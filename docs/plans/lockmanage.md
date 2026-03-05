---
Lock Manager 实现计划（修订版）

# 为什么需要 Lock Manager

当前 MiniDB 已有两层并发控制（页面 Latch + B+Tree 蟹行协议），但这两层都是**物理级短锁**，
只保护内存数据结构在单次操作期间的一致性。它们无法解决以下问题：

1. **事务隔离**: 没有行级逻辑锁，无法实现 READ_COMMITTED / REPEATABLE_READ 等隔离级别。
   两个事务可以同时修改同一行记录，导致 Lost Update。
2. **写写冲突**: INSERT/UPDATE/DELETE 没有排他锁保护，并发写同一行会产生数据覆盖。
3. **当前读一致性**: SELECT ... FOR UPDATE / LOCK IN SHARE MODE 无法实现，
   因为没有持续到事务结束的锁机制。
4. **幻读防护**: 后续 Gap Lock / Next-Key Lock 依赖 Lock Manager 基础设施。

Lock Manager 提供 InnoDB 风格的**事务级逻辑锁**：生命周期跟随事务（commit/rollback 时释放），
与 MVCC ReadView 配合，共同实现完整的事务隔离。

---

一、现状分析

当前项目已有两层并发控制：
┌────────────────────┬────────────────────────┬────────────────────┬──────────────────────────────┐
│        层级        │          机制          │        位置        │             特点             │
├────────────────────┼────────────────────────┼────────────────────┼──────────────────────────────┤
│ 页面物理锁 (Latch) │ ReentrantReadWriteLock │ BufferFrame        │ 短生命周期，保护内存数据结构 │
├────────────────────┼────────────────────────┼────────────────────┼──────────────────────────────┤
│ B+Tree 协议        │ 蟹行协议 + 乐观并发    │ ConcurrentBTreeOps │ 页面遍历期间的短锁           │
└────────────────────┴────────────────────────┴────────────────────┴──────────────────────────────┘
缺失的是事务级逻辑锁 (Lock)：即 InnoDB 风格的行锁、意向锁，用于保证事务隔离级别，生命周期跟随事务（commit/rollback 时释放）。

二、Latch vs Lock 的核心区别
┌──────────┬─────────────────────┬──────────────────────┐
│   维度   │    Latch (已有)     │    Lock (待实现)     │
├──────────┼─────────────────────┼──────────────────────┤
│ 保护对象 │ 内存数据结构 (页面) │ 逻辑数据 (行/间隙)   │
├──────────┼─────────────────────┼──────────────────────┤
│ 生命周期 │ 操作期间（微秒级）  │ 事务期间（可达秒级） │
├──────────┼─────────────────────┼──────────────────────┤
│ 死锁处理 │ 锁顺序预防          │ Wait-for Graph 检测  │
├──────────┼─────────────────────┼──────────────────────┤
│ 等待机制 │ 自旋/超时           │ 队列等待 + 死锁检测  │
├──────────┼─────────────────────┼──────────────────────┤
│ 持有者   │ 线程                │ 事务                 │
└──────────┴─────────────────────┴──────────────────────┘

三、整体架构设计

┌──────────────────────────────────────────────────────┐
│                  LockManager (新增)                    │
│                                                      │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ LockTable │  │ WaitQueue    │  │ DeadlockDetect │  │
│  │ (分段Hash)│  │ (per record) │  │ (WFG 图检测)   │  │
│  └──────────┘  └──────────────┘  └────────────────┘  │
│                                                      │
│  锁模式: S / X / IS / IX                              │
│  锁粒度: Table Lock / Record Lock                     │
│  锁类型: Record / Gap / Next-Key (Phase 5)            │
└─────────────────────────┬────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
TransactionManager  ConcurrentBTree  Handler API
(commit/rollback    (DML 加行锁)    (上层调用入口)
 释放所有锁)

---

四、致命隐患修正

