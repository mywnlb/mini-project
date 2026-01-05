# Segment & Extent 空间管理实现状态

> **最后更新时间**: 2026-01-05
> **当前阶段**: 阶段1 - 基础数据结构（进行中）

---

## 一、总体进度概览

```
[████████░░░░░░░░░░░░░░░░░░░░] 20% 完成

✅ 阶段0: 规划设计（已完成）
🔄 阶段1: 基础数据结构（进行中 - 2/7）
⏳ 阶段2: ExtentManager（待开始）
⏳ 阶段3: SegmentManager（待开始）
⏳ 阶段4: SpaceManager（待开始）
⏳ 阶段5: 集成修改（待开始）
⏳ 阶段6: 测试验证（待开始）
```

---

## 二、已完成的工作

### ✅ 阶段0: 规划设计（2026-01-05 完成）

**完成内容**：
1. **代码探索** - 探索了 DiskManager、Page、InnoDB空间管理文档
2. **架构设计** - 设计了完整的三层架构（SpaceManager → SegmentManager → ExtentManager）
3. **数据结构设计** - 定义了 FSP_HDR、XDES、INODE 的详细格式
4. **核心算法设计** - 设计了3阶段分配策略和Extent状态转移
5. **用户确认** - 确认支持超大表空间（多个XDES Page）、6阶段实施、保持DiskManager不变

**输出文档**：
- 完整实现计划：`C:\Users\李波\.claude\plans\immutable-inventing-horizon.md`

### ✅ 阶段1: 基础数据结构（部分完成 - 2/7）

#### 1. StorageConstants（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/constants/StorageConstants.java`

**新增内容**：
```java
// ==================== Extent & Segment 相关常量 ====================
- EXTENT_SIZE = 64                          // 64页/Extent
- EXTENT_SIZE_BYTES = 1048576               // 1MB
- EXTENTS_PER_GROUP = 256                   // 每组256个Extent
- PAGES_PER_EXTENT_GROUP = 16384            // 每组16384页

// ==================== FSP Header 相关常量 ====================
- FSP_HEADER_SIZE = 112
- FSP_SPACE_ID, FSP_SIZE, FSP_FREE_LIMIT, FSP_SEG_ID
- FSP_FREE, FSP_FREE_FRAG, FSP_FULL_FRAG
- FSP_SEG_INODES_FREE, FSP_SEG_INODES_FULL

// ==================== XDES Entry 相关常量 ====================
- XDES_ENTRY_SIZE = 40
- XDES_ARR_OFFSET = 150
- XDES_ID, XDES_FLST_NODE, XDES_STATE, XDES_BITMAP

// ==================== INODE Page 相关常量 ====================
- INODE_ENTRY_SIZE = 192
- INODES_PER_PAGE = 85
- INODE_PAGE_HEADER_SIZE = 12
- INODE_SEGMENT_ID, INODE_NOT_FULL_N_USED
- INODE_FREE, INODE_NOT_FULL, INODE_FULL
- INODE_MAGIC_N, INODE_FRAG_ARRAY, INODE_FRAG_ARRAY_SIZE
- INODE_FRAG_ARRAY_PAGES = 32
- INODE_MAGIC_NUMBER = 97937874

// ==================== 链表相关常量 ====================
- FLST_BASE_NODE_SIZE = 16
- FLST_NODE_SIZE = 12
```

**设计要点**：
- 所有常量都有详细的 Javadoc 注释
- 包含 InnoDB 对应关系说明
- 计算公式清晰，便于理解

#### 2. ExtentState 枚举（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/ExtentState.java`

**枚举值**：
```java
FREE(1)       - 完全空闲，属于表空间
FREE_FRAG(2)  - 碎片区，部分分配
FULL_FRAG(3)  - 碎片区，全满
FSEG(4)       - 属于Segment（部分或全满）
FSEG_FREE(5)  - 属于Segment，但完全空闲
```

