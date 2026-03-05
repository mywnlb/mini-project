# MySQL 8.0 风格无锁并发 Redo Log 设计方案

## 文档概述

本文档详细规划了 mini-db 项目中 Redo Log 子系统的无锁并发写入实现方案。

**设计目标**: 实现完整的 MySQL 8.0 无锁 redo log 架构，包括：
- 无锁空间预留 (atomic fetch_add)
- 并发写入 buffer
- Link_buf 连续性追踪 (recent_written + recent_closed)
- 专用后台线程 (writer/flusher/notifier/closer)
- 分片条件变量精准唤醒

**参考资料**:
- http://mysql.taobao.org/monthly/2019/02/05/
- https://catkang.github.io/2020/02/27/mysql-redo.html
- http://mysql.taobao.org/monthly/2019/03/03/

---

## 1. 设计决策摘要

| 决策项 | 选择 | 说明 |
|--------|------|------|
| Link_buf 实现 | VarHandle + long[] | 接近 C++ 语义，底层控制 |
| Link_buf 容量 | Buffer 容量 / 8 | 动态缩放，16MB buffer → 2MB Link_buf |
| Notifier 线程 | 完全分离 | LogWriteNotifier + LogFlushNotifier |
| recent_closed | 完整实现 | 支持 Checkpoint 正确计算 |
| 等待机制 | 分片条件变量 | 64 槽位，精准唤醒避免惊群 |
| Closer 线程 | 独立 LogCloser | 专用线程推进 recent_closed.tail |
| 架构模式 | 抽象接口 + 配置切换 | 支持 Lock-Based / Lock-Free 两种实现 |
| 线程模型 | 虚拟线程 + 生命周期服务 | 现代 Java 21 风格 |

---

## 2. 整体架构

### 2.1 组件总览

```
RedoLogManager (改造)
├── RedoLogBuffer (接口)
│   ├── LockBasedRedoLogBuffer   (现有实现)
│   └── LockFreeRedoLogBuffer    (新增)
│       ├── currentSn: AtomicLong
│       ├── recentWritten: LinkBuf
│       └── recentClosed: LinkBuf
│
├── 后台服务 (虚拟线程)
│   ├── LogCloser         [新增] 推进 recentClosed.tail
│   ├── LogWriter         [改造] buffer → file
│   ├── LogFlusher        [改造] fsync
│   ├── LogWriteNotifier  [新增] 唤醒等待 write 的线程
│   └── LogFlushNotifier  [新增] 唤醒等待 flush 的线程
│
├── 等待机制
│   └── WaitSlots         [新增] 分片条件变量
│
└── 生命周期管理
    └── DatabaseLifecycleManager [新增]
```

### 2.2 关键 SN 位置

```
┌─────────────────────────────────────────────────────────────────┐
│  flushedSn    writeSn    bufReadyForWriteSn   currentSn         │
│      ↓           ↓              ↓                 ↓             │
│  [已fsync]   [已写文件]    [buffer连续]      [已分配]           │
│                              ↑                                   │
│                    recentWritten.tail                           │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 数据流向

```
MTR ──write──► Buffer ──LogWriter──► File ──LogFlusher──► Disk
 │                │                    │                    │
 │                ▼                    ▼                    ▼
 │         recentWritten          writeSn              flushedSn
 │                │                    │                    │
 │                ▼                    ▼                    ▼
 │          LogCloser           LogWriteNotifier    LogFlushNotifier
 │               │                    │                    │
 ▼               ▼                    ▼                    ▼
recentClosed   推进tail            唤醒等待             唤醒等待
```

---

## 3. 核心数据结构

### 3.1 RedoLogBuffer 接口

```java
public interface RedoLogBuffer {
    // 核心操作
    long reserveSpace(int size) throws InterruptedException;
    void writeRecord(long startSn, byte[] data);
    void markWriteComplete(long startSn, int length);

    // 状态查询
    long getCurrentSn();
    long getBufReadyForWriteSn();
    long getWriteSn();
    long getFlushedSn();

