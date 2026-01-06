# Segment & Extent 空间管理实现状态

> **最后更新时间**: 2026-01-06
> **当前阶段**: 阶段1 - 基础数据结构（已完成 ✅）
> **最新完成**: 所有基础数据结构实现及完整测试覆盖

---

## 一、总体进度概览

```
[████████████████████████████] 100% 阶段1完成

✅ 阶段0: 规划设计（已完成）
✅ 阶段1: 基础数据结构（已完成 - 7/7 实现类 + 7/7 测试类）
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

### ✅ 阶段1: 基础数据结构（已完成 - 7/7）

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

### ✅ 辅助类实现（已完成 - 2/2）

#### 1. FlstNode（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/FlstNode.java`

**核心职责**：
- 封装 FLST_NODE (12字节) 的读写操作
- 管理双向链表节点的前驱和后继指针
- 提供节点状态查询（isHead, isTail, isIsolated）

**物理结构**（12字节）：
```
Offset  Size  Field
------  ----  -----
0       4     Prev Page Number - 前一个节点所在页号
4       2     Prev Offset - 前一个节点在页内的偏移
6       4     Next Page Number - 后一个节点所在页号
10      2     Next Offset - 后一个节点在页内的偏移
```

**核心方法**：
```java
// 前驱节点访问
int getPrevPageNo()
int getPrevOffset()
PageId getPrevNode()
void setPrevNode(MiniTransaction mtr, int pageNo, int nodeOffset)

// 后继节点访问
int getNextPageNo()
int getNextOffset()
PageId getNextNode()
void setNextNode(MiniTransaction mtr, int pageNo, int nodeOffset)

// 初始化和状态查询
void initialize(MiniTransaction mtr)
boolean hasPrev()
boolean hasNext()
boolean isIsolated()

// 节点移除
void remove(MiniTransaction mtr, Page prevPage, int prevOffset,
            Page nextPage, int nextOffset)
```

**测试覆盖**：✅ FlstNodeTest.java (16个测试用例)
- 构造函数参数验证
- 初始化测试
- 前驱/后继节点读写
- 状态查询（isHead, isTail, isIsolated）
- 双节点链表连接

#### 2. FlstBaseNode（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/FlstBaseNode.java`

**核心职责**：
- 封装 FLST_BASE_NODE (16字节) 的读写操作
- 管理双向链表的头部元数据（长度、首节点、尾节点）
- 提供链表操作（addFirst, addLast, removeFirst）

**物理结构**（16字节）：
```
Offset  Size  Field
------  ----  -----
0       4     Length - 链表中节点的数量
4       4     First Page Number - 首节点所在页号
8       2     First Offset - 首节点在页内的偏移
10      4     Last Page Number - 尾节点所在页号
14      2     Last Offset - 尾节点在页内的偏移
```

**核心方法**：
```java
// 长度管理
int getLength()
void setLength(MiniTransaction mtr, int length)
boolean isEmpty()

// 首节点访问
PageId getFirstNode()
int getFirstNodeOffset()
void setFirstNode(MiniTransaction mtr, int pageNo, int nodeOffset)

// 尾节点访问
PageId getLastNode()
int getLastNodeOffset()
void setLastNode(MiniTransaction mtr, int pageNo, int nodeOffset)

// 链表操作
void initialize(MiniTransaction mtr)
void addFirst(MiniTransaction mtr, Page nodePage, int nodeOffset)
void addLast(MiniTransaction mtr, Page nodePage, int nodeOffset)
PageId removeFirst(MiniTransaction mtr)
```

**实现要点**：
- addFirst/addLast: 自动更新旧节点的指针，保持链表一致性
- removeFirst: 清除旧首节点的指针，更新新首节点
- 空链表和单节点链表的特殊处理

**测试覆盖**：✅ FlstBaseNodeTest.java (15个测试用例)
- 构造函数和初始化
- 长度管理
- 首节点/尾节点读写
- addFirst 到空链表和非空链表
- addLast 到空链表和非空链表
- removeFirst 从空、单节点、多节点链表
- 混合操作（addFirst + addLast + removeFirst）

**常量补充**：✅ StorageConstants.java
- 新增 FLST_BASE_NODE 和 FLST_NODE 字段偏移常量
- FLST_LEN, FLST_FIRST_PAGE_NO, FLST_FIRST_OFFSET
- FLST_LAST_PAGE_NO, FLST_LAST_OFFSET
- FLST_PREV_PAGE_NO, FLST_PREV_OFFSET
- FLST_NEXT_PAGE_NO, FLST_NEXT_OFFSET

### ✅ 核心数据结构实现（已完成 - 5/5）

#### 3. FspHeaderPage（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/FspHeaderPage.java`