**核心方法**：
```java
int getValue()                      // 获取状态值
static ExtentState fromValue(int)   // 从值获取枚举
boolean isTablespaceOwned()         // 是否属于表空间
boolean isSegmentOwned()            // 是否属于Segment
boolean canAllocatePage()           // 是否可以分配页面
```

**状态转移图**：
```
FREE → FSEG_FREE → FSEG (Segment路径)
FREE → FREE_FRAG → FULL_FRAG (碎片页路径)
```

---

## 三、待实现的工作

### 🔄 阶段1: 基础数据结构（剩余 5/7）

#### 3. FspHeaderPage（待实现 ⏳）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/FspHeaderPage.java`

**继承关系**: `extends Page`

**核心职责**：
- 封装 FSP_HDR Page (page 0) 的读写操作
- 提供 FSP Header 各字段的 getter/setter
- 管理6个Extent链表（FREE, FREE_FRAG, FULL_FRAG, SEG_INODES_FREE, SEG_INODES_FULL）
- 提供访问 XDES Array 的方法

**关键方法设计**：
```java
public class FspHeaderPage extends Page {
    // 构造函数
    public FspHeaderPage(PageId pageId);
    public FspHeaderPage(Page page);

    // FSP Header 字段访问
    public int getSpaceId();
    public void setSpaceId(MiniTransaction mtr, int spaceId);

    public int getSize();
    public void setSize(MiniTransaction mtr, int size);

    public int getFreeLimit();
    public void setFreeLimit(MiniTransaction mtr, int freeLimit);

    public long getNextSegmentId();
    public long allocateSegmentId(MiniTransaction mtr);  // 原子递增

    // XDES Array 访问（0-255号Extent）
    public int getXdesEntryOffset(int extentNo);
    public ExtentDescriptor getXdesEntry(MiniTransaction mtr, int extentNo);

    // 链表操作（通过FlstBaseNode封装）
    public void initExtentLists(MiniTransaction mtr);
    public void addToFreeList(MiniTransaction mtr, ExtentDescriptor extent);
    public ExtentDescriptor popFromFreeList(MiniTransaction mtr);

    // ... 其他链表操作方法
}
```

**物理布局**：
```
[FIL Header 38B]
[FSP Header 112B]
  ├─ FSP_SIZE (4B)
  ├─ FSP_FREE_LIMIT (4B)
  ├─ FSP_SEG_ID (8B)
  ├─ FSP_FREE (16B FLST_BASE_NODE)
  ├─ FSP_FREE_FRAG (16B)
  ├─ FSP_FULL_FRAG (16B)
  ├─ FSP_SEG_INODES_FREE (16B)
  └─ FSP_SEG_INODES_FULL (16B)
[XDES Array 10240B = 256 × 40B]
[Unused]
[FIL Trailer 8B]
```

**实现注意事项**：
1. 所有修改操作必须通过 MTR 标记 dirty
2. 需要实现链表操作的辅助类（FlstBaseNode, FlstNode）
3. XDES Array 只能访问前256个Extent（0-255）

#### 4. XdesPage（待实现 ⏳）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/XdesPage.java`

**继承关系**: `extends Page`

**核心职责**：
- 封装独立的 XDES Page (page 16384, 32768, ...) 的读写
- 与 FspHeaderPage 共享 XDES Entry 访问逻辑
- 支持超大表空间（超过256MB）

**关键方法设计**：
```java
public class XdesPage extends Page {
    public XdesPage(PageId pageId);
    public XdesPage(Page page);

    // 计算XDES Page位置
    public static int getXdesPageNo(int extentNo);  // = (extentNo / 256) * 16384

    // 计算Extent在XDES Page中的偏移
    public static int getXdesEntryOffset(int extentNo);  // = 38 + (extentNo % 256) * 40

    // XDES Entry 访问
    public ExtentDescriptor getXdesEntry(MiniTransaction mtr, int extentNo);

    // 初始化（创建新XDES Page时调用）
    public void initialize(MiniTransaction mtr);
}
```

