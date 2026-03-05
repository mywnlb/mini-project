# 自适应 Purge 集成指南

## 概述

本文档说明如何将 `UndoSpaceMonitor` 和 `AdaptivePurgeScheduler` 集成到现有的 Purge 系统中。

---

## 架构设计

### 组件关系

```
┌─────────────────────────────────────────────────────────────┐
│                    PurgeThread                              │
│  (后台线程，定期执行 Purge 清理)                             │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ├─ 获取调度参数
                     │
        ┌────────────▼────────────┐
        │ AdaptivePurgeScheduler   │
        │ (动态调整 Purge 策略)    │
        └────────────┬────────────┘
                     │
                     ├─ 读取监控指标
                     │
        ┌────────────▼────────────┐
        │ UndoSpaceMonitor         │
        │ (收集 5 个关键指标)      │
        └────────────┬────────────┘
                     │
        ┌────────────┴────────────┐
        │                         │
   ┌────▼────┐          ┌────────▼──────┐
   │UndoLog   │          │PurgeCoordinator│
   │Manager   │          │(ReadView 管理) │
   └──────────┘          └─────────────────┘
```

### 数据流

```
1. UndoSpaceMonitor 定期收集指标：
   - 从 UndoLogManager 获取已使用空间
   - 从 UndoLogManager 获取 History List 长度
   - 从 PurgeCoordinator 获取活跃 ReadView 列表
   - 计算 Purge 滞后比率和最老 ReadView 年龄

2. AdaptivePurgeScheduler 根据指标做决策：
   - 评估当前状态（空闲/正常/警告/激进/危急）
   - 调整 Purge 间隔和批量大小
   - 决定是否限流新事务

3. PurgeThread 应用调度策略：
   - 使用 AdaptivePurgeScheduler 提供的参数
   - 按调整后的间隔和批量大小执行 Purge
   - 定期更新调度策略
```

---

## 集成步骤

### 步骤 1：初始化监控器和调度器

在数据库启动时，创建监控器和调度器实例：

```java
// 在 DatabaseEngine 或类似的启动类中

// 1. 创建 Undo 空间监控器
UndoSpaceMonitor undoSpaceMonitor = new UndoSpaceMonitor(
    undoLogManager,
    purgeCoordinator,
    128L * 1024 * 1024 * 1024  // 128GB 最大 Undo 空间
);

// 2. 创建自适应 Purge 调度器
AdaptivePurgeScheduler adaptiveScheduler = new AdaptivePurgeScheduler(
    undoSpaceMonitor
);

// 3. 创建 Purge 线程（使用调度器）
PurgeThread purgeThread = new PurgeThread(
    purgeCoordinator,
    undoLogManager,
    adaptiveScheduler.getPurgeIntervalMs(),
    adaptiveScheduler.getPurgeBatchSize()
);

// 4. 启动 Purge 线程
Thread purgeThreadHandle = new Thread(purgeThread);
purgeThreadHandle.start();
```

### 步骤 2：定期更新监控指标

创建一个监控线程，定期更新指标和调度策略：

```java
// 创建监控线程
Thread monitorThread = new Thread(() -> {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            // 每秒更新一次指标
            undoSpaceMonitor.updateMetrics();

            // 根据新指标更新调度策略
            adaptiveScheduler.updateSchedule();

            // 应用新的调度参数到 Purge 线程
            applyScheduleToThread(purgeThread, adaptiveScheduler);

            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
}, "MiniDB-Monitor-Thread");

monitorThread.setDaemon(true);
monitorThread.start();
```

### 步骤 3：修改 PurgeThread 以支持动态参数

修改 `PurgeThread` 以支持动态调整间隔和批量大小：

```java
public class PurgeThread extends Thread {
    // ... 现有代码 ...

    /**
     * 动态设置 Purge 间隔
     */
    public void setPurgeIntervalMs(long intervalMs) {
        this.purgeIntervalMs = intervalMs;
    }

    /**
     * 动态设置批量大小
     */
    public void setMaxRecordsPerRound(int maxRecords) {
        this.maxRecordsPerRound = maxRecords;
    }

    /**
     * 是否应该限流新事务
     */
    public boolean shouldThrottleNewTransactions() {
        return throttleNewTransactions.get();
    }

    /**
     * 设置限流标志
     */
    public void setThrottleNewTransactions(boolean throttle) {
        this.throttleNewTransactions.set(throttle);
    }
}
```