以下问题在原始设计中未覆盖，如果不解决将导致正确性或稳定性问题。

### 4.1 LockRequestQueue 内存泄漏

**问题**: Map<LockTarget, LockRequestQueue> 只有 put 没有 remove，长期运行会 OOM。

**修正方案**: 在 release() 和 cancelWait() 末尾检查空队列并清理。

```java
// LockRequestQueue
void release(TransactionId trxId) {
    grantedList.removeIf(r -> r.getTrxId().equals(trxId));
    grantWaiters();

    // 清理空队列
    if (grantedList.isEmpty() && waitingList.isEmpty()) {
        this.empty = true;  // 通知 LockTableSegment 移除此 entry
    }
}
```

```java
// LockTableSegment
void releaseLocks(TransactionId trxId, LockTarget target) {
    lock.lock();
    try {
        LockRequestQueue queue = map.get(target);
        if (queue != null) {
            queue.release(trxId);
            if (queue.isEmpty()) {
                map.remove(target);  // 关键：移除空队列
            }
        }
    } finally {
        lock.unlock();
    }
}
```

**不变量 L7**: LockTableSegment 中不允许存在 grantedList 和 waitingList 同时为空的 LockRequestQueue。每次 release/cancel 操作必须检查并清理。

### 4.2 Page Split/Merge 导致锁失效

**问题**: Split/Merge 时记录迁移到新页面，heapNo 完全重新分配，原有行锁指向的 (pageNo, heapNo) 失效。

**源码验证**:
- PageSplit.java:188-191 — 记录迁移到新页时 heapNo 重新分配
- PageMerge.java:129-130 — redistribute 时页面清空重建

**修正方案（MiniDB 阶段推荐）**: 利用 Latch-Lock 排序规则间接保证正确性。

正确的加锁-操作流程：
1. B+Tree 遍历获取 Latch
2. 读取 heapNo
3. 释放 Latch
4. 对 (pageNo, heapNo) 加 Lock（可能等待）
5. 重新获取 Latch
6. 验证 heapNo 仍然有效（未被 Split 改变）
7. 如果无效，释放 Lock，回到步骤 1 重试

**不变量 L8**: 加行锁后必须验证 (pageNo, heapNo) 仍然指向目标记录。如果 Page LSN 在 Lock 等待期间发生变化，必须重新定位记录。

### 4.3 锁升级死锁 (S→X)

**问题**: T1 持有 S，T2 持有 S，T1 请求升级 X，T2 也请求升级 X → 互相等待，死锁。

**修正方案**: 升级请求在存在其他 S 持有者时立即返回 DEADLOCK。

```java
LockResult tryUpgrade(LockRequest upgradeRequest) {
    TransactionId trxId = upgradeRequest.getTrxId();

    long otherShareHolders = grantedList.stream()
        .filter(r -> !r.getTrxId().equals(trxId))
        .filter(r -> r.getMode() == LockMode.SHARED)
        .count();

    if (otherShareHolders == 0) {
        // 只有自己持有 S 锁 → 直接升级
        updateGrant(trxId, LockMode.EXCLUSIVE);
        return LockResult.GRANTED;
    } else {
        // 存在其他共享持有者 → 立即失败
        return LockResult.DEADLOCK;
    }
}
```

**不变量 L9**: S→X 升级请求在存在其他 S 持有者时必须立即返回 DEADLOCK，禁止进入等待队列。

---

五、逻辑完善修正

### 5.1 写饥饿 (Writer Starvation)

**问题**: 原设计 "waitingList 为空且兼容→直接授予" 导致连续 S 请求可以不断插入，X 请求永远等不到。

**修正方案**: 引入 FIFO 公平性检查。