**物理布局**：
```
[FIL Header 38B]
[XDES Array 10240B = 256 × 40B]
[Unused 6098B]
[FIL Trailer 8B]
```

**XDES Page 位置规律**：
```
extentNo     XDES Page位置
0-255     →  page 0 (FSP_HDR)
256-511   →  page 16384
512-767   →  page 32768
768-1023  →  page 49152
...
```

**实现注意事项**：
1. XDES Page 不包含 FSP Header，直接从 FIL Header 后开始 XDES Array
2. 需要与 FspHeaderPage 共享 XDES Entry 解析逻辑（考虑提取公共基类或工具类）

#### 5. InodePage（待实现 ⏳）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/InodePage.java`

**继承关系**: `extends Page`

**核心职责**：
- 封装 INODE Page 的读写操作
- 管理85个 INODE Entry
- 提供 Segment 的元数据访问

**关键方法设计**：
```java
public class InodePage extends Page {
    public InodePage(PageId pageId);
    public InodePage(Page page);

    // INODE Page Header
    public void setMagicNumber(MiniTransaction mtr, int magic);
    public int getNextPageNo();
    public void setNextPageNo(MiniTransaction mtr, int pageNo);

    // INODE Entry 访问
    public int getInodeEntryOffset(int entryIndex);  // 0-84
    public SegmentDescriptor getInodeEntry(MiniTransaction mtr, int entryIndex);

    // 查找空闲 Entry
    public int findFreeInodeEntry(MiniTransaction mtr);

    // 初始化（创建新INODE Page时调用）
    public void initialize(MiniTransaction mtr);
    public void initAllEntries(MiniTransaction mtr);  // 将85个Entry的SegmentID设为0
}
```

**物理布局**：
```
[FIL Header 38B]
[INODE Header 12B]
  ├─ INODE_PAGE_LIST (12B FLST_NODE)
[INODE Entry #0 192B]
[INODE Entry #1 192B]
...
[INODE Entry #84 192B]
[Unused 6B]
[FIL Trailer 8B]

总计: 38 + 12 + 192×85 + 6 + 8 = 16384
```

**实现注意事项**：
1. 每个 INODE Page 包含85个 Entry（硬编码）
2. INODE Entry 的 SegmentID = 0 表示未分配
3. INODE Page 通过链表连接（FSP_SEG_INODES_FREE/FULL）

#### 6. ExtentDescriptor（待实现 ⏳）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/ExtentDescriptor.java`

**核心职责**：
- 封装 XDES Entry (40字节) 的读写操作
- 提供 Bitmap 操作（分配/释放页面）
- 管理 Extent 状态转移

**关键方法设计**：
```java
public class ExtentDescriptor {
    private final Page page;         // XDES Entry 所在的页面（FSP_HDR或XDES Page）
    private final int offset;        // XDES Entry 在页面中的偏移
    private final int extentNo;      // Extent 编号
    private final int startPageNo;   // Extent 起始页号 = extentNo * 64

    public ExtentDescriptor(Page page, int offset, int extentNo);

    // 基本字段访问
    public long getSegmentId();
    public void setSegmentId(MiniTransaction mtr, long segmentId);

    public ExtentState getState();
    public void setState(MiniTransaction mtr, ExtentState state);

    public int getExtentNo();
    public int getStartPageNo();

    // Bitmap 操作（每页2 bits）
    public boolean isPageFree(int pageOffset);  // pageOffset: 0-63
    public void allocatePage(MiniTransaction mtr, int pageOffset);
    public void freePage(MiniTransaction mtr, int pageOffset);
    public void initBitmap(MiniTransaction mtr);  // 设置所有页为FREE

    // 查找空闲页
    public int findFreePage();  // 返回0-63，-1表示无空闲

    // 状态查询
    public boolean isFull();
    public boolean isEmpty();
    public int getUsedPageCount();

    // 链表操作（FLST_NODE）
    public void setNextExtent(MiniTransaction mtr, int pageNo, int offset);
    public void setPrevExtent(MiniTransaction mtr, int pageNo, int offset);
}
```

