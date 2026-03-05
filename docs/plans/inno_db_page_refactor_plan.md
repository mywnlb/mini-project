# InnoDB IndexPage 重构计划（修订版 v2）

> **目标**：减轻 IndexPage 的职责负担，将其从"物理+逻辑混合体"拆分为职责单一的组件。在保持 InnoDB 物理页原地修改语义的前提下，明确 **物理页管理**、**布局定义**、**页内算法** 的职责边界，消除 WAL/并发/维护性风险。

---

## 0. 设计总原则（Kernel Invariants）

**以下规则必须作为内核级不变量长期保持：**

1. **唯一物理真实体：BufferFrame**
   - Page 的身份（PageId）、并发状态（Latch）、脏页状态（Dirty）、LSN、FixCount 只能存在于 BufferFrame。
   - 不允许在 IndexPage / Layout / Ops 中保存任何"物理状态副本"。
   - **已有实现 `BufferFrame.java` 满足此要求，直接复用，无需新建 PageFrame。**

2. **所有物理写入必须经由 MTR**
   - 禁止任何模块直接 `buffer.put*()` 或 `markDirty()`。
   - MTR 是唯一允许：修改 ByteBuffer + 生成 redo + 更新 pageLSN + 标记 dirty 的入口。

3. **指针/Offset 生命周期受 Latch 保护**
   - record offset / slot index 仅在持有 page latch 的临界区内有效。
   - 不得跨 `unfixPage()` 保存或复用。

---

## 1. 重构后的总体分层架构（简化版）

```
BufferPool
   └── BufferFrame (已实现，物理页描述符)
          ├── PageId / pageLock (S/X) / pinCount
          ├── dirty / oldestModification
          ├── LRU / Flush 链表指针
          └── Page page → ByteBuffer (16KB)

IndexPageLayout (纯静态，只读)
   └── 偏移常量 + 纯函数读取方法
   └── 唯一的 slot 偏移计算公式

IndexPage (瘦身 wrapper，持有 frame)
   └── 持有 BufferFrame 引用
   └── getter 调用 Layout（只读）
   └── 不提供 setter（写操作交给 Ops）

IndexPageOps (页内算法，所有写操作必须带 MTR)
   └── initPage / insertRecord / deleteRecord
   └── reorganize / splitPage
   └── 所有写操作通过 mtr.writeXxx()
```

### 1.1 与原方案的对比

| 原方案 | 简化方案 | 说明 |
|-------|---------|------|
| 5 层 (Frame/View/Cursor/Service/Accessor) | 3 层 + 底座 | 减少委托层级 |
| 新建 PageFrame 类 | 复用 BufferFrame | 避免重复实现 |
| PageView 暴露 buf() | IndexPage 只暴露给 Ops | 更安全 |
| PageCursor + IndexPageService | 合并为 IndexPageOps | 读写算法统一管理 |

---

## 2. BufferFrame 复用与增强

### 2.1 现有 BufferFrame 已具备的能力

```java
public class BufferFrame {
    // 身份标识
    private final int frameId;
    private PageId pageId;
    private Page page;                    // 持有 ByteBuffer

    // 并发控制
    private final AtomicInteger pinCount;
    private final ReentrantReadWriteLock pageLock;  // S/X latch

    // 脏页状态
    private volatile boolean dirty;
    volatile long oldestModification;     // 用于 FlushList

    // LRU/Flush 链表
    volatile int lruPrev, lruNext;
    volatile int flushPrev, flushNext;
}
```

### 2.2 需要增强的方法（供 MTR 断言）

```java
// BufferFrame 新增
public boolean isWriteLatched() {
    return pageLock.isWriteLockedByCurrentThread();
}

public boolean isReadLatched() {
    return pageLock.getReadHoldCount() > 0;
}

// 直接获取 ByteBuffer（包可见，供 Ops 使用）
ByteBuffer buffer() {
    return page.getBuffer();
}
```

### 2.3 消除 Page.dirty 冗余

**当前问题**：`BufferFrame.dirty` 和 `Page.dirty` 同时存在。

