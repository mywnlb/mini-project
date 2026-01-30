# B+Tree 并发控制

## 1. 概述

MiniDB B+Tree 实现了多种并发控制机制，支持高并发的读写操作。

### 1.1 并发控制层次

```
┌─────────────────────────────────────────────────────────────┐
│                     应用层并发控制                           │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │ MiniTransaction │  │   事务隔离      │                  │
│  └─────────────────┘  └─────────────────┘                  │
├─────────────────────────────────────────────────────────────┤
│                     B+Tree 并发控制                          │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │ ConcurrentBTree │  │ Latch Coupling  │                  │
│  └─────────────────┘  └─────────────────┘                  │
├─────────────────────────────────────────────────────────────┤
│                     页面级并发控制                           │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │  BufferFrame    │  │   读写锁        │                  │
│  │   Latch         │  │  (ReentrantRWL) │                  │
│  └─────────────────┘  └─────────────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

## 2. Latch 机制

### 2.1 Latch 模式

```java
public enum LatchMode {
    /** 共享锁（读锁） */
    SHARED,

    /** 排他锁（写锁） */
    EXCLUSIVE,

    /** 无锁 */
    NONE
}
```

### 2.2 LatchHolder

```java
public class LatchHolder implements AutoCloseable {
    private final BufferFrame frame;
    private final LatchMode mode;
    private boolean released;

    public static LatchHolder readLatch(BufferFrame frame) {
        frame.readLock();
        return new LatchHolder(frame, LatchMode.SHARED);
    }

    public static LatchHolder writeLatch(BufferFrame frame) {
        frame.writeLock();
        return new LatchHolder(frame, LatchMode.EXCLUSIVE);
    }

    public void upgrade() {
        if (mode == LatchMode.SHARED) {
            frame.readUnlock();
            frame.writeLock();
            // 注意：升级不是原子的，需要重新验证
        }
    }

    public void downgrade() {
        if (mode == LatchMode.EXCLUSIVE) {
            frame.writeUnlock();
            frame.readLock();
        }
    }

    @Override
    public void close() {
        release();
    }

    public void release() {
        if (!released) {
            if (mode == LatchMode.SHARED) {
                frame.readUnlock();
            } else if (mode == LatchMode.EXCLUSIVE) {
                frame.writeUnlock();
            }
            released = true;
        }
    }
}
```

## 3. Latch Coupling（蟹行协议）

### 3.1 基本原理

Latch Coupling 是一种经典的 B+Tree 并发控制协议：

1. 获取子节点的 latch
2. 释放父节点的 latch
3. 重复直到到达目标节点

```
搜索过程中的 Latch Coupling:

时刻 T1:        时刻 T2:        时刻 T3:
┌─────┐         ┌─────┐         ┌─────┐
│Root │ S-Latch │Root │         │Root │
└──┬──┘         └──┬──┘         └──┬──┘
   │               │               │
   ↓               ↓               ↓
┌─────┐         ┌─────┐         ┌─────┐
│ N1  │         │ N1  │ S-Latch │ N1  │
└──┬──┘         └──┬──┘         └──┬──┘
   │               │               │
   ↓               ↓               ↓
┌─────┐         ┌─────┐         ┌─────┐
│Leaf │         │Leaf │         │Leaf │ S-Latch
└─────┘         └─────┘         └─────┘
```

### 3.2 搜索操作

```java
public class ConcurrentBTreeOps {
    /**
     * 并发安全的搜索
     */
    public static BTreeSearchResult concurrentSearch(
            BTree btree, byte[] searchKey, BufferPool bufferPool) {

        PageId currentPageId = new PageId(
            btree.getMetadata().getSpaceId(),
            btree.getMetadata().getRootPageNo()
        );

        BufferFrame currentFrame = bufferPool.getPage(currentPageId,
            BufferPool.FetchMode.READ_EXISTING);
        currentFrame.readLock();  // 获取根节点读锁

        try {
            while (true) {
                ByteBuffer buffer = currentFrame.buffer();
                int level = IndexPage.readLevel(buffer);

                if (level == 0) {
                    // 叶子节点，执行搜索
                    return searchInLeaf(buffer, searchKey);
                }

                // 内部节点，找到子节点
                int childPageNo = findChildPage(buffer, searchKey);
                PageId childPageId = new PageId(
                    currentPageId.getSpaceId(), childPageNo);

                // Latch Coupling: 先获取子节点锁
                BufferFrame childFrame = bufferPool.getPage(childPageId,
                    BufferPool.FetchMode.READ_EXISTING);
                childFrame.readLock();

                // 再释放父节点锁
                currentFrame.readUnlock();

                // 移动到子节点
                currentFrame = childFrame;
                currentPageId = childPageId;
            }
        } finally {
            currentFrame.readUnlock();
        }
    }
}
```

### 3.3 插入操作（乐观锁）

```java
/**
 * 乐观插入：假设不需要分裂
 */
