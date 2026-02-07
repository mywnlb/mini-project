# Undo Log 实现文档

## 概述

Undo Log 是 MiniDB 实现事务回滚和 MVCC 的核心机制。通过记录每个修改操作的反向操作，Undo Log 支持：
1. **事务回滚**：撤销未提交事务的所有修改
2. **MVCC 版本链**：维护记录的历史版本供其他事务读取
3. **崩溃恢复**：恢复时回滚未提交的事务

---

## 技术要点

### 1. 核心概念

#### Undo Record（Undo 记录）
- **定义**：记录一个数据修改操作的反向操作
- **三种类型**：
  - `INSERT_UNDO`：记录 INSERT 操作，回滚时删除该行
  - `UPDATE_UNDO`：记录 UPDATE 操作，回滚时恢复旧值
  - `DELETE_UNDO`：记录 DELETE 操作，回滚时恢复该行

#### Undo Segment（Undo 段）
- **定义**：一个事务的 Undo 记录集合
- **分类**：
  - `INSERT Undo Segment`：存储该事务的所有 INSERT Undo
  - `UPDATE Undo Segment`：存储该事务的所有 UPDATE/DELETE Undo
- **生命周期**：
  - 事务开始时分配
  - 事务提交时标记为已提交
  - INSERT Undo 立即释放，UPDATE Undo 由 Purge 线程清理

#### Rollback Segment（回滚段）
- **定义**：管理多个事务的 Undo Segment 的容器
- **数量**：128 个（可配置）
- **分配策略**：轮询方式，减少锁竞争
- **物理存储**：每个 Rollback Segment 对应一个 TableSpace Segment

#### Undo Page（Undo 页）
- **定义**：存储 Undo 记录的物理页面
- **格式**：
  ```
  [Page Header: 16 bytes]
    - undo_type (1 byte): INSERT or UPDATE
    - trx_id (4 bytes): 产生此页的事务 ID
    - rseg_id (1 byte): Rollback Segment ID
    - free_offset (4 bytes): 下一条记录的写入位置
  [Undo Records: variable]
    - [Record 1]
    - [Record 2]
    - ...
  ```

#### Rollback Pointer（回滚指针）
- **定义**：指向 Undo Log 中上一版本的指针
- **编码**：56 位 (7 字节)
  ```
  [is_insert:1bit][rseg_id:7bits][page_no:32bits][offset:16bits]
  ```
- **用途**：链接版本链中的相邻版本

### 2. 解决的问题

#### 事务回滚
- **问题**：事务执行中出错或用户主动回滚，需要撤销所有修改
- **解决**：按反向顺序执行 Undo 记录中的反向操作

#### MVCC 版本链
- **问题**：读操作需要访问历史版本，但记录只有一份
- **解决**：通过 Undo Log 存储历史版本，版本链通过 ROLL_PTR 链接

#### 空间回收
- **问题**：Undo Log 占用大量磁盘空间
- **解决**：
  - INSERT Undo 在事务提交时立即释放
  - UPDATE Undo 由 Purge 线程在所有活跃事务都不需要时释放

#### 崩溃恢复
- **问题**：数据库崩溃时，未提交的事务需要回滚
- **解决**：恢复时扫描 Undo Log，回滚所有未提交的事务

### 3. 为什么需要 Undo Log

1. **支持事务回滚**：ACID 中的 Atomicity 要求
2. **支持 MVCC**：允许读操作访问历史版本
3. **支持快照隔离**：提供一致的数据视图
4. **支持崩溃恢复**：恢复时回滚未提交的事务
5. **减少锁竞争**：通过版本链避免读写冲突

---

## 实现计划

### 阶段 1：基础数据结构（已完成）

#### 1.1 UndoRecord 及其子类
- `UndoRecord`：抽象基类，定义通用接口
- `InsertUndoRecord`：记录 INSERT 操作
- `UpdateUndoRecord`：记录 UPDATE 操作
- `DeleteUndoRecord`：记录 DELETE 操作

#### 1.2 UndoPage
- 物理页面格式定义
- 页面初始化和记录读写

#### 1.3 UndoSegment
- 管理单个事务的 Undo 记录
- 支持顺序写入和反向遍历
- 状态管理（ACTIVE, COMMITTED, ROLLED_BACK, PURGED）