```java
LockResult tryGrant(LockRequest newRequest) {
    // 规则 1: 锁重入/升级 — 允许插队
    LockRequest existingGrant = findGrant(newRequest.getTrxId());
    if (existingGrant != null) {
        if (existingGrant.getMode().isStrongerOrEqual(newRequest.getMode())) {
            return LockResult.GRANTED;  // 已持有更强的锁
        }
        return tryUpgrade(newRequest);
    }

    // 规则 2: FIFO 公平性 — waitingList 非空时，新请求必须排队
    if (!waitingList.isEmpty()) {
        addToWait(newRequest);
        return LockResult.WAIT;
    }

    // 规则 3: waitingList 为空，检查兼容性
    if (newRequest.getMode().isCompatibleWith(grantedGroupMode)) {
        addToGranted(newRequest);
        return LockResult.GRANTED;
    }

    // 规则 4: 不兼容，排队
    addToWait(newRequest);
    return LockResult.WAIT;
}
```

**不变量 L10**: 当 waitingList 非空时，除锁重入外，新请求必须排队，即使与当前已授予模式兼容。

### 5.2 INSERT_INTENTION 锁预留

Phase 1 不实现，但架构兼容。兼容矩阵使用二维数组实现，方便后续扩展。

```java
public enum LockMode {
    SHARED(0),
    EXCLUSIVE(1),
    INTENTION_SHARED(2),
    INTENTION_EXCLUSIVE(3);
    // Phase 5 追加: INSERT_INTENTION(4)

    private static final boolean[][] COMPAT_MATRIX = { ... };

    public boolean isCompatibleWith(LockMode other) {
        return COMPAT_MATRIX[this.ordinal()][other.ordinal()];
    }
}
```

LockType 从 Phase 1 开始定义：

```java
public enum LockType {
    RECORD,   // Phase 1: 行锁
    TABLE,    // Phase 1: 表锁
    // Phase 5: GAP, NEXT_KEY, INSERT_INTENTION
}
```

### 5.3 Savepoint 策略

MiniDB 简化处理：Savepoint 回滚不释放锁，遵循严格 2PL。

```java
public class TransactionLockContext {
    private final List<LockRequest> heldLocks = new ArrayList<>();

    public void addLock(LockRequest request) {
        heldLocks.add(request);
    }

    // 唯一释放入口: commit/full rollback
    public List<LockRequest> releaseAll() {
        List<LockRequest> released = new ArrayList<>(heldLocks);
        heldLocks.clear();
        return released;
    }

    // RC 隔离级别: 读完即释放 S 锁
    public void releaseSharedLocks() {
        Iterator<LockRequest> it = heldLocks.iterator();
        List<LockRequest> toRelease = new ArrayList<>();
        while (it.hasNext()) {
            LockRequest req = it.next();
            if (req.getMode() == LockMode.SHARED) {
                toRelease.add(req);
                it.remove();
            }
        }
        // 通知 LockManager 释放这些锁
    }
}
```

**不变量 L11**: 除 RC 隔离级别的 S 锁提前释放外，所有锁只在 commit/rollback 时释放。Savepoint 回滚不释放锁。

---

六、性能与工程优化修正

### 6.1 死锁检测策略优化

**问题**: 每次 wait 都跑 WFG 开销过大。

**修正方案**: 分层策略。

**Layer 1 (默认)**: Lock Wait Timeout
- 配置项: lockWaitTimeoutMs = 50_000 (50秒)
- 超时后抛出 WaitTimeoutException，上层回滚事务
- 零额外开销

**Layer 2 (可选开启)**: 延迟 WFG 检测
- 等待超过 deadlockDetectDelayMs (默认 1000ms) 后触发
- 后台 Detector 线程周期性扫描
- 检测到环 → 选择 getTotalModifications() 最小的事务作牺牲者