public static boolean optimisticInsert(
        BTree btree, byte[] record, byte[] searchKey,
        BufferPool bufferPool, MiniTransaction mtr) {

    // 第一阶段：乐观搜索（只用读锁）
    PageId leafPageId = optimisticSearchLeaf(btree, searchKey, bufferPool);

    // 第二阶段：尝试插入
    BufferFrame leafFrame = bufferPool.getPage(leafPageId,
        BufferPool.FetchMode.READ_EXISTING);
    leafFrame.writeLock();

    try {
        ByteBuffer buffer = leafFrame.buffer();

        // 检查是否有足够空间
        if (hasSpaceForRecord(buffer, record)) {
            // 乐观成功：直接插入
            insertRecord(buffer, record, searchKey);
            leafFrame.setDirty(true);
            return true;
        }

        // 乐观失败：需要分裂，回退到悲观模式
        leafFrame.writeUnlock();
        return pessimisticInsert(btree, record, searchKey, bufferPool, mtr);

    } finally {
        if (leafFrame.isWriteLocked()) {
            leafFrame.writeUnlock();
        }
    }
}
```

### 3.4 插入操作（悲观锁）

```java
/**
 * 悲观插入：持有从根到叶子的写锁
 */
public static boolean pessimisticInsert(
        BTree btree, byte[] record, byte[] searchKey,
        BufferPool bufferPool, MiniTransaction mtr) {

    List<LatchHolder> heldLatches = new ArrayList<>();

    try {
        // 从根节点开始，获取写锁路径
        PageId currentPageId = new PageId(
            btree.getMetadata().getSpaceId(),
            btree.getMetadata().getRootPageNo()
        );

        while (true) {
            BufferFrame frame = bufferPool.getPage(currentPageId,
                BufferPool.FetchMode.READ_EXISTING);
            LatchHolder latch = LatchHolder.writeLatch(frame);
            heldLatches.add(latch);

            ByteBuffer buffer = frame.buffer();
            int level = IndexPage.readLevel(buffer);

            // 检查是否安全（有足够空间，不会分裂）
            if (isSafe(buffer, record)) {
                // 释放祖先节点的锁
                releaseAncestorLatches(heldLatches);
            }

            if (level == 0) {
                // 到达叶子节点
                return insertWithSplit(frame, record, searchKey,
                    heldLatches, bufferPool, mtr);
            }

            // 继续向下
            int childPageNo = findChildPage(buffer, searchKey);
            currentPageId = new PageId(currentPageId.getSpaceId(), childPageNo);
        }

    } finally {
        // 释放所有持有的锁
        for (LatchHolder latch : heldLatches) {
            latch.release();
        }
    }
}
```

## 4. ConcurrentBTree

### 4.1 并发 B+Tree 封装

```java
public class ConcurrentBTree {
    private final BTree btree;
    private final BufferPool bufferPool;
    private final RecordComparator comparator;

    // 统计信息
    private final AtomicLong optimisticSuccessCount = new AtomicLong();
    private final AtomicLong pessimisticFallbackCount = new AtomicLong();

    /**
     * 并发安全的插入
     */
    public boolean insert(byte[] record, byte[] searchKey, MiniTransaction mtr) {
        // 首先尝试乐观插入
        if (ConcurrentBTreeOps.optimisticInsert(
                btree, record, searchKey, bufferPool, mtr)) {
            optimisticSuccessCount.incrementAndGet();
            return true;
        }

        // 乐观失败，使用悲观模式
        pessimisticFallbackCount.incrementAndGet();
        return ConcurrentBTreeOps.pessimisticInsert(
            btree, record, searchKey, bufferPool, mtr);
    }

