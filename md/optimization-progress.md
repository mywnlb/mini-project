# Undo Log 优化实现进度

## 概述

本文档总结 undo.md 中四个优化方向的实现进度。

---

## 优先级 5：Undo 预分配 ✅ 已完成

### 设计目标
- 将 Undo 页面分配从 O(log n) 降低到 O(1)
- 减少 Extent 查询和元数据更新开销
- 支持页面复用，提升并发性能

### 核心组件

#### 1. UndoPagePool.java (~356 行)
**职责**：为每个 Rollback Segment 维护两层页面池

**关键特性**：
- `freshPages`：从未写过的页面，可直接分配
- `reusablePages`：Purge 后变空的页面，可重用
- 无锁队列（ConcurrentLinkedQueue）保证并发安全
- 原子计数器追踪页面数量和统计信息

**分配流程**：
```
allocateUndoPage() {
    if (!freshPages.isEmpty()) return freshPages.poll();
    if (!reusablePages.isEmpty()) return reusablePages.poll();
    return null;  // 需要从 Extent 分配新页
}
```

**关键约束**：
- ✅ 不做真实释放，只做复用
- ✅ freshPages 和 reusablePages 统计分开
- ✅ 无锁队列保证并发安全
- ✅ 异步补充不阻塞分配

#### 2. UndoPagePoolRefiller.java (~347 行)
**职责**：后台线程定期补充 UndoPagePool 中的 freshPages

**关键特性**：
- 定期扫描所有 Rollback Segment 的池
- 当 freshPages < MIN_THRESHOLD 时异步分配新页
- 支持优雅关闭（graceful shutdown）
- 异常不导致线程退出

**工作流程**：
```
1. 定期扫描所有 Rollback Segment 的池
2. 对于每个需要补充的池：
   a. 计算需要补充的页面数
   b. 从 Segment 分配新页（使用 MTR）
   c. 将新页加入 freshPages
3. 休眠指定时间后重复
```

**关键参数**：
- `DEFAULT_REFILL_INTERVAL_MS = 1000`：补充间隔
- `MIN_THRESHOLD = 5`：触发补充的阈值
- `MAX_THRESHOLD = 20`：目标池大小
- `MAX_CONSECUTIVE_FAILURES = 5`：最大连续失败次数

### 不变量维护
- **U4**：Undo 页面分配 - 预分配仍遵循 32 页 + Extent 规则
- **U7**：Undo 空间回收 - 池中的页面可被复用

### 性能收益
- 分配延迟：O(1) 队列操作
- 减少 Extent 查询频率
- 支持页面复用，减少碎片

---

## 优先级 4：自适应 Purge ⏳ 部分完成

### 设计目标
根据多指标动态调整 purge 频率和强度，防止长事务拖死 purge

### 已实现的基础组件

#### 1. PurgeCoordinator.java (~228 行)
**职责**：跟踪活跃 ReadView，计算可安全清理的 TRX_ID 边界

**关键特性**：
- 维护活跃 ReadView 列表（CopyOnWriteArrayList）
- 缓存 Purge 边界，避免频繁计算
- 支持缓存失效标记

**核心方法**：
```java
// 获取 Purge 边界（所有活跃 ReadView 的 up_limit_id 的最小值）
TransactionId getPurgeLimit()

// 检查 TRX_ID 是否可以被清理
boolean canPurge(TransactionId trxId)
```

#### 2. PurgeThread.java (~404 行)
**职责**：后台线程定期执行 Purge 清理

**关键特性**：
- 定期从 PurgeCoordinator 获取 Purge 边界
- 遍历 History List 获取可清理的 UPDATE Undo Segment
- 按 Rollback Segment 分组批量处理
- 支持暂停/恢复
- 统计清理的记录数和轮数

**工作流程**：
```
1. 获取当前 Purge 边界
2. 从 History List 获取可清理的条目
3. 按 Rollback Segment 分组
4. 批量清理每个 Rollback Segment
5. 从 History List 移除已清理的条目
```

**关键参数**：
- `DEFAULT_PURGE_INTERVAL_MS = 1000`：Purge 间隔
- `DEFAULT_MAX_RECORDS_PER_ROUND = 10000`：每轮最大记录数

### 待实现的自适应组件

#### 1. UndoSpaceMonitor（需要实现）
**职责**：监控 5 个关键指标

**关键指标**：
```
1. undo_space_ratio：已使用 Undo 空间占比 (0-100%)
2. history_list_length：待 purge 版本数
3. purge_lag：purge 处理速度跟不上产生速度的滞后
4. oldest_read_view_age：最老一致性读年龄（秒）
5. (可选) trx_throttle：空间危急时限制新事务
```

**实现位置**：
```
cn.zhangyis.minidb.storage.transaction.purge.UndoSpaceMonitor
```

#### 2. AdaptivePurgeScheduler（需要实现）
**职责**：根据多指标动态调整 purge 策略