```java
public class LockManager {
    private final long lockWaitTimeoutMs;        // 50s
    private final long deadlockDetectDelayMs;    // 1s
    private final boolean enableActiveDetection; // 默认 false

    LockResult waitForLock(LockRequest request) {
        long deadline = System.currentTimeMillis() + lockWaitTimeoutMs;

        while (System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(checkIntervalNs);

            if (request.isGranted()) return LockResult.GRANTED;
            if (request.isAborted()) return LockResult.DEADLOCK;

            // 可选: 等待超过阈值后注册到 DeadlockDetector
            if (enableActiveDetection && waitTime > deadlockDetectDelayMs) {
                deadlockDetector.registerWaiting(request);
            }
        }
        throw new WaitTimeoutException(...);
    }
}
```

### 6.2 LockTarget Hash 优化

使用位混合避免 hash 冲突：

```java
public class LockTarget {
    private final LockType type;
    private final int spaceId;
    private final int pageNo;
    private final int heapNo;

    @Override
    public int hashCode() {
        int h = spaceId;
        h = h * 31 + pageNo;
        h = h * 31 + heapNo;
        h = h * 31 + type.ordinal();
        h ^= (h >>> 16);  // 位混合
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LockTarget t)) return false;
        return type == t.type
            && spaceId == t.spaceId
            && pageNo == t.pageNo
            && heapNo == t.heapNo;
    }
}
```

### 6.3 隐式锁优化 (Implicit Lock) — 后期可选

Phase 1 不实现，但在架构中预留接口。

原理：INSERT 时不加显式锁，记录的 TRX_ID 字段就是隐式锁。其他事务访问该记录时：
1. 读取记录的 TRX_ID
2. 检查 TRX_ID 对应的事务是否活跃
3. 如果活跃 → 帮该事务在 LockManager 创建显式 X 锁
4. 然后自己排队等待

好处: 大幅减少 INSERT 的锁开销（大多数记录不会被其他事务立即访问）。

```java
// LockManager 预留方法
LockResult implicitToExplicit(TransactionId insertTrxId,
                               int spaceId, int pageNo, int heapNo) {
    // Phase 后期实现
}
```

### 6.4 受检异常处理

```java
// 基类
public class LockException extends MiniDbException { ... }

// 死锁
public class DeadlockException extends LockException {
    private final TransactionId victimTrxId;
}

// 等待超时
public class LockWaitTimeoutException extends LockException {
    private final long waitedMs;
    private final LockTarget target;
}

// LockManager 接口签名
public interface LockManager {
    void lockRecord(Transaction trx, int spaceId, int pageNo, int heapNo,
                    LockMode mode) throws DeadlockException, LockWaitTimeoutException;

    void lockTable(Transaction trx, int tableId,
                   LockMode mode) throws DeadlockException, LockWaitTimeoutException;

    void unlockAll(Transaction trx);  // 不抛异常，必须成功
}
```

---

七、分阶段实现计划

### Phase 1: 核心锁基础设施

目标: 建立 Lock Manager 的基本框架，支持行级 S/X 锁。

**1.1 LockMode 枚举**

包路径: cn.zhangyis.minidb.storage.transaction.lock.LockMode

- SHARED (S): 共享锁，读操作（当前读，如 SELECT ... LOCK IN SHARE MODE）
- EXCLUSIVE (X): 排他锁，写操作（INSERT/UPDATE/DELETE）
- INTENTION_SHARED (IS): 意向共享锁（表级）
- INTENTION_EXCLUSIVE (IX): 意向排他锁（表级）

兼容矩阵（二维数组实现，方便 Phase 5 扩展 INSERT_INTENTION）：
┌─────┬─────┬─────┬─────┬─────┐
│     │  S  │  X  │ IS  │ IX  │
├─────┼─────┼─────┼─────┼─────┤
│ S   │ Y   │ N   │ Y   │ N   │
├─────┼─────┼─────┼─────┼─────┤
│ X   │ N   │ N   │ N   │ N   │
├─────┼─────┼─────┼─────┼─────┤
│ IS  │ Y   │ N   │ Y   │ Y   │
├─────┼─────┼─────┼─────┼─────┤
│ IX  │ N   │ N   │ Y   │ Y   │
└─────┴─────┴─────┴─────┴─────┘