**修复**：
- 删除 `Page` 类中的 `dirty` 字段和 `markDirty()` 方法
- 脏页状态唯一由 `BufferFrame` 管理
- `Page` 的 `putXxx()` 方法标记为 `@Deprecated`，最终删除

---

## 3. IndexPageLayout（纯静态，只读）

### 3.1 职责

- 所有偏移常量的唯一定义点
- 纯函数读取方法（不修改状态）
- **唯一的 slot 偏移计算公式**

### 3.2 实现

```java
public final class IndexPageLayout {

    // ==================== 偏移常量 ====================
    public static final int PAGE_HEADER_START = 38;  // FIL_HEADER_SIZE
    public static final int PAGE_N_DIR_SLOTS = PAGE_HEADER_START;
    public static final int PAGE_HEAP_TOP = PAGE_HEADER_START + 2;
    public static final int PAGE_N_HEAP = PAGE_HEADER_START + 4;
    public static final int PAGE_FREE = PAGE_HEADER_START + 6;
    public static final int PAGE_GARBAGE = PAGE_HEADER_START + 8;
    public static final int PAGE_LAST_INSERT = PAGE_HEADER_START + 10;
    public static final int PAGE_DIRECTION = PAGE_HEADER_START + 12;
    public static final int PAGE_N_DIRECTION = PAGE_HEADER_START + 14;
    public static final int PAGE_N_RECS = PAGE_HEADER_START + 16;
    public static final int PAGE_MAX_TRX_ID = PAGE_HEADER_START + 18;
    public static final int PAGE_LEVEL = PAGE_HEADER_START + 26;
    public static final int PAGE_INDEX_ID = PAGE_HEADER_START + 28;

    public static final int INFIMUM_OFFSET = 94;
    public static final int SUPREMUM_OFFSET = 107;
    public static final int USER_RECORDS_START = 120;

    public static final int PAGE_DIR_SLOT_SIZE = 2;
    public static final int FIL_TRAILER_SIZE = 8;
    public static final int PAGE_SIZE = 16384;

    // ==================== 唯一 Slot 偏移公式 ====================

    /**
     * 计算 Page Directory 槽的偏移（唯一实现，禁止其他地方计算）
     */
    public static int slotOffset(int slotNo) {
        return PAGE_SIZE - FIL_TRAILER_SIZE - (slotNo + 1) * PAGE_DIR_SLOT_SIZE;
    }

    // ==================== 纯函数读取方法 ====================

    public static int readSlotCount(ByteBuffer buf) {
        return buf.getShort(PAGE_N_DIR_SLOTS) & 0xFFFF;
    }

    public static int readSlotValue(ByteBuffer buf, int slotNo) {
        return buf.getShort(slotOffset(slotNo)) & 0xFFFF;
    }

    public static int readHeapTop(ByteBuffer buf) {
        return buf.getShort(PAGE_HEAP_TOP) & 0xFFFF;
    }

    public static int readRecordCount(ByteBuffer buf) {
        return buf.getShort(PAGE_N_RECS) & 0xFFFF;
    }

    public static int readLevel(ByteBuffer buf) {
        return buf.getShort(PAGE_LEVEL) & 0xFFFF;
    }

    public static int readFreeListHead(ByteBuffer buf) {
        return buf.getShort(PAGE_FREE) & 0xFFFF;
    }

    public static int readGarbageSize(ByteBuffer buf) {
        return buf.getShort(PAGE_GARBAGE) & 0xFFFF;
    }

    /**
     * 计算 Page Directory 底部位置
     */
    public static int pageDirectoryEnd(ByteBuffer buf) {
        int slotCount = readSlotCount(buf);
        return PAGE_SIZE - FIL_TRAILER_SIZE - (slotCount * PAGE_DIR_SLOT_SIZE);
    }

    /**
     * 计算剩余可用空间
     */
    public static int freeSpace(ByteBuffer buf) {
        return pageDirectoryEnd(buf) - readHeapTop(buf);
    }

    private IndexPageLayout() {} // 禁止实例化
}
```

---

## 4. IndexPage（瘦身 wrapper）

### 4.1 职责

