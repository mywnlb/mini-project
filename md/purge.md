# Purge 系统实现文档

## 概述

Purge 是 MiniDB 的后台清理系统，负责回收不再被任何活跃事务需要的 Undo Log 空间。通过 Purge，系统可以：
1. **回收 Undo 空间**：释放已提交事务的 UPDATE Undo 占用的页面
2. **防止空间泄漏**：确保 Undo 空间不会无限增长
3. **维护性能**：减少 Undo Log 的大小，加快版本链遍历

---

## 技术要点

### 1. 核心概念

#### Purge 边界（Purge Limit）
- **定义**：所有 < 此值的事务 ID 对应的 Undo 都可以安全清理
- **计算方法**：所有活跃 ReadView 的 `upLimitId` 的最小值
- **含义**：
  - 所有 < purge_limit 的事务都已提交
  - 没有任何活跃 ReadView 需要这些事务的 Undo 版本
  - 这些 Undo 可以安全删除

#### History List（历史列表）
- **定义**：已提交事务的 UPDATE Undo Segment 按提交顺序排列的队列
- **用途**：
  - 维护提交顺序，确保 Purge 按顺序进行
  - 支持按 Rollback Segment 分组查询，减少锁竞争
- **生命周期**：
  - 事务提交时加入 History List
  - Purge 完成后从 History List 移除

#### PurgeCoordinator（Purge 协调器）
- **职责**：
  - 追踪所有活跃 ReadView
  - 计算 Purge 边界
  - 缓存 Purge 边界以避免频繁计算
- **关键方法**：
  - `registerReadView()`：注册新的 ReadView
  - `unregisterReadView()`：注销 ReadView
  - `getPurgeLimit()`：获取当前 Purge 边界

#### PurgeThread（Purge 线程）
- **职责**：
  - 后台定期运行
  - 从 History List 获取可清理的 Undo Segment
  - 调用 UndoLogManager 释放 Undo 页面
- **特点**：
  - 守护线程（Daemon Thread）
  - 幂等操作（失败不影响正确性）
  - 按 Rollback Segment 分组批量处理

### 2. 解决的问题

#### Undo 空间无限增长
- **问题**：UPDATE Undo 需要保留供 MVCC 使用，但不能永久保留
- **解决**：Purge 线程定期清理不再被需要的 Undo

#### 活跃 ReadView 追踪
- **问题**：如何判断一个 Undo 是否还被某个活跃事务需要？
- **解决**：通过 PurgeCoordinator 追踪所有活跃 ReadView，计算 Purge 边界

#### 并发安全性
- **问题**：Purge 线程和读操作并发执行，可能清理正在被读的 Undo
- **解决**：
  - Undo 记录不可修改，无需锁
  - 只有当所有活跃 ReadView 都不需要时才清理
  - 清理操作本身是幂等的

#### 性能优化
- **问题**：频繁计算 Purge 边界会成为性能瓶颈
- **解决**：
  - 缓存 Purge 边界
  - 按 Rollback Segment 分组批量处理
  - 限制每轮清理的记录数

### 3. 为什么需要 Purge

1. **防止 Undo 空间泄漏**：UPDATE Undo 需要及时清理
2. **维护系统性能**：减少 Undo Log 大小，加快版本链遍历
3. **支持长事务**：长事务不会因为 Undo 空间耗尽而失败
4. **实现 MVCC**：Purge 是 MVCC 的必要组成部分

---

## 实现计划

### 阶段 1：基础数据结构（已完成）

#### 1.1 HistoryList
- 维护已提交事务的 UPDATE Undo Segment
- 支持 FIFO 操作（add, poll, peek）
- 支持按 Rollback Segment 分组查询
- 线程安全（ConcurrentLinkedDeque）

#### 1.2 PurgeCoordinator
- 追踪活跃 ReadView
- 计算 Purge 边界
- 缓存 Purge 边界

#### 1.3 PurgeThread
- 后台线程定期运行
- 从 History List 获取可清理的 Undo
- 按 Rollback Segment 分组批量处理

### 阶段 2：集成（已完成）

#### 2.1 UndoLogManager 集成
- 提供 `purgeUpdateUndo()` 方法
- 提供 `getHistoryList()` 方法
- 提供 `getPurgableUpdateSegments()` 方法

#### 2.2 TransactionManager 集成
- 提供 `registerReadView()` 方法
- 提供 `unregisterReadView()` 方法
- 提供 `getOldestActiveReadViewUpLimitId()` 方法

#### 2.3 事务生命周期集成
- 事务提交时将 UPDATE Undo 加入 History List
- 事务提交/回滚时注销 ReadView

### 阶段 3：监控和调优（设计中）

#### 3.1 Purge 统计信息
- 已清理的 Undo 记录数
- Purge 轮数
- 当前 Undo 空间占用
- 活跃 ReadView 数量

#### 3.2 Purge 性能优化
- 自适应 Purge 频率
- 自适应批量大小
- Purge 暂停/恢复机制

---