**1.2 LockType 枚举**

```java
public enum LockType {
    RECORD,   // 行锁
    TABLE,    // 表锁
    // Phase 5: GAP, NEXT_KEY, INSERT_INTENTION
}
```

**1.3 LockTarget — 锁定目标标识**

- 行锁: (spaceId, pageNo, heapNo)
- 表锁: (tableId, 0, 0)
- 实现优化后的 equals/hashCode（位混合）
- 区分 TABLE 和 RECORD 类型

**1.4 LockRequest — 锁请求**

字段: transactionId, lockTarget, lockMode, status(WAITING/GRANTED/ABORTED), grantTime

**1.5 LockResult — 锁操作结果**

枚举: GRANTED, WAIT, DEADLOCK

**1.6 异常类**

- LockException (基类)
- DeadlockException
- LockWaitTimeoutException

**1.7 TransactionLockContext — 事务锁上下文**

- 记录该事务持有的所有锁
- commit/rollback 时通过 releaseAll() 释放
- Savepoint 回滚不释放锁（严格 2PL）
- RC 隔离级别支持 releaseSharedLocks()

解决的问题: #5 INSERT_INTENTION 预留, #6 Savepoint 策略, #8 Hash 优化, #10 异常处理

---

### Phase 2: LockTable 与授予逻辑

目标: 实现锁的授予、等待、释放核心逻辑。

**2.1 LockRequestQueue — 锁请求队列**

字段:
- List<LockRequest> grantedList — 已授予的锁
- List<LockRequest> waitingList — 等待中的锁
- LockMode grantedGroupMode — 已授予锁的聚合模式
- boolean empty — 空队列标志

核心方法:
- tryGrant(LockRequest) — 带 FIFO 公平性的授予逻辑（解决写饥饿）
- tryUpgrade(LockRequest) — S→X 升级（存在其他 S 持有者时立即 DEADLOCK）
- release(TransactionId) — 释放某事务的锁 + 空队列标记
- grantWaiters() — 尝试唤醒等待者

授予规则（修正后）：
1. 锁重入/升级 — 允许插队
2. FIFO 公平性 — waitingList 非空时，新请求必须排队（防止写饥饿）
3. waitingList 为空，检查与 grantedGroupMode 兼容性
4. 不兼容 → 排队等待

**2.2 LockTableSegment — 分段锁表**

- 参考 BufferPool 的 PageHashSegment 分段设计
- segments[] 数组，每个 segment 独立 ReentrantLock
- 通过 LockTarget.hashCode() 路由到 segment
- 每个 segment 内部: Map<LockTarget, LockRequestQueue>
- release 时检查空队列并 map.remove()（解决内存泄漏）

解决的问题: #1 内存泄漏, #3 升级死锁, #4 写饥饿

---

### Phase 3: LockManager 主类与等待机制

目标: 整合 LockTable，实现等待与死锁处理。

**3.1 LockManager — 对外接口**

```java
public interface LockManager {
    void lockRecord(Transaction trx, int spaceId, int pageNo, int heapNo,
                    LockMode mode) throws DeadlockException, LockWaitTimeoutException;
    void lockTable(Transaction trx, int tableId,
                   LockMode mode) throws DeadlockException, LockWaitTimeoutException;
    void unlockAll(Transaction trx);
    void unlockRecord(Transaction trx, LockTarget target);  // RC 下提前释放 S 锁
}
```

**3.2 锁等待机制**

- 使用 LockSupport.park/unpark 实现等待
- 分层策略:
  - Layer 1: Lock Wait Timeout（默认 50s，零开销）
  - Layer 2: 可选延迟 WFG 检测（等待超 1s 后触发）

等待流程:
1. 在 segment 锁内: 将请求加入 waitingList
2. 释放 segment 锁
3. park 当前线程（带超时）
4. 被 unpark 后重新获取 segment 锁，检查是否被授予