- 持有 `BufferFrame` 引用（不是直接持有 ByteBuffer）
- 提供 getter 方法（调用 Layout）
- **不提供 setter 方法**（写操作全部交给 Ops + MTR）

### 4.2 实现

```java
public final class IndexPage {

    private final BufferFrame frame;

    public IndexPage(BufferFrame frame) {
        this.frame = frame;
    }

    // ==================== Frame 访问 ====================

    public BufferFrame frame() {
        return frame;
    }

    // 包可见，供 IndexPageOps 使用
    ByteBuffer buffer() {
        return frame.getPage().getBuffer();
    }

    public PageId getPageId() {
        return frame.getPageId();
    }

    // ==================== Getter（调用 Layout）====================

    public int getSlotCount() {
        return IndexPageLayout.readSlotCount(buffer());
    }

    public int getSlotValue(int slotNo) {
        return IndexPageLayout.readSlotValue(buffer(), slotNo);
    }

    public int getHeapTop() {
        return IndexPageLayout.readHeapTop(buffer());
    }

    public int getRecordCount() {
        return IndexPageLayout.readRecordCount(buffer());
    }

    public int getLevel() {
        return IndexPageLayout.readLevel(buffer());
    }

    public boolean isLeaf() {
        return getLevel() == 0;
    }

    public int getFreeSpace() {
        return IndexPageLayout.freeSpace(buffer());
    }

    public int getFreeListHead() {
        return IndexPageLayout.readFreeListHead(buffer());
    }

    public int getGarbageSize() {
        return IndexPageLayout.readGarbageSize(buffer());
    }

    // ==================== 记录链表遍历（只读）====================

    public int getFirstUserRecordOffset() {
        return getRecordNext(IndexPageLayout.INFIMUM_OFFSET);
    }

    public int getRecordNext(int recOffset) {
        short relOffset = buffer().getShort(recOffset + 3);
        if (relOffset == 0) {
            return 0;
        }
        return recOffset + relOffset;
    }

    // 注意：没有 setter 方法！所有写操作通过 IndexPageOps
}
```

---

## 5. IndexPageOps（页内算法，写操作 + MTR）

### 5.1 职责

- 所有页内修改操作（init / insert / delete / reorganize）
- 所有写操作必须通过 `mtr.writeXxx()`
- 必须在 X latch 下执行

### 5.2 实现