    /**
     * 并发安全的搜索
     */
    public BTreeSearchResult search(byte[] searchKey) {
        return ConcurrentBTreeOps.concurrentSearch(btree, searchKey, bufferPool);
    }

    /**
     * 并发安全的删除
     */
    public boolean delete(byte[] searchKey, int recordSize, MiniTransaction mtr) {
        return ConcurrentBTreeOps.concurrentDelete(
            btree, searchKey, recordSize, bufferPool, mtr);
    }

    /**
     * 获取乐观成功率
     */
    public double getOptimisticSuccessRate() {
        long total = optimisticSuccessCount.get() + pessimisticFallbackCount.get();
        if (total == 0) return 1.0;
        return (double) optimisticSuccessCount.get() / total;
    }
}
```

### 4.2 使用示例

```java
// 创建并发 B+Tree
ConcurrentBTree concurrentBTree = new ConcurrentBTree(btree, bufferPool, comparator);

// 多线程并发操作
ExecutorService executor = Executors.newFixedThreadPool(10);

for (int i = 0; i < 1000; i++) {
    final int key = i;
    executor.submit(() -> {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte)key}, 2);
            concurrentBTree.insert(record, IntKeyComparator.intToBytes(key), mtr);
            mtr.commit();
        }
    });
}

executor.shutdown();
executor.awaitTermination(1, TimeUnit.MINUTES);

// 查看统计
System.out.println("乐观成功率: " + concurrentBTree.getOptimisticSuccessRate());
```

## 5. 死锁预防

### 5.1 锁顺序

为避免死锁，遵循以下锁获取顺序：

1. **从上到下**: 先获取父节点锁，再获取子节点锁
2. **从左到右**: 同层节点按页面号顺序获取
3. **先读后写**: 需要升级时，先释放读锁再获取写锁

### 5.2 锁超时

```java
public class LatchHolder {
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    public static LatchHolder tryWriteLatch(BufferFrame frame, long timeoutMs) {
        boolean acquired = frame.tryWriteLock(timeoutMs, TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new LatchTimeoutException("Failed to acquire write latch");
        }
        return new LatchHolder(frame, LatchMode.EXCLUSIVE);
    }
}
```

### 5.3 死锁检测

```java
public class DeadlockDetector {
    private final Map<Long, Set<PageId>> threadHeldLatches = new ConcurrentHashMap<>();
    private final Map<Long, PageId> threadWaitingFor = new ConcurrentHashMap<>();

    public void recordLatchAcquired(long threadId, PageId pageId) {
        threadHeldLatches.computeIfAbsent(threadId, k -> new HashSet<>()).add(pageId);
        threadWaitingFor.remove(threadId);
    }

    public void recordLatchWaiting(long threadId, PageId pageId) {
        threadWaitingFor.put(threadId, pageId);
    }

    public boolean detectDeadlock() {
        // 构建等待图，检测环
        // ...
    }
}
```

## 6. 范围扫描的并发控制

### 6.1 快照隔离

```java
public class ConcurrentRangeScanner implements AutoCloseable {
    private final BTree btree;
    private final BufferPool bufferPool;
    private final long snapshotLsn;  // 快照 LSN

    private PageId currentPageId;
    private int currentSlot;
    private LatchHolder currentLatch;

    public ConcurrentRangeScanner(BTree btree, BufferPool bufferPool) {
        this.btree = btree;
        this.bufferPool = bufferPool;
        this.snapshotLsn = bufferPool.getCurrentLsn();
    }

    public ScanEntry next() {
        // 移动到下一条记录
        currentSlot++;

        BufferFrame frame = bufferPool.getPage(currentPageId,
            BufferPool.FetchMode.READ_EXISTING);

        // 获取读锁
        LatchHolder newLatch = LatchHolder.readLatch(frame);

        // 释放旧锁（Latch Coupling）
        if (currentLatch != null) {
            currentLatch.release();
        }
        currentLatch = newLatch;

        ByteBuffer buffer = frame.buffer();
        int recordCount = IndexPage.readRecordCount(buffer);

        if (currentSlot >= recordCount) {
            // 移动到下一页
            int nextPageNo = IndexPage.readNextPage(buffer);
            if (nextPageNo == 0) {
                return null;  // 扫描结束
            }
            currentPageId = new PageId(currentPageId.getSpaceId(), nextPageNo);
            currentSlot = 0;
            return next();  // 递归获取下一页的第一条记录
        }

        return readEntry(buffer, currentSlot);
    }