**3.3 DeadlockDetector — Wait-for Graph 死锁检测**

- 可选开启，默认关闭（仅靠 timeout）
- 后台线程周期性扫描 wait-for 关系
- BFS/DFS 检测环
- 选择 getTotalModifications() 最小的事务作牺牲者
- 标记为 ABORTED + unpark

解决的问题: #7 死锁检测性能

---

### Phase 4: 与现有组件集成

**4.1 Transaction 集成**

- Transaction 新增 TransactionLockContext lockContext 字段
- TransactionManager.commit() 中调用 lockManager.unlockAll(trx)
- TransactionManager.rollback() 中调用 lockManager.unlockAll(trx)

**4.2 B+Tree 操作集成**

在 ConcurrentBTreeOps 的 DML 操作中加入行锁，遵循 Latch-Lock 排序：

INSERT 流程:
1. lockManager.lockTable(trx, tableId, IX)
2. 通过 B+Tree 定位插入位置（获取/释放 Latch）
3. lockManager.lockRecord(trx, space, page, heap, X)
4. 重新获取 Latch，验证 heapNo 有效性（L8）
5. 执行插入

SELECT ... FOR UPDATE 流程:
1. lockManager.lockTable(trx, tableId, IX)
2. B+Tree 搜索（获取/释放 Latch）
3. lockManager.lockRecord(trx, space, page, heap, X)
4. 重新获取 Latch，验证 heapNo

SELECT ... LOCK IN SHARE MODE 流程:
1. lockManager.lockTable(trx, tableId, IS)
2. B+Tree 搜索（获取/释放 Latch）
3. lockManager.lockRecord(trx, space, page, heap, S)
4. 重新获取 Latch，验证 heapNo

普通 SELECT (快照读):
不需要 Lock，只依赖 MVCC ReadView

**4.3 隔离级别适配**
┌──────────────────┬────────────────────┬─────────┐
│     隔离级别     │       读行为       │ 写行为  │
├──────────────────┼────────────────────┼─────────┤
│ READ_UNCOMMITTED │ 不加锁             │ 加 X 锁 │
├──────────────────┼────────────────────┼─────────┤
│ READ_COMMITTED   │ S 锁读完即释放     │ 加 X 锁 │
├──────────────────┼────────────────────┼─────────┤
│ REPEATABLE_READ  │ S 锁持续到事务结束 │ 加 X 锁 │
├──────────────────┼────────────────────┼─────────┤
│ SERIALIZABLE     │ 所有读加 S 锁      │ 加 X 锁 │
└──────────────────┴────────────────────┴─────────┘

解决的问题: #2 Page Split/Merge 锁失效

---

### Phase 5 (后期): Gap Lock 与 Next-Key Lock

目标: 在 REPEATABLE_READ 下防止幻读。

- LockType 扩展: GAP, NEXT_KEY, INSERT_INTENTION
- LockMode 扩展兼容矩阵，加入 INSERT_INTENTION
- Next-Key Lock = Record Lock + Gap Lock（锁定记录及其前面的间隙）
- 范围扫描时对扫描经过的记录加 Next-Key Lock
- INSERT 时检查间隙锁冲突
- INSERT_INTENTION 与 GAP 冲突，多个 INSERT_INTENTION 之间兼容

### 后期优化: 隐式锁 (Implicit Lock)

- INSERT 时不加显式锁，依赖记录 TRX_ID 字段作为隐式锁
- 其他事务访问时 implicit → explicit 转换
- 大幅减少 INSERT 锁开销

---