**XDES Entry 结构**（40字节）：
```
Offset  Size  Field
------  ----  -----
0       8     XDES_ID (Segment ID)
8       12    XDES_FLST_NODE (链表节点)
20      4     XDES_STATE (状态)
24      16    XDES_BITMAP (64页×2bits=128bits)
```

**Bitmap 编码**（每页2 bits）：
```
Bit 0: FREE (1=空闲, 0=已分配)
Bit 1: CLEAN (1=干净, 0=脏页) [简化版可忽略]
```

**实现注意事项**：
1. Bitmap 操作需要位运算（注意字节序）
2. 每次修改都要调用 page.markDirty(mtr)
3. 状态转移需要同步更新链表

#### 7. SegmentDescriptor（待实现 ⏳）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/SegmentDescriptor.java`

**核心职责**：
- 封装 INODE Entry (192字节) 的读写操作
- 管理3个 Extent 链表（FREE, NOT_FULL, FULL）
- 管理碎片页数组（32个页号）

**关键方法设计**：
```java
public class SegmentDescriptor {
    private final InodePage inodePage;  // INODE Entry 所在的 INODE Page
    private final int offset;            // INODE Entry 在页面中的偏移

    public SegmentDescriptor(InodePage inodePage, int offset);

    // 基本字段访问
    public long getSegmentId();
    public void setSegmentId(MiniTransaction mtr, long segmentId);

    public int getNotFullNUsed();
    public void setNotFullNUsed(MiniTransaction mtr, int count);

    public int getMagicNumber();
    public void setMagicNumber(MiniTransaction mtr, int magic);

    // 碎片页数组操作
    public int getFragPageNo(int index);  // index: 0-31
    public void setFragPageNo(MiniTransaction mtr, int index, int pageNo);
    public int findFreeFragSlot();  // 返回0-31，-1表示数组已满
    public int getFragUsedCount();

    // 链表访问（返回 FlstBaseNode 对象）
    public FlstBaseNode getFreeList();
    public FlstBaseNode getNotFullList();
    public FlstBaseNode getFullList();

    // 高层次操作
    public ExtentDescriptor getFirstFreeExtent(MiniTransaction mtr);
    public ExtentDescriptor getFirstPartialExtent(MiniTransaction mtr);

    // 初始化
    public void initialize(MiniTransaction mtr, long segmentId);
}
```

**INODE Entry 结构**（192字节）：
```
Offset  Size  Field
------  ----  -----
0       8     INODE_SEGMENT_ID
8       4     INODE_NOT_FULL_N_USED
12      16    INODE_FREE (FLST_BASE_NODE)
28      16    INODE_NOT_FULL (FLST_BASE_NODE)
44      16    INODE_FULL (FLST_BASE_NODE)
60      4     INODE_MAGIC_N
64      128   INODE_FRAG_ARRAY (32 × 4B)
```

**实现注意事项**：
1. SegmentID = 0 表示 INODE Entry 未分配
2. 碎片页数组：PageNo = FIL_NULL (0xFFFFFFFF) 表示未分配
3. 需要实现链表操作的辅助类（FlstBaseNode）

---

### ⏳ 阶段2: ExtentManager（待开始）

**文件清单**：
1. `ExtentManager.java` (接口)
2. `ExtentManagerImpl.java` (实现)

**核心方法**：
```java
public interface ExtentManager {
    // 获取 Extent 描述符
    ExtentDescriptor getExtentDescriptor(MiniTransaction mtr, int spaceId, int extentNo);

    // 在 Extent 中分配/释放页面
    int allocatePageInExtent(MiniTransaction mtr, ExtentDescriptor extent);
    void freePageInExtent(MiniTransaction mtr, ExtentDescriptor extent, int pageOffset);

    // 初始化和状态管理
    void initializeExtent(MiniTransaction mtr, ExtentDescriptor extent);
    void setExtentState(MiniTransaction mtr, ExtentDescriptor extent, ExtentState newState);

    // 查询
    int getUsedPageCount(ExtentDescriptor extent);
}
```