    @Override
    public void close() {
        if (currentLatch != null) {
            currentLatch.release();
        }
    }
}
```

## 7. 性能优化

### 7.1 锁粒度选择

| 场景 | 推荐锁粒度 | 原因 |
|------|-----------|------|
| 点查询 | 页面级读锁 | 冲突少，开销小 |
| 范围扫描 | 页面级读锁 + Latch Coupling | 减少锁持有时间 |
| 单条插入 | 乐观锁优先 | 大多数情况不需要分裂 |
| 批量插入 | 悲观锁 | 减少重试开销 |

### 7.2 热点页面处理

```java
public class HotPageOptimizer {
    private final Map<PageId, AtomicInteger> accessCount = new ConcurrentHashMap<>();
    private static final int HOT_THRESHOLD = 1000;

    public void recordAccess(PageId pageId) {
        accessCount.computeIfAbsent(pageId, k -> new AtomicInteger()).incrementAndGet();
    }

    public boolean isHotPage(PageId pageId) {
        AtomicInteger count = accessCount.get(pageId);
        return count != null && count.get() > HOT_THRESHOLD;
    }

    // 对于热点页面，可以考虑：
    // 1. 页面分裂，分散访问
    // 2. 使用更细粒度的锁
    // 3. 缓存优化
}
```

### 7.3 并发统计

```java
public class ConcurrencyStats {
    private final AtomicLong readLatchAcquired = new AtomicLong();
    private final AtomicLong writeLatchAcquired = new AtomicLong();
    private final AtomicLong latchContention = new AtomicLong();
    private final AtomicLong optimisticSuccess = new AtomicLong();
    private final AtomicLong pessimisticFallback = new AtomicLong();

    public String report() {
        return String.format(
            "Latches: read=%d, write=%d, contention=%d\n" +
            "Optimistic: success=%d, fallback=%d (%.1f%% success rate)",
            readLatchAcquired.get(), writeLatchAcquired.get(), latchContention.get(),
            optimisticSuccess.get(), pessimisticFallback.get(),
            getOptimisticSuccessRate() * 100
        );
    }

    public double getOptimisticSuccessRate() {
        long total = optimisticSuccess.get() + pessimisticFallback.get();
        return total == 0 ? 1.0 : (double) optimisticSuccess.get() / total;
    }
}
```

## 8. 最佳实践

### 8.1 减少锁持有时间

```java
// 好的做法：尽快释放锁
try (LatchHolder latch = LatchHolder.readLatch(frame)) {
    // 只在锁内做必要的操作
    byte[] data = readData(frame.buffer());
}
// 锁已释放，在锁外处理数据
processData(data);

// 不好的做法：长时间持有锁
try (LatchHolder latch = LatchHolder.readLatch(frame)) {
    byte[] data = readData(frame.buffer());
    processData(data);  // 耗时操作在锁内
}
```

### 8.2 避免锁升级

```java
// 好的做法：直接获取需要的锁
if (needWrite) {
    try (LatchHolder latch = LatchHolder.writeLatch(frame)) {
        // 写操作
    }
} else {
    try (LatchHolder latch = LatchHolder.readLatch(frame)) {
        // 读操作
    }
}

// 不好的做法：先读后升级
try (LatchHolder latch = LatchHolder.readLatch(frame)) {
    // 读操作
    if (needWrite) {
        latch.upgrade();  // 升级可能失败或导致死锁
        // 写操作
    }
}
```

### 8.3 批量操作优化

```java
// 对于批量插入，使用单独的加载过程
BulkLoadConfig config = BulkLoadConfig.builder()
    .fillFactor(0.9)
    .sortInput(true)
    .build();

// 批量加载不需要并发控制（独占访问）
BTreeBulkLoader loader = new BTreeBulkLoader(bufferPool, comparator, config);
loader.bulkLoad(indexId, spaceId, sortedRecords, mtr);
```