**核心职责**：
- 封装 FSP_HDR Page (page 0) 的读写操作
- 提供 FSP Header 各字段的 getter/setter
- 管理6个Extent链表（FREE, FREE_FRAG, FULL_FRAG, SEG_INODES_FREE, SEG_INODES_FULL）
- 提供访问 XDES Array 的方法（前256个Extent）

**核心方法**：
```java
// 构造函数（3种）
FspHeaderPage(PageId pageId)
FspHeaderPage(Page page)
FspHeaderPage(PageId pageId, ByteBuffer buffer)

// FSP Header 字段访问
int getFspSpaceId() / setFspSpaceId(MiniTransaction mtr, int)
int getSize() / setSize(MiniTransaction mtr, int)
int getFreeLimit() / setFreeLimit(MiniTransaction mtr, int)
long getSegId() / setSegId(MiniTransaction mtr, long)
long allocateSegmentId(MiniTransaction mtr)  // 原子递增

// 链表访问（返回 FlstBaseNode）
FlstBaseNode getFreeExtentList()
FlstBaseNode getFreeFragExtentList()
FlstBaseNode getFullFragExtentList()
FlstBaseNode getSegInodesFreeList()
FlstBaseNode getSegInodesFullList()

// XDES Array 访问
int getXdesEntryOffset(int extentNo)
ExtentDescriptor getXdesEntry(int extentNo)

// 初始化
void initialize(MiniTransaction mtr, int spaceId)
void initExtentLists(MiniTransaction mtr)
```

**测试覆盖**：✅ FspHeaderPageTest.java (20+个测试用例)
- 三种构造函数验证（包括类型验证）
- FSP Header 字段的 get/set 操作
- 原子 Segment ID 分配
- 六个链表的访问方法
- XDES Array 访问（0-255范围验证）
- 初始化和链表初始化
- 完整工作流集成测试

#### 4. XdesPage（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/XdesPage.java`

**核心职责**：
- 封装独立的 XDES Page (page 16384, 32768, ...) 的读写
- 支持超大表空间（超过256MB）
- 提供静态工具方法计算 XDES Page 位置

**核心方法**：
```java
// 构造函数（3种，带页号验证）
XdesPage(PageId pageId)
XdesPage(Page page)
XdesPage(PageId pageId, ByteBuffer buffer)

// 静态工具方法
static int getXdesPageNo(int extentNo)
static int getXdesEntryOffset(int extentNo)
static boolean isXdesPage(int pageNo)
static int[] getExtentRange(int extentNo)

// XDES Entry 访问
ExtentDescriptor getXdesEntry(int extentNo)
int[] getLocalExtentRange()

// 初始化
void initialize(MiniTransaction mtr)
void initializeRange(MiniTransaction mtr, int startExtentNo, int endExtentNo)
```

**测试覆盖**：✅ XdesPageTest.java (25+个测试用例)
- 构造函数验证（页号必须是16384的倍数，不能为0）
- 静态工具方法测试（getXdesPageNo, getXdesEntryOffset, isXdesPage, getExtentRange）
- XDES Entry 访问（验证属于正确页面）
- Local extent 范围计算
- 初始化和批量初始化
- 边界条件测试
- 完整工作流集成测试

#### 5. InodePage（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/InodePage.java`

**核心职责**：
- 封装 INODE Page 的读写操作
- 管理85个 INODE Entry
- 提供 Segment 的元数据访问
- 提供 Entry 查找和分配功能

**核心方法**：
```java
// 构造函数（3种）
InodePage(PageId pageId)
InodePage(Page page)
InodePage(PageId pageId, ByteBuffer buffer)

// INODE Page Header
FlstNode getListNode()

// INODE Entry 访问
int getInodeEntryOffset(int entryIndex)
SegmentDescriptor getInodeEntry(int entryIndex)

// Entry 查找
int findFreeEntry()
int findEntryBySegmentId(long segmentId)
int getUsedEntryCount()
boolean isFull()
boolean isEmpty()

// Entry 分配和释放
int allocateEntry(MiniTransaction mtr, long segmentId)
void freeEntry(MiniTransaction mtr, int entryIndex)
void initEntry(MiniTransaction mtr, int entryIndex)

// 初始化
void initialize(MiniTransaction mtr)
void initAllEntries(MiniTransaction mtr)

// 统计
long[] getAllSegmentIds()
```

**测试覆盖**：✅ InodePageTest.java (30+个测试用例)
- 构造函数验证（包括类型验证）
- INODE Page Header 访问（链表节点）
- INODE Entry 访问和偏移计算（0-84范围验证）
- Entry 查找（空闲、按ID查找）
- Entry 分配和释放
- 统计和状态查询（isFull, isEmpty, getUsedEntryCount）
- 初始化操作
- 边界条件测试
- 完整分配释放工作流集成测试