#### 1.4 RollbackPointer
- 56 位指针编码/解码
- 支持序列化/反序列化

### 阶段 2：Undo Log 管理（已完成）

#### 2.1 UndoLogManager
- 管理 128 个 Rollback Segment
- 为事务分配 INSERT/UPDATE Undo Segment
- 提供 Undo 记录的读写接口
- 支持事务提交/回滚时的 Undo 清理

#### 2.2 物理空间管理
- 每个 Rollback Segment 对应一个 TableSpace Segment
- 支持 32 碎片页 + Extent 分配策略
- 页面释放后可复用

#### 2.3 Undo Page 缓存
- `UndoPageCache`：缓存热点 Undo 页面
- 减少 Buffer Pool 查找开销

### 阶段 3：事务集成（已完成）

#### 3.1 TransactionalDml
- INSERT 时写入 INSERT Undo
- UPDATE 时写入 UPDATE Undo
- DELETE 时写入 DELETE Undo

#### 3.2 事务提交
- INSERT Undo 立即释放
- UPDATE Undo 加入 History List

#### 3.3 事务回滚
- 按反向顺序执行 Undo 记录
- 释放所有 Undo 页面

### 阶段 4：Purge 集成（已完成）

#### 4.1 History List
- 维护已提交事务的 UPDATE Undo 按提交顺序排列
- 支持按 Rollback Segment 分组查询

#### 4.2 PurgeCoordinator
- 计算可以安全清理的 Undo 边界
- 基于最老活跃 ReadView 的 upLimitId

#### 4.3 PurgeThread
- 后台线程定期清理 Undo
- 按 Rollback Segment 分组批量处理

---

## 核心设计约束（Invariants）

### U1：Undo 记录不可修改
- Undo 记录一旦写入就不能修改
- 保证版本链的一致性

### U2：版本链完整性
- 每条记录的版本链必须以 INSERT Undo 结尾
- 版本链中的 trx_id 必须单调递减

### U3：INSERT/UPDATE Undo 分离
- 每个事务有两个独立的 Undo Segment
- INSERT Undo 提交时立即释放
- UPDATE Undo 由 Purge 线程清理

### U4：Undo 页面分配
- 前 32 页从碎片区分配
- 之后从 Extent 链表分配
- 页面释放后可复用

### U5：Rollback Segment 并发安全
- 每个 Rollback Segment 有独立的锁
- 减少锁竞争

### U6：Undo 记录顺序
- 同一事务的 Undo 记录按修改顺序排列
- 回滚时按反向顺序执行

### U7：Undo 空间回收
- INSERT Undo 在事务提交时立即释放
- UPDATE Undo 在 Purge 时释放
- 防止 Undo 空间无限增长

### U8：Undo 读取安全
- Undo 记录不可修改，无需锁
- 版本链遍历时无需额外同步

---

## 关键文件

| 文件 | 行数 | 职责 |
|------|------|------|
| UndoRecord.java | ~100 | Undo 记录基类 |
| InsertUndoRecord.java | ~80 | INSERT Undo 记录 |
| UpdateUndoRecord.java | ~120 | UPDATE Undo 记录 |
| DeleteUndoRecord.java | ~100 | DELETE Undo 记录 |
| UndoPage.java | ~150 | Undo 页面格式 |
| UndoSegment.java | ~200 | Undo 段管理 |
| UndoPageCache.java | ~100 | Undo 页面缓存 |
| UndoLogManager.java | 1005 | Undo Log 管理器 |
| RollbackPointer.java | 407 | 回滚指针 |

---

## 使用示例

### 写入 Undo 记录

```java
// 事务开始时分配 Undo Segment
Transaction trx = transactionManager.begin();
UndoSegment insertSeg = undoLogManager.assignInsertSegment(trx);
UndoSegment updateSeg = undoLogManager.assignUpdateSegment(trx);

// INSERT 时写入 INSERT Undo
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    RollbackPointer rollPtr = undoLogManager.writeInsertUndo(
        mtr, trx, tableId, primaryKey);
    // 使用 rollPtr 设置记录的 ROLL_PTR
    mtr.commit();
}

// UPDATE 时写入 UPDATE Undo
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    RollbackPointer rollPtr = undoLogManager.writeUpdateUndo(
        mtr, trx, tableId, prevRollPtr, primaryKey, oldColumns);
    // 使用 rollPtr 更新记录的 ROLL_PTR
    mtr.commit();
}
```