```java
public final class IndexPageOps {

    private IndexPageOps() {} // 工具类，禁止实例化

    // ==================== 页面初始化 ====================

    public static void initPage(IndexPage page, long indexId, int level, MiniTransaction mtr) {
        BufferFrame frame = page.frame();

        // 断言 X latch
        assert frame.isWriteLatched() : "Must hold X latch";

        // 初始化 Page Header
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 2);
        mtr.writeShort(frame, IndexPageLayout.PAGE_HEAP_TOP, (short) IndexPageLayout.USER_RECORDS_START);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_HEAP, (short) (0x8002)); // Compact + 2 records
        mtr.writeShort(frame, IndexPageLayout.PAGE_FREE, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_GARBAGE, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_LAST_INSERT, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_DIRECTION, (short) 5); // PAGE_NO_DIRECTION
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIRECTION, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_RECS, (short) 0);
        mtr.writeLong(frame, IndexPageLayout.PAGE_MAX_TRX_ID, 0L);
        mtr.writeShort(frame, IndexPageLayout.PAGE_LEVEL, (short) level);
        mtr.writeLong(frame, IndexPageLayout.PAGE_INDEX_ID, indexId);

        // 初始化 Infimum / Supremum
        initInfimumSupremum(frame, mtr);

        // 初始化 Page Directory
        initPageDirectory(frame, mtr);
    }

    private static void initInfimumSupremum(BufferFrame frame, MiniTransaction mtr) {
        // Infimum
        int off = IndexPageLayout.INFIMUM_OFFSET;
        mtr.writeByte(frame, off, (byte) 0x01);      // n_owned=1
        mtr.writeByte(frame, off + 1, (byte) 0x00);
        mtr.writeByte(frame, off + 2, (byte) 0x02);  // rec_type=infimum
        mtr.writeShort(frame, off + 3, (short) (IndexPageLayout.SUPREMUM_OFFSET - off));
        mtr.writeBytes(frame, off + 5, "infimum\0".getBytes());

        // Supremum
        off = IndexPageLayout.SUPREMUM_OFFSET;
        mtr.writeByte(frame, off, (byte) 0x01);      // n_owned=1
        mtr.writeByte(frame, off + 1, (byte) 0x00);
        mtr.writeByte(frame, off + 2, (byte) 0x0B);  // heap_no=1, rec_type=supremum
        mtr.writeShort(frame, off + 3, (short) 0);   // next=0 (end)
        mtr.writeBytes(frame, off + 5, "supremum".getBytes());
    }

    private static void initPageDirectory(BufferFrame frame, MiniTransaction mtr) {
        // Slot 0: supremum
        mtr.writeShort(frame, IndexPageLayout.slotOffset(0), (short) IndexPageLayout.SUPREMUM_OFFSET);
        // Slot 1: infimum
        mtr.writeShort(frame, IndexPageLayout.slotOffset(1), (short) IndexPageLayout.INFIMUM_OFFSET);
    }

    // ==================== 记录插入 ====================

    public static int insertRecord(IndexPage page, byte[] recordData, int insertAfter, MiniTransaction mtr) {
        BufferFrame frame = page.frame();
        assert frame.isWriteLatched() : "Must hold X latch";

        ByteBuffer buf = page.buffer();
        int recordSize = recordData.length;

        // 1. 分配空间
        int heapTop = IndexPageLayout.readHeapTop(buf);
        int newRecOffset = heapTop;

        // 2. 写入记录数据
        mtr.writeBytes(frame, newRecOffset, recordData);

        // 3. 更新 heap top
        mtr.writeShort(frame, IndexPageLayout.PAGE_HEAP_TOP, (short) (heapTop + recordSize));

        // 4. 更新记录链表
        int nextRec = buf.getShort(insertAfter + 3) + insertAfter;
        mtr.writeShort(frame, insertAfter + 3, (short) (newRecOffset - insertAfter));
        mtr.writeShort(frame, newRecOffset + 3, (short) (nextRec - newRecOffset));

        // 5. 更新记录计数
        int nRecs = IndexPageLayout.readRecordCount(buf);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_RECS, (short) (nRecs + 1));

        // 6. 更新 heap 记录数
        int nHeap = buf.getShort(IndexPageLayout.PAGE_N_HEAP) & 0x7FFF;
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_HEAP, (short) (0x8000 | (nHeap + 1)));

        return newRecOffset;
    }

    // ==================== Slot 操作 ====================

    public static void setSlotValue(IndexPage page, int slotNo, int recOffset, MiniTransaction mtr) {
        BufferFrame frame = page.frame();
        assert frame.isWriteLatched() : "Must hold X latch";

        int slotOff = IndexPageLayout.slotOffset(slotNo);
        mtr.writeShort(frame, slotOff, (short) recOffset);
    }

    public static void insertSlot(IndexPage page, int slotNo, MiniTransaction mtr) {
        BufferFrame frame = page.frame();
        assert frame.isWriteLatched() : "Must hold X latch";

        ByteBuffer buf = page.buffer();
        int slotCount = IndexPageLayout.readSlotCount(buf);

        // memmove 现有 slots
        int srcOff = IndexPageLayout.slotOffset(slotCount - 1);
        int dstOff = IndexPageLayout.slotOffset(slotCount);
        int len = (slotCount - slotNo) * IndexPageLayout.PAGE_DIR_SLOT_SIZE;

        mtr.memmove(frame, dstOff, srcOff, len);

        // 更新 slot count
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIR_SLOTS, (short) (slotCount + 1));
    }

    // ==================== 记录链表操作 ====================

    public static void setRecordNext(IndexPage page, int recOffset, int nextOffset, MiniTransaction mtr) {
        BufferFrame frame = page.frame();
        assert frame.isWriteLatched() : "Must hold X latch";

        short relOffset = (short) (nextOffset == 0 ? 0 : nextOffset - recOffset);
        mtr.writeShort(frame, recOffset + 3, relOffset);
    }

    public static void setRecordOwned(IndexPage page, int recOffset, int owned, MiniTransaction mtr) {
        BufferFrame frame = page.frame();
        assert frame.isWriteLatched() : "Must hold X latch";

        byte b = page.buffer().get(recOffset);
        mtr.writeByte(frame, recOffset, (byte) ((b & 0x0F) | ((owned & 0x0F) << 4)));
    }
}
```