**关键实现逻辑**：
```java
public ExtentDescriptor getExtentDescriptor(MiniTransaction mtr, int spaceId, int extentNo) {
    // 1. 计算 XDES Page 位置
    int xdesPageNo = XdesPage.getXdesPageNo(extentNo);  // (extentNo/256)*16384

    // 2. 加载 XDES Page
    PageId xdesPageId = new PageId(spaceId, xdesPageNo);
    Page xdesPage;
    if (xdesPageNo == 0) {
        xdesPage = new FspHeaderPage(mtr.getPage(xdesPageId));
    } else {
        xdesPage = new XdesPage(mtr.getPage(xdesPageId));
    }

    // 3. 计算 XDES Entry 偏移
    int offset = XdesPage.getXdesEntryOffset(extentNo);

    // 4. 创建 ExtentDescriptor
    return new ExtentDescriptor(xdesPage, offset, extentNo);
}
```

---

### ⏳ 阶段3: SegmentManager（待开始）

**文件清单**：
1. `SegmentManager.java` (接口)
2. `SegmentManagerImpl.java` (实现)

**核心方法**：
```java
public interface SegmentManager {
    // Segment 生命周期
    long createSegment(MiniTransaction mtr, int spaceId);
    void dropSegment(MiniTransaction mtr, int spaceId, long segmentId);

    // 页面分配（3阶段策略）
    PageId allocatePageForSegment(MiniTransaction mtr, int spaceId, long segmentId);
    void freePage(MiniTransaction mtr, PageId pageId);

    // Extent 分配
    ExtentDescriptor allocateExtentForSegment(MiniTransaction mtr, int spaceId, long segmentId);

    // 查询
    SegmentStatistics getStatistics(MiniTransaction mtr, int spaceId, long segmentId);
}
```

**核心算法：3阶段分配策略**（来自计划文档）：
```java
public PageId allocatePageForSegment(MiniTransaction mtr, int spaceId, long segmentId) {
    // Step 0: 加载 INODE Entry
    SegmentDescriptor segment = loadSegmentDescriptor(mtr, spaceId, segmentId);

    // Step 1: 尝试从碎片数组分配（前32页）
    if (segment.getFragUsedCount() < 32) {
        int freeSlot = segment.findFreeFragSlot();
        if (freeSlot != -1) {
            PageId pageId = spaceManager.allocateFragPage(mtr, spaceId);
            segment.setFragPageNo(mtr, freeSlot, pageId.getPageNo());
            return pageId;
        }
    }

    // Step 2: 尝试从 Partial Extent 分配
    ExtentDescriptor partialExt = segment.getFirstPartialExtent(mtr);
    if (partialExt != null) {
        int pageOffset = partialExt.findFreePage();
        if (pageOffset != -1) {
            partialExt.allocatePage(mtr, pageOffset);

            // 检查是否变为 Full
            if (partialExt.isFull()) {
                moveExtentToFullList(mtr, segment, partialExt);
            }

            return new PageId(spaceId, partialExt.getStartPageNo() + pageOffset);
        }
    }

    // Step 3: 尝试从 Free Extent 分配
    ExtentDescriptor freeExt = segment.getFirstFreeExtent(mtr);
    if (freeExt != null) {
        int pageOffset = freeExt.findFreePage();
        freeExt.allocatePage(mtr, pageOffset);

        // 移动到 Partial 链表
        moveExtentToPartialList(mtr, segment, freeExt);

        return new PageId(spaceId, freeExt.getStartPageNo() + pageOffset);
    }

    // Step 4: 申请新 Extent
    ExtentDescriptor newExt = spaceManager.allocateExtent(mtr, spaceId, segmentId);
    if (newExt == null) {
        // 扩展表空间
        spaceManager.extendTablespace(mtr, spaceId, 4);  // 扩展4个Extent = 4MB
        newExt = spaceManager.allocateExtent(mtr, spaceId, segmentId);
    }

    // 分配第一页并加入 Partial 链表
    int pageOffset = newExt.findFreePage();
    newExt.allocatePage(mtr, pageOffset);
    addExtentToPartialList(mtr, segment, newExt);

    return new PageId(spaceId, newExt.getStartPageNo() + pageOffset);
}
```