### 事务提交

```java
// 提交时释放 INSERT Undo，保留 UPDATE Undo
undoLogManager.commitTransaction(trx);
// - INSERT Undo 页面立即释放
// - UPDATE Undo 加入 History List
```

### 事务回滚

```java
// 回滚时执行反向操作
Iterable<UndoRecord> undoRecords = undoLogManager.rollbackTransaction(trx);
for (UndoRecord record : undoRecords) {
    if (record instanceof InsertUndoRecord) {
        // 删除插入的行
        deleteRow(record.getPrimaryKey());
    } else if (record instanceof UpdateUndoRecord) {
        // 恢复旧值
        updateRow(record.getPrimaryKey(), record.getOldValues());
    } else if (record instanceof DeleteUndoRecord) {
        // 恢复删除的行
        insertRow(record.getOldRowData());
    }
}
// 所有 Undo 页面释放
```

### 版本链遍历

```java
// 读取 Undo 记录
UndoRecord record = undoLogManager.readUndoRecord(rollPtr);

// 遍历版本链
VersionChainReader reader = new VersionChainReader(undoLogManager);
RecordVersion visibleVersion = reader.findVisibleVersion(
    record.getRollPtr(), readView);
```

---

## 与 InnoDB 的对应关系

| MiniDB | InnoDB | 说明 |
|--------|--------|------|
| UndoRecord | trx_undo_rec_t | Undo 记录 |
| UndoSegment | trx_undo_t | Undo 段 |
| Rollback Segment | trx_rseg_t | 回滚段 |
| UndoPage | undo page | Undo 页面 |
| RollbackPointer | roll_ptr_t | 回滚指针 |
| UndoLogManager | trx_undo_mgr | Undo 管理器 |

---

## 性能特征

### 时间复杂度
- Undo 记录写入：O(1)
- Undo 记录读取：O(1)
- 版本链遍历：O(k)，其中 k = 版本链深度

### 空间复杂度
- 每个 Undo 记录：O(n)，其中 n = 修改的列数
- 每个 Undo Segment：O(m)，其中 m = 修改次数
- 总 Undo 空间：O(活跃事务数 × 修改次数)

### 优化机会
1. **差异存储**：UPDATE Undo 只存储修改的列
2. **页面复用**：释放的 Undo 页面可被后续事务复用
3. **批量清理**：Purge 线程按 Rollback Segment 批量处理
4. **缓存优化**：UndoPageCache 减少 Buffer Pool 查找

---

## 测试覆盖

### 单元测试
- [ ] UndoRecord 序列化/反序列化
- [ ] UndoPage 记录读写
- [ ] UndoSegment 顺序写入和反向遍历
- [ ] RollbackPointer 编码/解码
- [ ] 边界情况（满页、空页、多页）

### 集成测试
- [ ] 事务提交时 Undo 清理
- [ ] 事务回滚时反向操作
- [ ] 版本链遍历正确性
- [ ] 并发事务的 Undo 隔离

### 性能测试
- [ ] 大量 Undo 记录的写入性能
- [ ] 版本链遍历性能
- [ ] Undo 页面缓存效果
- [ ] Purge 线程的清理性能

---

## 已知限制

1. **版本链深度限制**：最多遍历 1000 个版本
2. **Undo 空间占用**：UPDATE Undo 需要保留到 Purge 完成
3. **Rollback Segment 数量固定**：128 个，不支持动态扩展

---

## 后续改进方向

### 优先级和实现顺序

| 优先级 | 改进方向 | 难度 | 收益 | 核心特点 |
|--------|---------|------|------|---------|
| 5 | Undo 预分配 | 低 | 中 | 局部优化，不改核心语义 |
| 4 | 自适应 Purge | 中 | 中 | 局部优化，不改核心语义 |
| 2 | 增量 Undo | 中 | 高 | 局部优化，不改核心语义 |
| 1 | Undo 压缩（剪枝跳跃指针） | 高 | 中 | 高风险，涉及数据页 + 链指针 |

---

### 优先级 5：Undo 预分配

**目标**：O(1) 分配，减少 Extent 查询开销