---

## 6. MTR API 完整列表

MTR 必须提供以下写入原语，所有方法内部断言 X latch：

```java
public class MiniTransaction {

    // ==================== 基本写入 ====================

    public void writeByte(BufferFrame frame, int offset, byte value) {
        assertXLatched(frame);
        frame.buffer().put(offset, value);
        generateRedo(frame, offset, 1, value);
        markDirty(frame);
    }

    public void writeShort(BufferFrame frame, int offset, short value) {
        assertXLatched(frame);
        frame.buffer().putShort(offset, value);
        generateRedo(frame, offset, 2, value);
        markDirty(frame);
    }

    public void writeInt(BufferFrame frame, int offset, int value) {
        assertXLatched(frame);
        frame.buffer().putInt(offset, value);
        generateRedo(frame, offset, 4, value);
        markDirty(frame);
    }

    public void writeLong(BufferFrame frame, int offset, long value) {
        assertXLatched(frame);
        frame.buffer().putLong(offset, value);
        generateRedo(frame, offset, 8, value);
        markDirty(frame);
    }

    public void writeBytes(BufferFrame frame, int offset, byte[] data) {
        assertXLatched(frame);
        ByteBuffer buf = frame.buffer();
        buf.position(offset);
        buf.put(data);
        generateRedo(frame, offset, data);
        markDirty(frame);
    }

    // ==================== memmove ====================

    /**
     * 内存移动（处理重叠区间）
     * Redo 记录移动后的最终数据，而非移动操作本身（保证幂等性）
     */
    public void memmove(BufferFrame frame, int dst, int src, int len) {
        assertXLatched(frame);
        ByteBuffer buf = frame.buffer();

        // 读取源数据
        byte[] data = new byte[len];
        buf.position(src);
        buf.get(data);

        // 写入目标位置
        buf.position(dst);
        buf.put(data);

        // Redo 记录最终数据
        generateRedo(frame, dst, data);
        markDirty(frame);
    }

    // ==================== 内部方法 ====================

    private void assertXLatched(BufferFrame frame) {
        assert frame.isWriteLatched() :
            "Must hold X latch on frame " + frame.getFrameId();
    }

    private void markDirty(BufferFrame frame) {
        if (!frame.isDirty()) {
            frame.setDirty(true);
            // 更新 FlushList...
        }
        // 更新 pageLSN...
    }
}
```

---

## 7. 操作时序示例

### 7.1 插入一条记录

```
1. frame = bufferPool.fixPage(pageId, LatchMode.X)
2. IndexPage page = new IndexPage(frame)
3. mtr.begin()
4. int slotNo = findInsertSlot(page, key)        // 只读操作
5. int prevRec = findPrevRecord(page, slotNo)    // 只读操作
6. int newRec = IndexPageOps.insertRecord(page, recordData, prevRec, mtr)
7. updateSlotOwnership(page, slotNo, newRec, mtr)
8. mtr.commit()
9. bufferPool.unfixPage(frame)
```

### 7.2 页面初始化

```
1. frame = bufferPool.allocatePage(spaceId)
2. frame.writeLock()
3. IndexPage page = new IndexPage(frame)
4. mtr.begin()
5. IndexPageOps.initPage(page, indexId, level, mtr)
6. mtr.commit()
7. frame.writeUnlock()
```

---

## 8. 分阶段实施路线

### Phase 1：BufferFrame 增强 + Page.dirty 清理