---

### ⏳ 阶段4: SpaceManager（待开始）

**文件清单**：
1. `SpaceManager.java` (接口)
2. `SpaceManagerImpl.java` (实现)

**核心方法**：
```java
public interface SpaceManager {
    // 表空间生命周期
    void initializeTablespace(MiniTransaction mtr, int spaceId, String name);
    void openTablespace(MiniTransaction mtr, int spaceId, String name);

    // Extent 管理
    ExtentDescriptor allocateExtent(MiniTransaction mtr, int spaceId, long segmentId);
    void freeExtent(MiniTransaction mtr, ExtentDescriptor extent);

    // 表空间扩展
    void extendTablespace(MiniTransaction mtr, int spaceId, int extentCount);

    // 碎片页分配
    PageId allocateFragPage(MiniTransaction mtr, int spaceId);

    // 统计
    SpaceStatistics getStatistics(int spaceId);
}
```

**初始化表空间逻辑**：
```java
public void initializeTablespace(MiniTransaction mtr, int spaceId, String name) {
    // Step 1: 创建物理文件
    diskManager.createTablespace(spaceId, name);

    // Step 2: 初始化 Page 0 (FSP_HDR)
    PageId page0Id = new PageId(spaceId, 0);
    FspHeaderPage fspHdr = new FspHeaderPage(mtr.newPage(page0Id));
    fspHdr.setSpaceId(mtr, spaceId);
    fspHdr.setSize(mtr, 1);
    fspHdr.setFreeLimit(mtr, 1);
    fspHdr.setNextSegmentId(mtr, 1);
    fspHdr.initExtentLists(mtr);
    mtr.markDirty(fspHdr);

    // Step 3: 预分配第一个 Extent（64页）
    extendTablespace(mtr, spaceId, 1);

    // Step 4: 初始化 Page 1 (第一个 INODE Page)
    PageId page1Id = new PageId(spaceId, 1);
    InodePage inodePage = new InodePage(mtr.newPage(page1Id));
    inodePage.setMagicNumber(mtr, INODE_MAGIC_NUMBER);
    inodePage.initAllEntries(mtr);
    mtr.markDirty(inodePage);

    // Step 5: 将 INODE Page 加入 FSP_SEG_INODES_FREE 链表
    fspHdr.addToInodeFreeList(mtr, inodePage);

    mtr.commit();
}
```

---

### ⏳ 阶段5: 集成修改（待开始）

**修改清单**：

#### 1. TablespaceFile.java

**新增方法**：
```java
/**
 * 批量扩展表空间（用于 Extent 分配）
 *
 * @param pageCount 扩展的页数（通常是64的倍数）
 * @return 起始页号
 */
public int extendTablespace(int pageCount) throws IOException {
    fileLock.writeLock().lock();
    try {
        int startPageNo = this.pageCount.get();

        // 批量写入空页
        for (int i = 0; i < pageCount; i++) {
            int pageNo = startPageNo + i;
            ByteBuffer emptyPage = createEmptyPage(pageNo);
            writePage(pageNo, emptyPage);
        }

        this.pageCount.addAndGet(pageCount);
        return startPageNo;
    } finally {
        fileLock.writeLock().unlock();
    }
}

private ByteBuffer createEmptyPage(int pageNo) {
    ByteBuffer page = ByteBuffer.allocate(PAGE_SIZE);
    page.order(ByteOrder.LITTLE_ENDIAN);

    // 初始化 FIL Header
    page.putInt(4, pageNo);                         // page_no
    page.putInt(8, StorageConstants.FIL_NULL);      // prev_page
    page.putInt(12, StorageConstants.FIL_NULL);     // next_page
    page.putInt(34, this.spaceId);                  // space_id

    page.rewind();
    return page;
}
```