**核心思路**：为每个 Rollback Segment 预先分配 Undo 页面池，分离 freshPages 和 reusablePages

**关键设计**：

1. **两层池结构**
   ```
   UndoPagePool {
       Queue<PageId> freshPages;      // 从未写过，可直接分配
       Queue<PageId> reusablePages;   // purge 后变空，可重用
   }
   ```

2. **分配流程**
   ```
   allocateUndoPage() {
       if (!freshPages.isEmpty()) return freshPages.poll();
       if (!reusablePages.isEmpty()) return reusablePages.poll();
       return allocateNewPageFromExtent();  // 最后才扩展
   }
   ```

3. **回收流程**
   ```
   releaseUndoPage(pageId) {
       // purge 线程调用，页面已清空
       reusablePages.offer(pageId);
   }
   ```

4. **实现位置**
   - `UndoPagePool`：两层队列管理
   - `RollbackSegment`：集成 pool
   - `UndoLogManager.allocateUndoPage()`：优先从 pool 取
   - `PurgeThread`：回收空页到 reusablePages
   - 后台 refiller 线程：补充 freshPages

**关键约束**：
- ❌ 不做真实释放，只做复用
- ✅ freshPages 和 reusablePages 统计分开
- ✅ 无锁队列（ConcurrentLinkedQueue）保证并发安全
- ✅ 补充操作在后台线程执行，不阻塞分配

**不变量维护**：
- U4：Undo 页面分配 - 预分配仍遵循 32 页 + Extent 规则
- U7：Undo 空间回收 - 池中的页面可被复用

---

### 优先级 4：自适应 Purge

**目标**：根据多指标动态调整 purge 频率和强度

**核心思路**：监控 5 个关键指标，多指标决策，防止长事务拖死 purge

**关键指标**：

1. `undo_space_ratio`：已使用 Undo 空间占比
2. `history_list_length`：待 purge 版本数
3. `purge_lag`：purge 处理速度跟不上产生速度的滞后
4. `oldest_read_view_age`：最老一致性读年龄（秒）
5. `(可选) trx_throttle`：空间危急时限制新事务

**决策规则**：

```
if (oldest_read_view_age > 60s) {
    // 长事务拖死 purge，需要激进清理
    purge_batch_size = MAX;
    purge_sleep_ms = MIN;
}
if (undo_space_ratio > 80%) {
    // 空间危急
    purge_batch_size = AGGRESSIVE;
    (可选) block_new_transactions();
}
if (history_list_length > threshold) {
    // 待清理版本堆积
    purge_batch_size = INCREASE;
}
```

**实现位置**：
- `UndoSpaceMonitor`：收集 5 个关键指标
- `AdaptivePurgeScheduler`：多指标决策
- `PurgeCoordinator`：动态应用策略
- 指标导出（JMX）

**关键约束**：
- ✅ 不能只看 undo_space_ratio，必须检测长事务
- ✅ oldest_read_view_age > 60s 时激进清理
- ✅ 支持可选的事务限流（空间危急时）

**不变量维护**：
- U7：Undo 空间回收 - 自适应 Purge 确保及时回收

---

### 优先级 2：增量 Undo

**目标**：只存储修改的列，减少空间占用 30-70%

**核心思路**：UPDATE Undo 使用 TLV 格式，记录 formatVersion/schemaVersion，支持新旧格式混读

**关键设计**：

1. **Undo 记录格式升级**
   ```
   UndoRecordHeader {
       byte formatVersion;        // Undo 格式版本
       byte schemaVersion;        // 表 schema 版本
       byte type;                 // INSERT/UPDATE/DELETE
       // ... 其他字段
   }

   UpdateUndoRecord {
       List<UndoField> fields;    // TLV 格式：(colId, oldValue)
       // 而不是存储整行数据
   }
   ```

2. **版本链重建算法**
   ```
   从新到旧遍历版本链：
     v_new = 当前记录
     for each undo in chain {
         if (undo.schemaVersion != v_new.schemaVersion) {
             // schema 变更，需要转换
             v_new = applySchemaEvolution(v_new, undo);
         }
         // 按需补齐列
         for each col in undo.fields {
             if (v_new[col] == NULL) {
                 v_new[col] = undo[col];
             }
         }
         if (allColumnsReconstructed(v_new)) break;  // 补齐即停
     }
   ```