#### 6. ExtentDescriptor（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/ExtentDescriptor.java`

**核心职责**：
- 封装 XDES Entry (40字节) 的读写操作
- 提供 Bitmap 操作（分配/释放页面）
- 管理 Extent 状态
- 提供空闲页查找

**核心方法**：
```java
// 构造函数
ExtentDescriptor(Page page, int offset, int extentNo)

// 基本字段访问
long getSegmentId() / setSegmentId(MiniTransaction mtr, long)
ExtentState getState() / setState(MiniTransaction mtr, ExtentState)
int getExtentNo()
int getStartPageNo()

// Bitmap 操作
boolean isPageFree(int pageOffset)
void allocatePage(MiniTransaction mtr, int pageOffset)
void freePage(MiniTransaction mtr, int pageOffset)
void initBitmap(MiniTransaction mtr)

// 空闲页查找
int findFreePage()

// 状态查询
boolean isFull()
boolean isEmpty()
int getFreePageCount()
int getUsedPageCount()

// 链表节点访问
FlstNode getListNode()
```

**测试覆盖**：✅ ExtentDescriptorTest.java (24+个测试用例)
- 构造函数参数验证
- Segment ID 和状态管理
- Bitmap 操作（分配、释放、重复操作检测）
- 空闲页查找（全空、部分空、全满、有间隙）
- 状态查询（isEmpty, isFull, getUsedPageCount, getFreePageCount）
- 链表节点访问
- 边界条件测试（页偏移0-63）

#### 7. SegmentDescriptor（已完成 ✅）

**文件路径**: `mini-db/src/main/java/cn/zhangyis/minidb/storage/space/SegmentDescriptor.java`

**核心职责**：
- 封装 INODE Entry (192字节) 的读写操作
- 管理3个 Extent 链表（FREE, NOT_FULL, FULL）
- 管理碎片页数组（32个页号）
- 提供 Segment 初始化和清除功能

**核心方法**：
```java
// 构造函数
SegmentDescriptor(Page page, int offset)

// 基本字段访问
long getSegmentId() / setSegmentId(MiniTransaction mtr, long)
int getNotFullNUsed() / setNotFullNUsed(MiniTransaction mtr, int)
int getMagicNumber()

// 碎片页数组操作
int getFragArrayPage(int index)
void setFragArrayPage(MiniTransaction mtr, int index, int pageNo)
int findFreeFragSlot()
int getFragUsedCount()

// 链表访问
FlstBaseNode getFreeList()
FlstBaseNode getNotFullList()
FlstBaseNode getFullList()

// 初始化和清除
void initialize(MiniTransaction mtr, long segmentId)
void clear(MiniTransaction mtr)

// 统计
int getTotalExtentCount()
int getEstimatedPageCount()
```

**测试覆盖**：✅ SegmentDescriptorTest.java (20+个测试用例)
- 构造函数参数验证
- Segment ID 和字段管理
- NOT_FULL_N_USED 计数器
- 碎片页数组操作（getFragArrayPage, setFragArrayPage, findFreeFragSlot）
- 三个链表访问（Free, NotFull, Full）
- Initialize 和 clear 操作
- 统计方法（getTotalExtentCount, getEstimatedPageCount）
- 魔数验证

### ✅ 测试基础设施（已完成）

**BaseStorageTest.java**：✅ 测试基类
- 自动创建和销毁 DiskManager、BufferPool
- 自动调用 diskManager.createTablespace()
- 提供统一的测试环境（SPACE_ID, SPACE_NAME等常量）
- 所有空间管理测试继承此类

---

## 三、待实现的工作

### ⏳ 阶段2: ExtentManager（下一步）

**说明**：阶段1（基础数据结构）已全部完成，现在可以开始实现阶段2。

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

### ✅ 已完成（阶段1）

1. ✅ StorageConstants（已完成）
2. ✅ ExtentState（已完成）
3. ✅ FlstBaseNode / FlstNode（链表基础设施，已完成）
4. ✅ FspHeaderPage（表空间核心，已完成）
5. ✅ XdesPage（支持大表空间，已完成）
6. ✅ ExtentDescriptor（Extent 操作核心，已完成）
7. ✅ InodePage（已完成）
8. ✅ SegmentDescriptor（已完成）
9. ✅ 所有单元测试（BaseStorageTest + 7个测试类）

### 高优先级（阶段2 - 下一步）

10. ⏳ ExtentManager（接口 + 实现）
11. ⏳ ExtentManagerTest（单元测试）

### 中优先级（阶段3-4）