#### 2. DiskManager.java

**集成 SpaceManager**：
```java
public class DiskManager {
    private final SpaceManager spaceManager;

    public DiskManager(String dataDir) {
        // ...
        this.spaceManager = new SpaceManagerImpl(this);
    }

    // 保持现有方法不变，SpaceManager 作为高级 API 使用
}
```

#### 3. IndexPage.java

**使用 SegmentManager**：
```java
// 在 IndexPage 中预留的段信息字段（已存在）：
// PAGE_BTR_SEG_LEAF = 74   (叶子段信息，10字节)
// PAGE_BTR_SEG_TOP = 84    (非叶子段信息，10字节)

// 未来 B+Tree 实现时，使用 SegmentManager 分配页面
```

---

### ⏳ 阶段6: 测试验证（待开始）

**测试清单**：

#### 1. 单元测试（5个Test类）

1. **ExtentDescriptorTest.java**
   - Bitmap 操作测试（allocate/free/find）
   - 状态转移测试
   - isFull/isEmpty 测试

2. **SegmentDescriptorTest.java**
   - 碎片页数组操作
   - 链表访问
   - initialize 测试

3. **ExtentManagerTest.java**
   - getExtentDescriptor（跨XDES Page）
   - allocatePageInExtent/freePageInExtent
   - 状态管理

4. **SegmentManagerTest.java**
   - createSegment/dropSegment
   - allocatePageForSegment（3阶段策略）
   - freePage

5. **SpaceManagerTest.java**
   - initializeTablespace
   - allocateExtent/freeExtent
   - extendTablespace

#### 2. 集成测试

**SpaceIntegrationTest.java**：
```java
@Test
public void testFullWorkflow() {
    // 1. 初始化表空间
    // 2. 创建 Segment
    // 3. 分配32个碎片页
    // 4. 分配第一个 Extent（64页）
    // 5. 分配第二个 Extent
    // 6. 验证状态转移（Partial → Full）
    // 7. 删除 Segment，验证空间回收
}
```

#### 3. 性能测试

- 测试分配1000个页面的性能
- 测试跨XDES Page分配的性能
- 测试并发分配的正确性

---

## 四、关键设计决策记录

### 1. 支持超大表空间

**决策**: 支持多个 XDES Page，不限制在256MB

**理由**:
- 用户明确要求支持更大空间
- 生产环境需求

**实现要点**:
- 每256个Extent需要1个XDES Page
- XDES Page位置: page 0, 16384, 32768, ...
- ExtentManager 自动计算 XDES Page 位置

### 2. 保持 DiskManager 不变

**决策**: 新增独立的 SpaceManager 层，DiskManager 只负责物理 I/O

**理由**:
- 职责分离清晰
- 避免破坏现有代码
- 便于测试和维护

### 3. 6阶段实施

**决策**: 分6个阶段自底向上实施

**理由**:
- 风险可控，每阶段有明确交付物
- 便于测试和调试
- 符合软件工程最佳实践

---

## 五、辅助类设计

### 1. FlstBaseNode（链表基节点）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/FlstBaseNode.java`

**职责**: 封装 FLST_BASE_NODE (16字节) 的读写

**方法设计**:
```java
public class FlstBaseNode {
    private final Page page;
    private final int offset;

    public int getLength();
    public void setLength(MiniTransaction mtr, int length);

    public PageId getFirstNode();  // (pageNo, offset)
    public void setFirstNode(MiniTransaction mtr, PageId nodeId);

    public PageId getLastNode();
    public void setLastNode(MiniTransaction mtr, PageId nodeId);

    // 链表操作
    public void addFirst(MiniTransaction mtr, FlstNode node);
    public void addLast(MiniTransaction mtr, FlstNode node);
    public FlstNode popFirst(MiniTransaction mtr);
}
```