3. **实现位置**
   - `UndoRecordHeader`：加 formatVersion/schemaVersion
   - `UpdateUndoRecord`：改为 TLV 格式 List<UndoField>
   - `UndoPage`：序列化/反序列化按 version 分支
   - `TransactionalDml`：写 undo 时只记录变更列
   - `VersionReconstructor`：从新到旧遍历，按需补齐列，补齐即停

**关键约束**：
- ✅ formatVersion/schemaVersion 必须记录
- ✅ 新旧格式混读支持（严格按 version 分支）
- ✅ schema 变更时需要转换逻辑
- ✅ 补齐即停（不必遍历整条链）

**测试**：
- 单列/多列/宽表更新
- 多次更新链重建正确性
- 新旧格式混读

**不变量维护**：
- U1：Undo 记录不可修改 - 仍然不可修改
- U2：版本链完整性 - 需要确保列继承逻辑正确
- U8：Undo 读取安全 - 仍然无需锁

---

### 优先级 1：Undo 压缩（剪枝跳跃指针）

**目标**：减少版本链深度，加速版本链遍历

**核心思路**：追加写新 Undo 记录，跳过中间版本，不改写/删除旧 Undo

**关键设计**：

1. **合并条件**
   - 同一记录（RecordId/PK）
   - 连续 UPDATE（遇到 DELETE/INSERT 边界停止）
   - 链上所有 undo.trx_id < purge_view.low_limit_id
   - 当前 record.roll_ptr 未变化（ABA 校验）
   - 合并后记录大小不超页可用空间

2. **时序**
   ```
   原始链：record.roll_ptr → U3 → U2 → U1 → NULL
                             ↑    ↑    ↑
                           (都是 UPDATE，可合并)

   合并后：record.roll_ptr → U_merged → NULL
           (U_merged 直接指向 U1 之前的版本)

   旧 U3/U2/U1 进入正常 purge 回收流程
   ```

3. **实现位置**
   - `UndoPruner`：在 purge 扫 history list 时识别"同一记录连续 UPDATE 段"
   - `UndoLogManager.appendMergedUndo()`：追加写合并后的新 undo（含 redo）
   - `ClusteredIndexAccessor.updateRollPtr()`：更新记录 roll_ptr（redo 记录）

4. **执行流程**
   ```
   1. Purge 得到 purge_view
   2. 扫 history list 找候选链段
   3. 对每条记录：
      a. X-latch 该记录所在聚簇页
      b. 复验 roll_ptr 未变化（ABA 校验）
      c. 追加写 U_merged（含 redo）
      d. redo + 更新 record.roll_ptr = mergedPtr
      e. 旧段进入正常 purge free
   ```

5. **Java 骨架**
   ```java
   final class UndoPruner {
       void prune(PurgeView view, RecordId rid) {
           clusteredIndex.xLatchRecordPage(rid, () -> {
               RecordHeader h = clusteredIndex.readHeader(rid);
               if (!isPrunable(view, h)) return;

               VersionChain seg = undoLogManager.collectContinuousUpdates(
                   h.rollPtr(), view);
               MergePlan plan = MergePlan.tryBuild(seg);
               if (plan == null) return;

               RollPtr merged = undoLogManager.appendMergedUndo(plan);
               redo.logUpdateRecordRollPtr(rid, h.rollPtr(), merged);
               clusteredIndex.updateRollPtr(rid, merged);
           });
       }
   }
   ```

**关键约束**：
- ❌ 不能改写/删除旧 undo 记录（违反 U1）
- ✅ 只能追加写新 undo 记录
- ✅ 必须 X-latch 数据页 + redo 记录
- ✅ 必须 ABA 校验防止并发冲突
- ✅ 涉及数据页链指针更新，风险最高

**不变量维护**：
- U1：Undo 记录不可修改 - 追加写保证
- U2：版本链完整性 - 合并后版本链仍完整
- U6：Undo 记录顺序 - 合并后仍保持顺序
- U7：Undo 空间回收 - 合并释放更多空间

**风险等级**：🔴 最高
- 涉及数据页 + 链指针更新
- 必须有完整的 latch + redo + 回归测试
- 并发冲突场景复杂