- [ ] BufferFrame 添加 `isWriteLatched()`, `isReadLatched()` 方法
- [ ] BufferFrame 添加包可见 `buffer()` 方法
- [ ] Page 类删除 `dirty` 字段，`markDirty()` 标记 `@Deprecated`
- [ ] Page 类的 `putXxx()` 方法标记 `@Deprecated`

### Phase 2：抽取 IndexPageLayout

- [ ] 从 IndexPage 抽取所有偏移常量到 IndexPageLayout
- [ ] 实现纯函数读取方法
- [ ] 确保 slot 偏移计算唯一入口

### Phase 3：IndexPage 瘦身

- [ ] IndexPage 改为持有 BufferFrame
- [ ] 删除所有 setter 方法
- [ ] getter 改为调用 Layout

### Phase 4：实现 IndexPageOps

- [ ] 实现 initPage / insertRecord / deleteRecord
- [ ] 所有写操作通过 MTR
- [ ] 添加 X latch 断言

### Phase 5：MTR API 补全

- [ ] 实现完整的 writeXxx 系列方法
- [ ] 实现 memmove
- [ ] X latch 断言

---

## 9. 关键风险点与防护

### 9.1 惊群写入的根治

**根因**：多个对象能绕开统一写入口直接修改 buffer。

**防护**：
- IndexPageOps 所有写操作必须用 `mtr.writeXxx(frame, ...)`
- IndexPageLayout 只读（无写方法）
- IndexPage 不暴露可写 ByteBuffer

### 9.2 Page Directory memmove 风险

**防护**：
- memmove 必须由 MTR 提供原语
- directory slot offset 只能从 `IndexPageLayout.slotOffset()` 获取
- 禁止任何地方自行计算 slot 偏移

### 9.3 LSN 同步机制

**两个 LSN 的关系**：
- `BufferFrame.oldestModification`：内存元数据，用于 FlushList 排序
- `Page.FIL_PAGE_LSN`：物理字段，刷盘时写入

**更新时机**：MTR commit 时同步更新两者。

---

## 10. 强制测试与验收

- [ ] IndexPageLayout 单测：slot 偏移计算边界
- [ ] IndexPageOps 单测：init / insert / slot 操作
- [ ] WAL 一致性测试：
  - 无 MTR 写入 = 0 次 `buffer.put*`
  - pageLSN 单调递增
- [ ] Latch 断言测试：无 X latch 时写操作抛异常

---

## 11. 明确的简化点（当前阶段）

- 仅支持 Compact 行格式
- 不支持 off-page / BLOB
- 不支持压缩页
- slot 查找先线性，后优化二分（代码量差异小，建议直接二分）
- RecordAccessor 可后置，但 originOffset 语义需写进注释+单测

---

## 12. Compact Record 工具类（最小实现）

即使不做完整 RecordAccessor，也需要固定 originOffset 语义：

```java
public final class CompactRecordUtil {

    // Record Header 相对 origin 的负偏移
    public static final int OFF_NEXT_REC = -2;   // next_record (2 bytes)
    public static final int OFF_REC_TYPE = -3;   // rec_type (3 bits)
    public static final int OFF_N_OWNED = -5;    // n_owned (4 bits)

    public static final int DELETE_MASK = 0x20;

    public static int nextRecOffset(ByteBuffer buf, int origin) {
        short rel = buf.getShort(origin + OFF_NEXT_REC);
        return rel == 0 ? 0 : origin + rel;
    }

    public static boolean isDeleted(ByteBuffer buf, int origin) {
        return (buf.get(origin + OFF_N_OWNED) & DELETE_MASK) != 0;
    }

    public static int getRecType(ByteBuffer buf, int origin) {
        return buf.get(origin + OFF_REC_TYPE) & 0x07;
    }

    public static int getNOwned(ByteBuffer buf, int origin) {
        return (buf.get(origin + OFF_N_OWNED) >> 4) & 0x0F;
    }

    private CompactRecordUtil() {}
}
```

---

> **结论**：该修订方案在复用现有 `BufferFrame` 的基础上，通过 3 层结构（Layout/Page/Ops）+ MTR 写入约束，将 IndexPage 从"物理+逻辑混合体"拆分为职责单一的组件，显著降低维护成本和 WAL/并发风险。