    // 脏页注册 (recent_closed 相关)
    void markPageDirtyComplete(long commitSn);
    long getDirtyPageLwm();

    // 等待
    boolean waitForFlush(long targetSn, long timeoutNanos) throws InterruptedException;
}
```

### 3.2 LinkBuf 实现

```java
public class LinkBuf {
    private final int capacity;
    private final int mask;
    private final long granularity;
    private final long[] slots;
    private static final VarHandle SLOTS_HANDLE;
    private final AtomicLong tail = new AtomicLong(0);

    static {
        SLOTS_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    }

    /**
     * 标记 [startSn, startSn+length) 区间完成
     */
    public void addLink(long startSn, int length) {
        int slot = (int) ((startSn / granularity) & mask);
        SLOTS_HANDLE.setRelease(slots, slot, (long) length);
    }

    /**
     * 尝试推进 tail
     */
    public long advanceTail() {
        long currentTail = tail.get();

        while (true) {
            int slot = (int) ((currentTail / granularity) & mask);
            long length = (long) SLOTS_HANDLE.getAcquire(slots, slot);

            if (length == 0) {
                break;  // 遇到空洞
            }

            SLOTS_HANDLE.setRelease(slots, slot, 0L);
            currentTail += length;
        }

        tail.set(currentTail);
        return currentTail;
    }

    public long getTail() {
        return tail.get();
    }
}
```

### 3.3 WaitSlots 分片等待

```java
public class WaitSlots {
    private final int slotCount;
    private final int slotMask;
    private final long granularity;
    private final ReentrantLock[] locks;
    private final Condition[] conditions;
    private final AtomicInteger[] waiterCounts;

    public boolean waitFor(long targetSn, LongSupplier currentValueSupplier,
                          long timeoutNanos) throws InterruptedException {
        if (currentValueSupplier.getAsLong() >= targetSn) {
            return true;
        }

        int slot = slotIndex(targetSn);
        waiterCounts[slot].incrementAndGet();
        locks[slot].lock();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (currentValueSupplier.getAsLong() < targetSn) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                conditions[slot].awaitNanos(remaining);
            }
            return true;
        } finally {
            locks[slot].unlock();
            waiterCounts[slot].decrementAndGet();
        }
    }

    public void wakeupRange(long fromSn, long toSn) {
        // 精准唤醒受影响的槽位
    }
}
```

---

## 4. 生命周期管理

### 4.1 生命周期接口

```java
public interface Lifecycle {
    String getName();
    int getOrder();
    void initialize() throws Exception;
    void start() throws Exception;
    void stop() throws Exception;
    void destroy() throws Exception;
    LifecycleState getState();
}

public enum LifecycleState {
    NEW, INITIALIZING, INITIALIZED, STARTING,
    RUNNING, STOPPING, STOPPED, DESTROYING, DESTROYED, FAILED
}
```

### 4.2 生命周期事件与监听器

```java
public record LifecycleEvent(
    Lifecycle source,
    LifecycleState oldState,
    LifecycleState newState,
    Throwable error
) {}

public interface LifecycleListener {
    default void onInitialized(LifecycleEvent event) {}
    default void onStarted(LifecycleEvent event) {}
    default void onStopped(LifecycleEvent event) {}
    default void onDestroyed(LifecycleEvent event) {}
    default void onFailed(LifecycleEvent event) {}
}
```

### 4.3 后台服务抽象 (虚拟线程)

```java
public abstract class BackgroundService implements Lifecycle {
    private final String name;
    private final int order;
    private volatile LifecycleState state = LifecycleState.NEW;
    private Thread virtualThread;

    @Override
    public void start() throws Exception {
        state = LifecycleState.STARTING;
        virtualThread = Thread.ofVirtual()
            .name(name)
            .uncaughtExceptionHandler((t, e) -> {
                state = LifecycleState.FAILED;
            })
            .start(this::runLoop);
        state = LifecycleState.RUNNING;
    }