**决策规则**：
```java
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
```
cn.zhangyis.minidb.storage.transaction.purge.AdaptivePurgeScheduler
```

### 不变量维护
- **P1**：不能清理任何活跃 ReadView 可能需要的 Undo 记录
- **P2**：purge_limit 是所有活跃 ReadView 的 up_limit_id 的最小值
- **P3**：ReadView 关闭时必须从跟踪列表中移除
- **P4**：Purge 操作本身是幂等的
- **P5**：Purge 失败不影响系统正确性，只影响空间回收

---

## 优先级 2：增量 Undo ⏳ 框架设计

### 设计目标
只存储修改的列，减少空间占用 30-70%

### 核心思路
UPDATE Undo 使用 TLV 格式，记录 formatVersion/schemaVersion，支持新旧格式混读

### 关键设计

#### 1. Undo 记录格式升级
```java
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

#### 2. 版本链重建算法
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

### 实现位置
- `UndoRecordHeader`：加 formatVersion/schemaVersion
- `UpdateUndoRecord`：改为 TLV 格式 List<UndoField>
- `UndoPage`：序列化/反序列化按 version 分支
- `TransactionalDml`：写 undo 时只记录变更列
- `VersionReconstructor`：从新到旧遍历，按需补齐列，补齐即停

### 关键约束
- ✅ formatVersion/schemaVersion 必须记录
- ✅ 新旧格式混读支持（严格按 version 分支）
- ✅ schema 变更时需要转换逻辑
- ✅ 补齐即停（不必遍历整条链）

### 不变量维护
- **U1**：Undo 记录不可修改 - 仍然不可修改
- **U2**：版本链完整性 - 需要确保列继承逻辑正确
- **U8**：Undo 读取安全 - 仍然无需锁

---

## 优先级 1：Undo 压缩（剪枝跳跃指针）🔴 最高风险

### 设计目标
减少版本链深度，加速版本链遍历

### 核心思路
追加写新 Undo 记录，跳过中间版本，不改写/删除旧 Undo

### 关键设计

#### 1. 合并条件
- 同一记录（RecordId/PK）
- 连续 UPDATE（遇到 DELETE/INSERT 边界停止）
- 链上所有 undo.trx_id < purge_view.low_limit_id
- 当前 record.roll_ptr 未变化（ABA 校验）
- 合并后记录大小不超页可用空间

#### 2. 时序示例
```
原始链：record.roll_ptr → U3 → U2 → U1 → NULL
                         ↑    ↑    ↑
                       (都是 UPDATE，可合并)

合并后：record.roll_ptr → U_merged → NULL
        (U_merged 直接指向 U1 之前的版本)

旧 U3/U2/U1 进入正常 purge 回收流程
```

#### 3. 执行流程
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

### 实现位置
- `UndoPruner`：在 purge 扫 history list 时识别"同一记录连续 UPDATE 段"
- `UndoLogManager.appendMergedUndo()`：追加写合并后的新 undo（含 redo）
- `ClusteredIndexAccessor.updateRollPtr()`：更新记录 roll_ptr（redo 记录）

### 关键约束
- ❌ 不能改写/删除旧 undo 记录（违反 U1）
- ✅ 只能追加写新 undo 记录
- ✅ 必须 X-latch 数据页 + redo 记录
- ✅ 必须 ABA 校验防止并发冲突
- ✅ 涉及数据页链指针更新，风险最高

### 不变量维护
- **U1**：Undo 记录不可修改 - 追加写保证
- **U2**：版本链完整性 - 合并后版本链仍完整
- **U6**：Undo 记录顺序 - 合并后仍保持顺序
- **U7**：Undo 空间回收 - 合并释放更多空间

### 风险等级
🔴 **最高**
- 涉及数据页 + 链指针更新
- 必须有完整的 latch + redo + 回归测试
- 并发冲突场景复杂

---

## 实现建议

### 短期（优先级 4）
1. 实现 `UndoSpaceMonitor`：收集 5 个关键指标
2. 实现 `AdaptivePurgeScheduler`：多指标决策
3. 集成到 `PurgeThread`：动态应用策略
4. 添加 JMX 指标导出

### 中期（优先级 2）
1. 升级 `UndoRecordHeader` 和 `UpdateUndoRecord` 格式
2. 实现 `VersionReconstructor`：按需补齐列
3. 修改 `TransactionalDml`：只记录变更列
4. 完整的单元测试和集成测试

### 长期（优先级 1）
1. 实现 `UndoPruner`：识别可合并的链段
2. 实现 `appendMergedUndo()`：追加写合并后的 undo
3. 完整的 latch + redo + 回归测试
4. 性能基准测试

---

## 测试覆盖

### 优先级 5（已完成）
- ✅ UndoPagePool 分配/回收
- ✅ UndoPagePoolRefiller 补充逻辑
- ✅ 并发分配和回收

### 优先级 4（待实现）
- [ ] UndoSpaceMonitor 指标收集
- [ ] AdaptivePurgeScheduler 决策规则
- [ ] 长事务场景下的自适应行为
- [ ] 空间危急时的限流

### 优先级 2（待实现）
- [ ] 单列/多列/宽表更新
- [ ] 多次更新链重建正确性
- [ ] 新旧格式混读
- [ ] Schema 变更场景

### 优先级 1（待实现）
- [ ] 链段合并正确性
- [ ] ABA 校验
- [ ] 并发冲突场景
- [ ] 性能基准测试

---

## 参考文档

- `undo.md`：完整的 Undo Log 设计文档
- `CLAUDE.md`：Kernel-Safe Mode 要求
- `context.md`：项目上下文
- `instructions.md`：实现指导