### 步骤 4：在事务管理器中应用限流

在 `TransactionManager` 中检查限流标志：

```java
public class TransactionManager {
    private PurgeThread purgeThread;

    /**
     * 开始新事务
     */
    public Transaction begin() {
        // 检查是否需要限流
        if (purgeThread.shouldThrottleNewTransactions()) {
            // 等待 Purge 线程清理空间
            waitForPurgeToFreeSpace();
        }

        // 创建新事务
        return createNewTransaction();
    }

    /**
     * 等待 Purge 线程清理空间
     */
    private void waitForPurgeToFreeSpace() {
        long startTime = System.currentTimeMillis();
        long maxWaitTime = 30000; // 最多等待 30 秒

        while (purgeThread.shouldThrottleNewTransactions()) {
            if (System.currentTimeMillis() - startTime > maxWaitTime) {
                throw new RuntimeException("Undo space critical, cannot create new transaction");
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for purge", e);
            }
        }
    }
}
```

---

## 监控和调试

### 查询当前状态

```java
// 获取监控指标
UndoSpaceMonitor.Metrics metrics = undoSpaceMonitor.getMetrics();
System.out.println("Undo space ratio: " + metrics.undoSpaceRatio * 100 + "%");
System.out.println("History list length: " + metrics.historyListLength);
System.out.println("Purge lag: " + metrics.purgeLag);
System.out.println("Oldest read view age: " + metrics.oldestReadViewAgeMs + "ms");
System.out.println("Critical: " + metrics.isCritical);
System.out.println("Warning: " + metrics.isWarning);

// 获取调度策略
AdaptivePurgeScheduler.SchedulePolicy policy = adaptiveScheduler.getCurrentPolicy();
System.out.println("Current policy: " + policy.name);
System.out.println("Purge interval: " + adaptiveScheduler.getPurgeIntervalMs() + "ms");
System.out.println("Batch size: " + adaptiveScheduler.getPurgeBatchSize());
System.out.println("Throttle: " + adaptiveScheduler.shouldThrottleNewTransactions());
```

### JMX 指标导出

创建 JMX MBean 以导出监控指标：

```java
public interface UndoSpaceMonitorMBean {
    double getUndoSpaceRatio();
    long getHistoryListLength();
    double getPurgeLag();
    long getOldestReadViewAgeMs();
    boolean isCritical();
    boolean isWarning();
    String getCurrentPolicy();
    long getPurgeIntervalMs();
    int getPurgeBatchSize();
}

public class UndoSpaceMonitorJMX implements UndoSpaceMonitorMBean {
    private final UndoSpaceMonitor monitor;
    private final AdaptivePurgeScheduler scheduler;

    public UndoSpaceMonitorJMX(UndoSpaceMonitor monitor, AdaptivePurgeScheduler scheduler) {
        this.monitor = monitor;
        this.scheduler = scheduler;
    }

    @Override
    public double getUndoSpaceRatio() {
        return monitor.getUndoSpaceRatio();
    }

    @Override
    public long getHistoryListLength() {
        return monitor.getHistoryListLength();
    }

    // ... 其他方法 ...
}

// 在启动时注册 MBean
MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
ObjectName name = new ObjectName("cn.zhangyis.minidb:type=UndoSpaceMonitor");
mbs.registerMBean(new UndoSpaceMonitorJMX(monitor, scheduler), name);
```

---

## 性能考虑

### 监控开销

- **UndoSpaceMonitor.updateMetrics()**：O(n)，其中 n = 活跃 ReadView 数量
  - 通常 n < 100，开销很小
  - 每秒调用一次，总开销 < 1% CPU

- **AdaptivePurgeScheduler.updateSchedule()**：O(1)
  - 简单的条件判断
  - 开销可忽略

### 调优建议

1. **监控间隔**：
   - 默认 1 秒，可根据需要调整
   - 更频繁的监控 → 更快的响应，但 CPU 开销更高
   - 更稀疏的监控 → 更低的 CPU 开销，但响应延迟更高

