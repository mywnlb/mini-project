# BufferPool 实现说明与使用指南

本文档总结 mini-db 当前 BufferPool 的实现结构、并发语义、使用方式与注意事项，便于写存储层代码时正确使用缓冲池。

## 设计目标

- 以页为单位缓存磁盘数据，减少磁盘 I/O
- 支持高并发读写：PageHash 分段锁 + 近似 LRU（后台整理）
- 支持脏页管理与刷盘，提供基础的 WAL 规则接入点（RedoLogManager）

## 核心结构

BufferPool 内部主要由以下组件组成：

- frames：固定大小的 BufferFrame 数组，每个 frame 持有一个 Page
- PageHashSegment[]：PageId → frameIndex 的分段映射（每段独立锁）
- FreeList：空闲 frame 队列
- LRUList：Young/Old 分区的 LRU（访问热路径只更新时间戳，后台线程整理顺序）
- FlushList：脏页集合（按 oldest LSN 组织）
- BufferPoolMetrics：运行时统计与健康度指标

对应实现：

- BufferPool：`src/main/java/.../storage/buffer/BufferPool.java`
- BufferFrame：`src/main/java/.../storage/buffer/BufferFrame.java`
- PageHashSegment：`src/main/java/.../storage/buffer/PageHashSegment.java`
- LRUList / FreeList / FlushList：`src/main/java/.../storage/buffer/*.java`

## 分段 PageHash（PageHashSegment）

- segmentCount 由 BufferPoolConfig 指定，必须是 2 的幂（16/32/64/128/256…）
- 选择 segment 的方式是“取 hashCode 的高位”，位数由 segmentCount 动态计算：

```text
segmentBits = log2(segmentCount)
shift       = 32 - segmentBits
index       = (hash >>> shift) & (segmentCount - 1)
```

注意：PageHashSegment 的 `get/put/remove` 自身会加锁；外部通常不需要再包一层 segment 锁。

## LRU（Young/Old + 近似无锁访问）

### 分区规则（简化）

- 新加载页面进入 Old 区（避免扫描污染 Young）
- 页面在 Old 区驻留超过 oldBlockTimeMs 后，再次访问会触发“晋升请求”，由后台线程移动到 Young
- 淘汰优先从 Old 区尾部开始找可淘汰页，不足时再看 Young 区尾部

### 后台整理线程

BufferPool 启动后台线程按固定间隔调用 `LRUList.reorderPeriodically()`：

- 处理 Old → Young 的晋升请求
- 进行部分 Young → Old 的降级（冷页回收）
- 重新平衡 Young/Old 比例
- 更新 LRU precision 指标

## 并发语义（必须了解）

### 1) pin/unpin 约束

- getPage 返回的 BufferFrame 默认会被 pin
- 使用完必须调用 `unpinPage(pageId, isDirty)`，否则页面不会被淘汰，最终触发 BufferExhausted

### 2) 淘汰屏障

为避免“命中后 pin 到正在被淘汰的 frame”，frame 内部维护淘汰状态：

- 命中路径用 `tryPin()`：若 frame 正在淘汰，则不会 pin，调用方重试
- 淘汰路径用 `tryAcquireForEviction()`：确保只有一个线程能进入淘汰，并且不会与并发 pin 交错

### 3) pageLock（页面内容锁）

- `frame.readLock()`：读页面内容
- `frame.writeLock()`：修改页面内容，或执行刷盘前的 prepareForFlush（会写校验和）

建议：任何可能修改 Page ByteBuffer 的操作，都在 `frame.writeLock()` 保护下执行。

### 4) 锁顺序（避免死锁）

涉及跨结构的更新（加载/删除/淘汰等）需要遵循固定顺序：

```text
poolLock → PageHashSegment 写锁 → LRUList 锁 → FlushList 锁 → frame.pageLock
```

原则：不要在持有 frame.pageLock 的情况下再调用 BufferPool 的结构性方法（如 getPage/deletePage/flushAllPages），以免形成反向锁序。

## 使用方式

### 读取已有页面

```java
BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
try {
    frame.readLock();
    try {
        Page page = frame.getPage();
        // 读取 page 内容
    } finally {
        frame.readUnlock();
    }
} finally {
    bufferPool.unpinPage(pageId, false);
}
```

### 修改页面并标脏

```java
BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
try {
    frame.writeLock();
    try {
        Page page = frame.getPage();
        // 修改 page 内容（会 markDirty）
    } finally {
        frame.writeUnlock();
    }
} finally {
    bufferPool.unpinPage(pageId, true);
}
```

注意：本实现的脏页登记主要由 `unpinPage(pageId, true)` 触发，调用方需要显式传入 isDirty。

### 新建页面

```java
BufferFrame frame = bufferPool.newPage(spaceId);
// 返回的 frame 已 pin，按需初始化后调用 unpinPage(pageId, true/false)
```

### 刷盘与关闭

- `flushPage(pageId)`：刷单页（内部会持 frame 写锁并更新 checksum）
- `flushAllPages()`：刷全部脏页（实现可随版本演进；调用者不应依赖其内部阶段细节）
- `close()`：停止后台线程并刷盘

如果接入 RedoLogManager：

- 调用 `bufferPool.setRedoLogManager(redoLogManager)` 后，flush 会遵守 WAL 规则（等待对应 LSN 的 redo fsync）

## 注意事项清单

- 每次 getPage 必须对应一次 unpinPage
- 修改页面内容用 frame.writeLock 保护；读页面内容用 frame.readLock
- `flushPage/flushAllPages` 会调用 `Page.prepareForFlush()`，它会写 checksum，因此需要写锁保护
- segmentCount 必须为 2 的幂；否则 segmentMask 计算与分段逻辑无意义
- LRU 是近似的：短时间内顺序可能不精确，但在后台线程作用下会收敛

## 常用查询

- `bufferPool.getStats()`：容量/命中/读写/young-old 分布
- `bufferPool.getMetrics()`：详细指标与健康度（含 LRU precision、锁等待、flush 统计等）