    private void runLoop() {
        while (state == LifecycleState.RUNNING) {
            try {
                doWork();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                onError(e);
            }
        }
    }

    protected abstract void doWork() throws Exception;
}
```

### 4.4 启动顺序

```java
public final class RedoLogOrders {
    public static final int LOG_CLOSER = 100;
    public static final int LOG_WRITER = 200;
    public static final int LOG_FLUSHER = 300;
    public static final int LOG_WRITE_NOTIFIER = 400;
    public static final int LOG_FLUSH_NOTIFIER = 410;
}
```

---

## 5. LockFreeRedoLogBuffer 实现

### 5.1 核心字段

```java
public class LockFreeRedoLogBuffer implements RedoLogBuffer {
    private final byte[] buffer;
    private final int capacity;

    private final AtomicLong currentSn = new AtomicLong(0);
    private volatile long writeSn = 0;
    private volatile long flushedSn = 0;

    private final LinkBuf recentWritten;
    private final LinkBuf recentClosed;

    private final WaitSlots writeWaitSlots;
    private final WaitSlots flushWaitSlots;
}
```

### 5.2 无锁空间预留

```java
@Override
public long reserveSpace(int size) throws InterruptedException {
    long startSn = currentSn.getAndAdd(size);
    long endSn = startSn + size;

    while (endSn - flushedSn > capacity) {
        flushWaitSlots.waitFor(flushedSn + capacity - (endSn - flushedSn));
    }

    return startSn;
}
```

### 5.3 并发写入

```java
@Override
public void writeRecord(long startSn, byte[] data) {
    int offset = (int) (startSn % capacity);
    // 直接写入，无锁
    System.arraycopy(data, 0, buffer, offset, data.length);
}

@Override
public void markWriteComplete(long startSn, int length) {
    recentWritten.addLink(startSn, length);
}
```

---

## 6. MTR 提交流程

### 6.1 新旧对比

```
旧流程 (Lock-Based):
1. 获取 commitLock         ← 串行瓶颈
2. reserveSpace() (持锁)
3. writeRecord() (持锁)
4. advanceWriteReadySn()
5. 释放 commitLock
6. waitForFlush()

新流程 (Lock-Free):
1. reserveSpace() (CAS)    ← 并发
2. writeRecord() (无锁)    ← 并发
3. markWriteComplete()     ← 并发
4. waitForFlush()          ← 并发等待
5. markPageDirtyComplete() ← 并发
```

### 6.2 MiniTransaction 改造

```java
public void commit() throws MiniDbException {
    List<RedoRecord> redoGroup = buildRedoGroup();

    if (!redoGroup.isEmpty()) {
        byte[] payload = RedoRecordSerializer.serialize(redoGroup);

        // 无锁预留 + 并发写入
        startSn = redoLogBuffer.reserveSpace(payload.length);
        redoLogBuffer.writeRecord(startSn, payload);
        redoLogBuffer.markWriteComplete(startSn, payload.length);

        // 等待持久化
        long commitSn = startSn + payload.length;
        redoLogBuffer.waitForFlush(commitSn, MAX_WAIT_NANOS);

        updatePageLsn(commitSn);
    }

    releasePages();

    if (startSn >= 0) {
        redoLogBuffer.markPageDirtyComplete(startSn + totalLength);
    }

    state = State.COMMITTED;
}
```

---

## 7. 配置与工厂

### 7.1 配置扩展

```java
public class RedoLogConfig {
    public enum BufferMode {
        LOCK_BASED,
        LOCK_FREE
    }

    private final BufferMode bufferMode;
    private final int linkBufCapacityRatio;  // 默认 8
    private final int waitSlotCount;          // 默认 64
    private final long waitSlotGranularity;   // 默认 4096