### 2. FlstNode（链表节点）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/FlstNode.java`

**职责**: 封装 FLST_NODE (12字节) 的读写

**方法设计**:
```java
public class FlstNode {
    private final Page page;
    private final int offset;

    public PageId getPrevNode();
    public void setPrevNode(MiniTransaction mtr, PageId nodeId);

    public PageId getNextNode();
    public void setNextNode(MiniTransaction mtr, PageId nodeId);
}
```

---

## 六、实现优先级建议

### 高优先级（必须实现）

1. ✅ StorageConstants（已完成）
2. ✅ ExtentState（已完成）
3. ⏳ FlstBaseNode / FlstNode（链表基础设施，优先实现）
4. ⏳ FspHeaderPage（表空间核心）
5. ⏳ XdesPage（支持大表空间）
6. ⏳ ExtentDescriptor（Extent 操作核心）

### 中优先级（核心功能）

7. ⏳ InodePage
8. ⏳ SegmentDescriptor
9. ⏳ ExtentManager
10. ⏳ SegmentManager（3阶段算法）
11. ⏳ SpaceManager

### 低优先级（集成和优化）

12. ⏳ TablespaceFile 修改
13. ⏳ 单元测试
14. ⏳ 集成测试
15. ⏳ 性能优化

---

## 七、下次会话启动指南

### 快速恢复上下文

1. **查看本文档**: `mini-db/docs/segment-extent-implementation-status.md`
2. **查看计划**: `C:\Users\李波\.claude\plans\immutable-inventing-horizon.md`
3. **查看已完成代码**:
   - `StorageConstants.java` (新增常量)
   - `ExtentState.java` (枚举)

### 下一步建议

**选项1：继续阶段1（推荐）**
```
继续实现阶段1的剩余组件：
1. FlstBaseNode / FlstNode（链表基础）
2. FspHeaderPage（FSP_HDR页）
3. XdesPage（XDES页）
4. ExtentDescriptor（Extent描述符）
5. InodePage（INODE页）
6. SegmentDescriptor（Segment描述符）
```

**选项2：先实现链表基础设施**
```
优先实现链表相关的工具类（FlstBaseNode/FlstNode），
因为后续所有页面都依赖链表操作。
```

**选项3：分支实现**
```
创建功能分支 feature/segment-extent，
可以并行开发而不影响主分支。
```

### 启动命令示例

```bash
# 进入项目目录
cd C:/coding/java/self/miniproject/miniproject/mini-db

# 查看实现状态文档
cat docs/segment-extent-implementation-status.md

# 查看待办事项
# （如果使用 Git 分支）
git checkout -b feature/segment-extent

# 继续实现
# 例如: 创建 FlstBaseNode.java
```

---

## 八、参考资料

### 项目内部文档

- **完整实现计划**: `C:\Users\李波\.claude\plans\immutable-inventing-horizon.md`
- **InnoDB空间管理Skill**: `mini-db/.claude/skills/innodb-space-management/`
  - `SKILL.md` - 核心概念
  - `reference.md` - 参考资料
  - `why-need-segment-extent.md` - 设计动机
  - `user-notes-segment-extent-page.md` - 用户笔记

### InnoDB 源码参考

- `storage/innobase/include/fsp0types.h` - 类型定义
- `storage/innobase/include/fsp0fsp.h` - FSP Header
- `storage/innobase/fsp/fsp0fsp.cc` - 空间管理实现
- `storage/innobase/include/fut0lst.h` - 链表实现

### 书籍参考

- 《MySQL技术内幕：InnoDB存储引擎》第4章 - 表空间管理
- 《High Performance MySQL》- Buffer Pool 和空间管理

---

**文档结束**
**下次更新**: 实现阶段1剩余组件后更新本文档