八、不变量总表
┌──────┬────────────────────────────────────────────────────────┬─────────┐
│ 编号 │                         不变量                         │  来源   │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L1   │ X 锁和 RR 下的 S 锁持续到 commit/rollback              │ 原      │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L2   │ 行锁前必须先获取表级意向锁                             │ 原      │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L3   │ 授予决策严格遵守兼容矩阵                               │ 原      │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L4   │ 死锁检测/超时必须终止等待                              │ 原      │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L5   │ unlockAll 幂等                                         │ 原      │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L6   │ Latch 与 Lock 解耦，禁止持 Latch 等 Lock               │ 原      │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L7   │ 空 LockRequestQueue 必须从 Map 中移除                  │ 新增    │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L8   │ 加行锁后必须验证 (pageNo, heapNo) 有效性               │ 新增    │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L9   │ S→X 升级存在其他 S 持有者时立即返回 DEADLOCK           │ 新增    │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L10  │ waitingList 非空时，非重入请求必须排队                 │ 新增    │
├──────┼────────────────────────────────────────────────────────┼─────────┤
│ L11  │ Savepoint 回滚不释放锁，只有 commit/full rollback 释放 │ 新增    │
└──────┴────────────────────────────────────────────────────────┴─────────┘

九、Latch-Lock 排序规则

获取顺序:   Lock → Latch → 操作 → 释放 Latch (Latch 在操作内释放)
Lock 在事务结束释放

禁止:       持有 Latch 时去获取 Lock（可能等待导致 Latch 长时间持有）

十、文件结构

cn.zhangyis.minidb.storage.transaction.lock/
├── LockMode.java              — 锁模式枚举 + 兼容矩阵 (二维数组)
├── LockType.java              — 锁类型 (RECORD / TABLE, 后期加 GAP / NEXT_KEY)
├── LockTarget.java            — 锁目标标识 (优化 Hash)
├── LockRequest.java           — 锁请求
├── LockRequestQueue.java      — 单个锁目标的请求队列 (FIFO 公平 + 升级处理 + 空队列清理)
├── LockResult.java            — 锁操作结果
├── LockTableSegment.java      — 分段锁表
├── LockManager.java           — 对外接口
├── TransactionLockContext.java — 事务锁上下文 (Savepoint 策略)
├── DeadlockDetector.java      — 死锁检测器 (可选延迟 WFG)
├── LockException.java         — 锁异常基类
├── DeadlockException.java     — 死锁异常
├── LockWaitTimeoutException.java — 等待超时异常
└── LockManagerMetrics.java    — 锁性能指标

十一、修订后执行顺序
┌───────┬──────────────────────────────────────────────────────────────────────────┬─────────────────┐
│ Phase │                                   内容                                 │   解决的问题    │
├───────┼──────────────────────────────────────────────────────────────────────────┼─────────────────┤
│ 1     │ LockMode (兼容矩阵数组) + LockType + LockTarget (Hash优化)             │ #5, #8          │
│       │ + LockRequest + LockResult + 异常类 + TransactionLockContext           │ #6, #10         │
├───────┼──────────────────────────────────────────────────────────────────────────┼─────────────────┤
│ 2     │ LockRequestQueue (FIFO公平 + 升级死锁处理 + 空队列清理)                │ #1, #3, #4      │
│       │ + LockTableSegment (分段Hash)                                          │                 │
├───────┼──────────────────────────────────────────────────────────────────────────┼─────────────────┤
│ 3     │ LockManager 主类 + Timeout 等待机制 + 可选延迟 WFG                      │ #7              │
├───────┼──────────────────────────────────────────────────────────────────────────┼─────────────────┤
│ 4     │ Transaction 集成 + B+Tree 集成 (含 Split/Merge 验证逻辑)               │ #2              │
├───────┼──────────────────────────────────────────────────────────────────────────┼─────────────────┤
│ 5     │ Gap Lock + INSERT_INTENTION + Next-Key Lock                             │ #5 完整实现     │
├───────┼──────────────────────────────────────────────────────────────────────────┼─────────────────┤
│ 后期  │ 隐式锁优化 (implicit → explicit 转换)                                  │ #9              │
└───────┴──────────────────────────────────────────────────────────────────────────┴─────────────────┘