    public static Builder builder() {
        return new Builder();
    }
}
```

### 7.2 工厂模式

```java
public class RedoLogBufferFactory {
    public static RedoLogBuffer create(RedoLogConfig config) {
        return switch (config.getBufferMode()) {
            case LOCK_BASED -> new LockBasedRedoLogBuffer(...);
            case LOCK_FREE -> new LockFreeRedoLogBuffer(...);
        };
    }
}
```

### 7.3 使用示例

```java
// 有锁模式 (默认)
RedoLogConfig config = RedoLogConfig.builder()
    .lockBased()
    .build();

// 无锁模式
RedoLogConfig config = RedoLogConfig.builder()
    .lockFree()
    .linkBufCapacityRatio(8)
    .waitSlotCount(64)
    .build();

RedoLogManager manager = new RedoLogManager(config);
manager.start();
```

---

## 8. 文件结构

```
cn.zhangyis.minidb.storage.redo/
├── RedoLogConfig.java                    [改造]
├── RedoLogManager.java                   [改造]
│
├── buffer/
│   ├── RedoLogBuffer.java                [新增] 接口
│   ├── LockBasedRedoLogBuffer.java       [重命名]
│   ├── LockFreeRedoLogBuffer.java        [新增]
│   ├── LinkBuf.java                      [新增]
│   └── RedoLogBufferFactory.java         [新增]
│
├── wait/
│   ├── WaitSlots.java                    [新增]
│   └── WaitSlotMetrics.java              [新增]
│
├── writer/
│   ├── LogWriter.java                    [改造]
│   ├── LogFlusher.java                   [改造]
│   ├── LogWriteNotifier.java             [新增]
│   ├── LogFlushNotifier.java             [新增]
│   ├── LogCloser.java                    [新增]
│   └── RedoLogServiceFactory.java        [新增]
│
└── lifecycle/
    ├── Lifecycle.java                    [新增]
    ├── LifecycleState.java               [新增]
    ├── LifecycleEvent.java               [新增]
    ├── LifecycleListener.java            [新增]
    ├── BackgroundService.java            [新增]
    └── DatabaseLifecycleManager.java     [新增]
```

---

## 9. 实现阶段

### 阶段 1: 基础设施

1.1 生命周期框架
- Lifecycle, LifecycleState, LifecycleEvent
- LifecycleListener, BackgroundService
- DatabaseLifecycleManager

1.2 LinkBuf 数据结构
- LinkBuf (VarHandle + long[])
- 单元测试

1.3 WaitSlots 等待机制
- WaitSlots 分片条件变量
- 单元测试

### 阶段 2: 接口抽象

2.1 RedoLogBuffer 接口
- 提取接口
- 现有实现重命名
- 验证测试通过

2.2 配置扩展
- RedoLogConfig 新增 BufferMode
- RedoLogBufferFactory

### 阶段 3: 无锁实现

3.1 LockFreeRedoLogBuffer
- 无锁空间预留
- 并发写入
- recentWritten / recentClosed

3.2 后台服务
- LogWriter / LogFlusher 改造
- LogWriteNotifier / LogFlushNotifier / LogCloser
- RedoLogServiceFactory

### 阶段 4: 集成与测试

4.1 MiniTransaction 改造
4.2 Checkpoint 集成
4.3 集成测试 + 性能对比

---

## 10. 测试要点

```java
// 并发正确性
@Test void testConcurrentReserve()
@Test void testConcurrentWriteWithHoles()
@Test void testTailAdvancement()
@Test void testWaitSlotsWakeup()
@Test void testRecentClosedCheckpoint()

// 性能对比
@Benchmark void lockBasedCommit()
@Benchmark void lockFreeCommit()
```

---

## 附录: 术语对照

| 术语 | 定义 |
|------|------|
| SN | Sequence Number，纯 payload 字节偏移 |
| LSN | Log Sequence Number，包含 block 开销的物理偏移 |
| Link_buf | 环形数组，追踪并发写入的连续边界 |
| recent_written | 追踪 buffer 写入连续性的 Link_buf |
| recent_closed | 追踪脏页注册连续性的 Link_buf |
| tail | Link_buf 中连续完成的边界 |
| WaitSlots | 分片条件变量，避免惊群效应 |