## 核心设计约束（Invariants）

### P1：Purge 安全性
- 不能清理任何活跃 ReadView 可能需要的 Undo 记录
- 只有当所有活跃 ReadView 的 upLimitId 都 > undoTrxId 时才能清理

### P2：Purge 边界计算
- purge_limit = min(所有活跃 ReadView 的 upLimitId)
- 所有 < purge_limit 的 Undo 都可以安全清理

### P3：ReadView 生命周期
- ReadView 创建时必须注册到 PurgeCoordinator
- ReadView 销毁时必须从 PurgeCoordinator 注销
- 保证 Purge 边界计算的正确性

### P4：Purge 幂等性
- Purge 操作可以重复执行而不改变结果
- 失败不影响系统正确性，只影响空间回收

### P5：History List 顺序
- History List 中的条目必须按提交顺序排列
- Purge 必须按顺序处理，不能跳过

### P6：Undo 记录不可修改
- Undo 记录一旦写入就不能修改
- Purge 只能删除，不能修改

### P7：并发安全
- Purge 线程和读操作可以并发执行
- 不需要额外的同步机制（Undo 记录不可修改）

---

## 关键文件

| 文件 | 行数 | 职责 |
|------|------|------|
| HistoryList.java | 389 | 历史列表管理 |
| PurgeCoordinator.java | 228 | Purge 协调器 |
| PurgeThread.java | 404 | Purge 后台线程 |

---

## 使用示例

### 初始化 Purge 系统

```java
// 创建 Purge 协调器
PurgeCoordinator purgeCoordinator = new PurgeCoordinator(transactionManager);

// 创建 Purge 线程
PurgeThread purgeThread = new PurgeThread(purgeCoordinator, undoLogManager);

// 启动 Purge 线程
purgeThread.start();
```

### 事务提交时的 Purge 集成

```java
// 事务提交
public void commitTransaction(Transaction trx) {
    // ... 提交逻辑

    // 释放 INSERT Undo
    UndoSegment insertSeg = undoLogManager.getInsertSegment(trx.getId());
    if (insertSeg != null) {
        undoLogManager.freeInsertUndo(insertSeg);
    }

    // UPDATE Undo 加入 History List
    UndoSegment updateSeg = undoLogManager.getUpdateSegment(trx.getId());
    if (updateSeg != null) {
        undoLogManager.getHistoryList().add(trx.getId(), updateSeg.getRsegId());
    }

    // 注销 ReadView
    purgeCoordinator.unregisterReadView(readView);
}
```

### 读操作时的 ReadView 注册

```java
// 读操作开始
public DataTuple read(Transaction trx, byte[] key) {
    // 获取或创建 ReadView
    ReadView readView = trx.getOrCreateReadView();

    // 注册 ReadView
    purgeCoordinator.registerReadView(readView);

    try {
        // ... 读操作逻辑
        return result;
    } finally {
        // 读操作结束时注销 ReadView
        purgeCoordinator.unregisterReadView(readView);
    }
}
```

### Purge 线程的工作流程

```java
// Purge 线程主循环
while (running) {
    // 1. 获取当前 Purge 边界
    TransactionId purgeLimit = purgeCoordinator.getPurgeLimit();

    // 2. 从 History List 获取可清理的条目
    HistoryList historyList = undoLogManager.getHistoryList();
    List<HistoryEntry> purgableEntries = historyList.getPurgableEntries(
        purgeLimit, maxRecordsPerRound);

    // 3. 按 Rollback Segment 分组
    Map<Integer, List<HistoryEntry>> byRseg = groupByRseg(purgableEntries);

    // 4. 批量清理
    for (Map.Entry<Integer, List<HistoryEntry>> group : byRseg.entrySet()) {
        int rsegId = group.getKey();
        List<HistoryEntry> entries = group.getValue();

        for (HistoryEntry entry : entries) {
            // 清理 UPDATE Undo
            undoLogManager.purgeUpdateUndo(entry.getTrxId());

            // 从 History List 移除
            historyList.remove(entry);
        }
    }

    // 5. 等待下一个周期
    Thread.sleep(purgeIntervalMs);
}
```

---

## 与 InnoDB 的对应关系

| MiniDB | InnoDB | 说明 |
|--------|--------|------|
| HistoryList | history list | 历史列表 |
| PurgeCoordinator | purge coordinator | Purge 协调器 |
| PurgeThread | purge thread | Purge 线程 |
| purge_limit | purge_limit | Purge 边界 |

---

## 性能特征

### 时间复杂度
- 计算 Purge 边界：O(n)，其中 n = 活跃 ReadView 数
- 从 History List 获取可清理条目：O(m)，其中 m = History List 大小
- 清理单个 Undo：O(k)，其中 k = Undo 页面数

### 空间复杂度
- History List：O(m)，其中 m = 已提交但未清理的事务数
- PurgeCoordinator：O(n)，其中 n = 活跃 ReadView 数