12. ⏳ SegmentManager（3阶段分配算法）
13. ⏳ SegmentManagerTest
14. ⏳ SpaceManager
15. ⏳ SpaceManagerTest

### 低优先级（集成和优化）

16. ⏳ TablespaceFile 修改（扩展表空间）
17. ⏳ DiskManager 集成
18. ⏳ 集成测试（SpaceIntegrationTest）
19. ⏳ 性能测试

---

## 七、下次会话启动指南

### 快速恢复上下文

1. **查看本文档**: `mini-db/docs/segment-extent-implementation-status.md`
2. **查看计划**: `C:\Users\李波\.claude\plans\immutable-inventing-horizon.md`
3. **查看已完成代码**（阶段1）:
   - `StorageConstants.java` - 所有常量定义
   - `ExtentState.java` - Extent状态枚举
   - `FlstNode.java` / `FlstBaseNode.java` - 链表基础设施
   - `FspHeaderPage.java` - FSP_HDR Page封装
   - `XdesPage.java` - XDES Page封装
   - `InodePage.java` - INODE Page封装
   - `ExtentDescriptor.java` - Extent描述符
   - `SegmentDescriptor.java` - Segment描述符
   - 所有测试类：7个Test类，共120+测试用例

### 🎉 阶段1完成总结

**已实现组件**：
- ✅ 2个基础类（StorageConstants, ExtentState）
- ✅ 2个辅助类（FlstNode, FlstBaseNode）
- ✅ 3个Page类（FspHeaderPage, XdesPage, InodePage）
- ✅ 2个描述符类（ExtentDescriptor, SegmentDescriptor）
- ✅ 1个测试基类（BaseStorageTest）
- ✅ 7个单元测试类（120+测试用例）

**测试覆盖率**：
- 所有核心方法均有单元测试
- 包含边界条件和异常情况测试
- 包含集成工作流测试

### 下一步建议

**选项1：开始阶段2 - ExtentManager（推荐）**
```
实现 ExtentManager 接口和实现类：
1. 创建 ExtentManager.java 接口
2. 实现 ExtentManagerImpl.java
3. 核心功能：
   - getExtentDescriptor(spaceId, extentNo)
   - allocatePageInExtent(extent)
   - freePageInExtent(extent, pageOffset)
   - initializeExtent(extent)
4. 创建 ExtentManagerTest.java（单元测试）
```

**选项2：完善文档和代码审查**
```
在进入阶段2之前：
1. 代码审查：检查所有实现是否符合规范
2. 文档完善：添加更多使用示例
3. 性能分析：分析Bitmap操作的性能
```

**选项3：分支管理**
```
创建功能分支进行开发：
git checkout -b feature/extent-manager
```

### 启动命令示例

```bash
# 进入项目目录
cd C:/coding/java/self/miniproject/miniproject/mini-db

# 查看实现状态文档
cat docs/segment-extent-implementation-status.md

# 运行阶段1的所有单元测试
gradle :mini-db:test --tests "*space*"

# 查看测试覆盖率
gradle :mini-db:test :mini-db:jacocoTestReport

# 创建阶段2开发分支（可选）
git checkout -b feature/extent-manager

# 开始实现 ExtentManager
# 创建接口文件: mini-db/src/main/java/cn/zhangyis/minidb/storage/space/ExtentManager.java
# 创建实现文件: mini-db/src/main/java/cn/zhangyis/minidb/storage/space/ExtentManagerImpl.java
# 创建测试文件: mini-db/src/test/java/cn/zhangyis/minidb/storage/space/ExtentManagerTest.java
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

---

## 九、更新历史

### 2026-01-06 - 阶段1完成 ✅

**本次更新内容**：
- ✅ 完成所有7个核心数据结构的实现
- ✅ 完成所有7个单元测试类（120+测试用例）
- ✅ 完成 BaseStorageTest 测试基类
- ✅ 更新实现状态文档，阶段1进度从30%提升至100%

**新增文件清单**（本次会话）：
1. `ExtentDescriptorTest.java` - 24个测试用例
2. `SegmentDescriptorTest.java` - 20个测试用例
3. `FspHeaderPageTest.java` - 20个测试用例
4. `XdesPageTest.java` - 25个测试用例
5. `InodePageTest.java` - 30个测试用例

**测试覆盖**：
- 构造函数验证
- 字段的 get/set 操作
- Bitmap 和数组操作
- 链表节点访问
- 初始化和状态管理
- 查找和分配算法
- 边界条件和异常处理
- 完整工作流集成测试

**下一步**：开始实现阶段2 - ExtentManager

---

**文档结束**
**下次更新**: 实现阶段2（ExtentManager）后更新本文档