2. **Purge 参数**：
   - 默认间隔：1000ms
   - 最小间隔：100ms（激进模式）
   - 最大间隔：5000ms（空闲模式）
   - 调整这些值以平衡 CPU 和空间回收速度

3. **限流阈值**：
   - 默认空间危急阈值：80%
   - 默认警告阈值：60%
   - 可根据实际硬件和工作负载调整

---

## 故障排查

### 问题 1：Undo 空间持续增长

**症状**：`undo_space_ratio` 持续上升，最终达到 100%

**可能原因**：
1. 长事务阻止 Purge（`oldest_read_view_age` > 60s）
2. Purge 线程崩溃或被阻塞
3. History List 增长速度超过清理速度

**解决方案**：
```java
// 检查最老 ReadView 年龄
if (monitor.getOldestReadViewAgeMs() > 60000) {
    logger.warn("Long transaction detected, age = {}ms",
        monitor.getOldestReadViewAgeMs());
    // 主动杀死长事务或等待其完成
}

// 检查 Purge 线程状态
if (!purgeThread.isRunning()) {
    logger.error("Purge thread is not running!");
    // 重启 Purge 线程
}

// 检查 Purge 滞后
if (monitor.getPurgeLag() > 2.0) {
    logger.warn("Purge lag is high: {}", monitor.getPurgeLag());
    // 增加 Purge 批量大小或频率
}
```

### 问题 2：Purge 线程 CPU 占用过高

**症状**：Purge 线程 CPU 占用 > 50%

**可能原因**：
1. 调度策略过于激进
2. History List 过长，清理耗时
3. Undo 页面碎片化

**解决方案**：
```java
// 调整最大批量大小
adaptiveScheduler.updateSchedule();
int batchSize = adaptiveScheduler.getPurgeBatchSize();
if (batchSize > 50000) {
    // 减少批量大小
    logger.info("Reducing batch size to reduce CPU usage");
}

// 增加 Purge 间隔
long interval = adaptiveScheduler.getPurgeIntervalMs();
if (interval < 500) {
    logger.info("Increasing purge interval to reduce CPU usage");
}
```

### 问题 3：新事务被限流

**症状**：新事务创建被阻塞，`shouldThrottleNewTransactions()` 返回 true

**可能原因**：
1. Undo 空间占比 > 80%
2. 需要等待 Purge 线程清理空间

**解决方案**：
```java
// 检查空间占比
double spaceRatio = monitor.getUndoSpaceRatio();
logger.warn("Undo space ratio: {:.2f}%", spaceRatio * 100);

// 检查是否处于危急状态
if (monitor.isCritical()) {
    logger.error("System in critical state: {}", monitor.getMetrics());
    // 可能需要扩展 Undo 空间或优化查询
}

// 等待 Purge 线程清理
logger.info("Waiting for purge thread to free space...");
Thread.sleep(1000);
```

---

## 测试

### 单元测试

已提供的测试类：
- `UndoSpaceMonitorTest`：测试指标收集和计算
- `AdaptivePurgeSchedulerTest`：测试调度决策

运行测试：
```bash
mvn test -Dtest=UndoSpaceMonitorTest
mvn test -Dtest=AdaptivePurgeSchedulerTest
```

### 集成测试

建议添加以下集成测试：

```java
@Test
void testAdaptivePurgeUnderLoad() {
    // 1. 创建大量事务，产生大量 Undo 记录
    // 2. 监控 Undo 空间占比变化
    // 3. 验证 Purge 线程自动调整策略
    // 4. 验证空间最终被回收
}

@Test
void testLongTransactionHandling() {
    // 1. 创建长事务
    // 2. 验证 oldest_read_view_age 增加
    // 3. 验证调度策略转换到激进模式
    // 4. 验证 Purge 线程加速清理
}

@Test
void testThrottlingBehavior() {
    // 1. 填满 Undo 空间到 85%
    // 2. 验证 shouldThrottleNewTransactions() 返回 true
    // 3. 验证新事务被阻塞
    // 4. 验证 Purge 线程清理后限流解除
}
```

---

## 参考文档

- `undo.md`：完整的 Undo Log 设计文档
- `optimization-progress.md`：优化实现进度总结
- `CLAUDE.md`：Kernel-Safe Mode 要求
- `context.md`：项目上下文