### 优化机会
1. **缓存 Purge 边界**：避免频繁计算
2. **按 Rollback Segment 分组**：减少锁竞争
3. **批量清理**：减少函数调用开销
4. **自适应 Purge 频率**：根据 Undo 空间占用动态调整

---

## 测试覆盖

### 单元测试
- [ ] HistoryList 的 add/remove/poll 操作
- [ ] HistoryList 的按 Rollback Segment 分组查询
- [ ] PurgeCoordinator 的 Purge 边界计算
- [ ] PurgeCoordinator 的 ReadView 注册/注销
- [ ] 边界情况（空 History List、单个 ReadView、多个 ReadView）

### 集成测试
- [ ] 事务提交时 Undo 加入 History List
- [ ] 事务提交/回滚时 ReadView 注销
- [ ] Purge 线程正确清理 Undo
- [ ] Purge 不会清理活跃 ReadView 需要的 Undo
- [ ] 并发事务的 Purge 安全性

### 性能测试
- [ ] 大量 ReadView 下的 Purge 边界计算
- [ ] 大量 Undo 的清理性能
- [ ] Purge 线程对读操作的影响
- [ ] History List 的内存占用

---

## 已知限制

1. **Purge 延迟**：Undo 清理有延迟，不是立即进行
2. **History List 大小**：可能在长事务场景下增长很大
3. **Purge 线程单线程**：不支持多线程并行 Purge

---

## 后续改进方向

1. **多线程 Purge**：支持多个 Purge 线程并行清理
2. **自适应 Purge**：根据 Undo 空间占用动态调整 Purge 频率
3. **优先级 Purge**：优先清理占用空间最多的 Undo
4. **增量 Purge**：支持增量式清理，避免长时间锁定
5. **分布式 Purge**：支持跨节点的 Purge 协调

---

## Purge 工作流程图

```
┌─────────────────────────────────────────────────────────────┐
│                    Purge 系统工作流程                        │
└─────────────────────────────────────────────────────────────┘

1. 事务提交
   ├─ 释放 INSERT Undo 页面
   ├─ UPDATE Undo 加入 History List
   └─ 注销 ReadView

2. PurgeCoordinator 计算 Purge 边界
   ├─ 遍历所有活跃 ReadView
   ├─ 找最小的 upLimitId
   └─ 缓存结果

3. PurgeThread 定期运行
   ├─ 获取 Purge 边界
   ├─ 从 History List 获取可清理条目
   ├─ 按 Rollback Segment 分组
   ├─ 批量清理 Undo 页面
   └─ 从 History List 移除条目

4. 循环回到步骤 3
```

---

## Purge 安全性保证

### 场景 1：长事务阻止 Purge

```
时间线：
T1: 事务 A 开始（ReadView_A: upLimitId=100）
T2: 事务 B 修改记录，生成 Undo（TRX_ID=101）
T3: 事务 B 提交，Undo 加入 History List
T4: 事务 C 修改记录，生成 Undo（TRX_ID=102）
T5: 事务 C 提交，Undo 加入 History List
T6: Purge 线程运行
    - Purge 边界 = min(ReadView_A.upLimitId) = 100
    - 只能清理 < 100 的 Undo
    - TRX_ID=101, 102 的 Undo 不能清理（因为 >= 100）
T7: 事务 A 提交，ReadView_A 注销
T8: Purge 线程再次运行
    - Purge 边界 = 下一个要分配的 TRX_ID
    - 现在可以清理 TRX_ID=101, 102 的 Undo
```

### 场景 2：多个活跃 ReadView

```
时间线：
T1: 事务 A 开始（ReadView_A: upLimitId=100）
T2: 事务 B 开始（ReadView_B: upLimitId=105）
T3: 事务 C 修改记录，生成 Undo（TRX_ID=110）
T4: 事务 C 提交，Undo 加入 History List
T5: Purge 线程运行
    - Purge 边界 = min(100, 105) = 100
    - 只能清理 < 100 的 Undo
    - TRX_ID=110 的 Undo 不能清理
T6: 事务 A 提交，ReadView_A 注销
T7: Purge 线程再次运行
    - Purge 边界 = min(105) = 105
    - 仍然不能清理 TRX_ID=110 的 Undo（因为 >= 105）
T8: 事务 B 提交，ReadView_B 注销
T9: Purge 线程再次运行
    - Purge 边界 = 下一个要分配的 TRX_ID
    - 现在可以清理 TRX_ID=110 的 Undo
```

---

## 监控指标

### 关键指标
- **History List 长度**：已提交但未清理的事务数
- **Undo 空间占用**：当前 Undo Log 占用的磁盘空间
- **活跃 ReadView 数**：当前活跃的 ReadView 数量
- **Purge 边界**：当前可以清理的最小 TRX_ID
- **Purge 速率**：每秒清理的 Undo 记录数

### 告警条件
- History List 长度 > 阈值（例如 10000）
- Undo 空间占用 > 阈值（例如 1GB）
- 活跃 ReadView 数 > 阈值（例如 100）
- Purge 速率 < 阈值（例如 100 records/sec）
