# Segment & Extent 空间管理实现状态

> **最后更新时间**: 2026-01-13
> **当前阶段**: 阶段4完成（SpaceManager）✅
> **最新完成**: ExtentManager、SegmentManager、SpaceManager 完整实现及测试

---

## 一、总体进度概览

```
[████████████████████████████████████] 80% 阶段1-4完成

✅ 阶段0: 规划设计（已完成）
✅ 阶段1: 基础数据结构（已完成 - 7/7 实现类 + 7/7 测试类）
✅ 阶段2: ExtentManager（已完成 - 接口 + 实现 + 测试）
✅ 阶段3: SegmentManager（已完成 - 接口 + 实现 + 测试）
✅ 阶段4: SpaceManager（已完成 - 接口 + 实现 + 测试）
⏳ 阶段5: 集成修改（待开始）
⏳ 阶段6: 测试验证（待开始）
```

---

## 二、已完成的工作

### ✅ 阶段1: 基础数据结构（已完成）

详细内容见之前版本的文档，包括：
- **StorageConstants** - 所有常量定义
- **ExtentState** - Extent 状态枚举
- **FlstNode / FlstBaseNode** - 链表基础设施
- **FspHeaderPage** - FSP_HDR Page 封装
- **XdesPage** - XDES Page 封装
- **InodePage** - INODE Page 封装
- **ExtentDescriptor** - Extent 描述符（40字节）
- **SegmentDescriptor** - Segment 描述符（192字节）

所有类均有完整的单元测试（120+ 测试用例）。

---

### ✅ 阶段2: ExtentManager（已完成）

**文件路径**：
- `ExtentManager.java` - 接口
- `ExtentManagerImpl.java` - 实现
- `ExtentManagerTest.java` - 测试（24个测试用例）

#### 核心功能

**1. getExtentDescriptor() - 获取 Extent 描述符**
```java
ExtentDescriptor getExtentDescriptor(MiniTransaction mtr, int spaceId, int extentNo);
```
- 自动计算 XDES Page 位置（page 0, 16384, 32768, ...）
- 支持跨 XDES Page 的 Extent 访问
- 返回完整的 ExtentDescriptor 对象

**实现要点**：
```java
// 1. 计算 XDES Page 位置
int xdesPageNo = (extentNo / 256) * 16384;

// 2. 加载正确的 XDES Page
if (xdesPageNo == 0) {
    page = new FspHeaderPage(mtr.getPage(...));
} else {
    page = new XdesPage(mtr.getPage(...));
}

// 3. 计算 XDES Entry 偏移
int offset = ... // 根据 page 类型计算

// 4. 返回 ExtentDescriptor
return new ExtentDescriptor(page, offset, extentNo);
```

**2. allocatePageInExtent() - 在 Extent 中分配页面**
```java
int allocatePageInExtent(MiniTransaction mtr, ExtentDescriptor extent);
```
- 从 Extent 的 bitmap 中查找空闲页
- 标记页面为已使用
- 返回页面在 Extent 中的偏移（0-63）

**3. freePageInExtent() - 释放 Extent 中的页面**
```java
void freePageInExtent(MiniTransaction mtr, ExtentDescriptor extent, int pageOffset);
```
- 在 bitmap 中标记页面为空闲
- 支持状态转移（FULL → NOT_FULL）

**4. 其他方法**
- `initializeExtent()` - 初始化 Extent 为 FREE 状态
- `setExtentState()` - 设置 Extent 状态
- `getUsedPageCount()` / `getFreePageCount()` - 统计信息
- `isEmpty()` / `isFull()` - 状态查询

---

### ✅ 阶段3: SegmentManager（已完成）

**文件路径**：
- `SegmentManager.java` - 接口
- `SegmentManagerImpl.java` - 实现
- `SegmentManagerTest.java` - 测试（17个测试用例）

#### 核心功能

**1. createSegment() - 创建 Segment**
```java
long createSegment(MiniTransaction mtr, int spaceId);
```
- 分配唯一的 Segment ID（从 FSP_SEG_ID 原子递增）
- 在 INODE Page 中分配 Entry
- 初始化 3 个 Extent 链表（FREE, NOT_FULL, FULL）
- 初始化碎片页数组（32个槽位）
- 如果 INODE Page 已满，移动到 INODES_FULL 链表

**实现流程**：
```
1. 加载 FSP Header，获取下一个 Segment ID
2. 查找或创建有空闲 Entry 的 INODE Page
3. 在 INODE Page 中分配 Entry（调用 findFreeEntry()）
4. 初始化 INODE Entry（设置 segmentId, 初始化链表和碎片数组）
5. 如果 INODE Page 已满（85个Entry都已分配），移到 INODES_FULL 链表
```

**2. allocatePageForSegment() - 3阶段分配策略**
```java
PageId allocatePageForSegment(MiniTransaction mtr, int spaceId, long segmentId);
```

**3阶段分配策略**（简化版，碎片页暂时跳过）：
```
阶段1: 碎片页分配（前32页）
  └─ 需要 SpaceManager.allocateFragPage()（已实现）

阶段2: Partial Extent 分配
  └─ 从 NOT_FULL 链表获取 Extent
  └─ 分配一个页面，如果 Extent 变满，移到 FULL 链表

阶段3: Free Extent 分配
  └─ 从 FREE 链表获取 Extent，分配第一个页面
  └─ 移动到 NOT_FULL 链表
  └─ 如果 FREE 为空，从 SpaceManager 分配新 Extent
```

**当前实现**：主要实现了阶段2和阶段3，阶段1（碎片页）需要 SpaceManager 支持。

**3. allocateExtentForSegment() - 分配 Extent 给 Segment**
```java
ExtentDescriptor allocateExtentForSegment(MiniTransaction mtr, int spaceId, long segmentId);
```
- 从表空间 FREE 链表分配 Extent
- 设置 Extent 的 segmentId 和状态（FSEG_FREE）
- 加入 Segment 的 FREE 链表

**4. dropSegment() - 删除 Segment**
```java
void dropSegment(MiniTransaction mtr, int spaceId, long segmentId);
```
- 释放碎片页数组中的所有页面（TODO）
- 释放 3 个链表中的所有 Extent，归还到表空间 FREE 链表
- 清空 INODE Entry

**5. 其他方法**
- `getSegmentDescriptor()` - 查找 Segment（遍历 INODES_FREE 和 INODES_FULL 链表）
- `getStatistics()` - 获取 Segment 统计信息
- `freePage()` - 释放页面（支持碎片页和 Extent 页）

#### 关键实现细节

**Extent 链表迁移**：
```
FREE → NOT_FULL: 分配第一个页面时
NOT_FULL → FULL: 分配完最后一个空闲页时
FULL → NOT_FULL: 释放页面后有空闲页时
```

**INODE Page 管理**：
- 首次创建 Segment 时，创建第一个 INODE Page（page 2）
- INODE Page 有 85 个 Entry，每个 192 字节
- 满了之后从 INODES_FREE 移到 INODES_FULL
- `findFreeEntry()` 方法已修复（之前总是返回 0）

---

### ✅ 阶段4: SpaceManager（已完成）

**文件路径**：
- `SpaceManager.java` - 接口
- `SpaceManagerImpl.java` - 实现
- `SpaceManagerTest.java` - 测试（17个测试用例）

#### 核心功能

**1. initializeTablespace() - 初始化表空间**
```java
void initializeTablespace(MiniTransaction mtr, int spaceId);
```

**初始化流程**：
```
1. 初始化 Page 0 (FSP_HDR)
   - 设置 spaceId, size, freeLimit, nextSegmentId
   - 初始化 6 个 Extent 链表（FREE, FREE_FRAG, FULL_FRAG, INODES_FREE, INODES_FULL）
   - 初始化 Extent 0 的 XDES Entry（状态设为 FSEG，系统保留）

2. 扩展表空间到第一个 Extent（64页）
   - 调用 extendTablespace(mtr, spaceId, 1)
   - 将 Extent 1 加入 FREE 链表

3. 创建 Page 1（占位，暂未使用）

4. 创建 Page 2（第一个 INODE Page）
   - 初始化 85 个 INODE Entry
   - 加入 INODES_FREE 链表
```

**2. allocateExtent() - 分配 Extent**
```java
ExtentDescriptor allocateExtent(MiniTransaction mtr, int spaceId);
```
- 从表空间 FREE 链表分配
- 如果 FREE 为空，自动扩展表空间（1个 Extent）
- 返回 ExtentDescriptor（调用者需设置 segmentId 和 state）

**3. freeExtent() - 释放 Extent**
```java
void freeExtent(MiniTransaction mtr, ExtentDescriptor extent);
```
- 重新初始化 Extent（清空 segmentId, 重置 bitmap）
- 设置状态为 FREE
- 加入表空间 FREE 链表

**4. allocateFragPage() - 分配碎片页**
```java
PageId allocateFragPage(MiniTransaction mtr, int spaceId);
```

**碎片页分配流程**：
```
1. 检查 FREE_FRAG 链表
   - 如果为空，从 FREE 分配一个 Extent，设置为 FREE_FRAG 状态
   - 如果不为空，使用第一个 Extent

2. 在 Extent 中分配页面（调用 extentManager.allocatePageInExtent()）

3. 检查 Extent 是否变满
   - 如果满了，从 FREE_FRAG 移到 FULL_FRAG
```

**状态转移**：
```
FREE → FREE_FRAG: 首次用作碎片页时
FREE_FRAG → FULL_FRAG: 所有64页分配完时
FULL_FRAG → FREE_FRAG: 释放页面后有空闲页时
FREE_FRAG → FREE: 所有页面都被释放时
```

**5. freeFragPage() - 释放碎片页**
```java
void freeFragPage(MiniTransaction mtr, PageId pageId);
```
- 确定页面所属的 Extent
- 在 Extent 中释放页面
- 处理状态转移（FULL_FRAG → FREE_FRAG → FREE）

**6. extendTablespace() - 扩展表空间**
```java
void extendTablespace(MiniTransaction mtr, int spaceId, int extentCount);
```

**扩展策略（Lazy Allocation）**：
```
1. 只创建必需的 XDES Page（16384, 32768, ...）
2. 其他页面采用延迟分配（在实际使用时才通过 mtr.newPage() 创建）
3. 初始化新 Extent 为 FREE 状态，加入 FREE 链表
4. 更新 FSP_SIZE 和 FSP_FREE_LIMIT（逻辑大小）
```

**关键修复**：
- 跳过已分配的 Extent（避免重新初始化 FREE_FRAG/FULL_FRAG Extent）
- 避免在单个 MTR 中创建过多页面（导致 Buffer Pool 耗尽）

**7. getStatistics() - 获取统计信息**
```java
SpaceStatistics getStatistics(MiniTransaction mtr, int spaceId);
```

返回信息：
- 总页数、已使用页数、使用率
- FREE / FREE_FRAG / FULL_FRAG 链表长度
- 下一个 Segment ID

---

## 三、测试覆盖

### 单元测试汇总

| 模块 | 测试类 | 测试用例数 | 覆盖内容 |
|------|--------|-----------|----------|
| 基础数据结构 | 7个 | 120+ | 构造函数、字段访问、Bitmap、链表、初始化 |
| ExtentManager | 1个 | 24 | getExtentDescriptor（跨XDES Page）、页面分配/释放、状态管理 |
| SegmentManager | 1个 | 17 | createSegment、dropSegment、Extent分配、页面分配、统计 |
| SpaceManager | 1个 | 17 | 初始化表空间、Extent分配/释放、碎片页分配/释放、扩展表空间 |
| **总计** | **10个** | **178+** | **完整覆盖** |

### 关键Bug修复记录

1. **Buffer Pool 耗尽问题**
   - **问题**：在单个 MTR 中创建过多页面（256页），超过 Buffer Pool 容量（64页）
   - **修复**：采用延迟分配策略，只创建 XDES Page
   - **位置**：`ExtentManagerTest.getExtentDescriptor_shouldReturnDescriptorFromPage16384_forExtent256`

2. **InodePage.findFreeEntry() 总是返回 0**
   - **问题**：stub 实现导致所有 Segment 覆盖同一个 Entry
   - **修复**：实现正确的遍历逻辑，找到第一个未分配的 Entry
   - **位置**：`InodePage.java:325`

3. **extendTablespace() 重新初始化已分配 Extent**
   - **问题**：扩展时会覆盖 FREE_FRAG/FULL_FRAG 状态的 Extent
   - **修复**：检查 Extent 当前状态，跳过已分配的 Extent
   - **位置**：`SpaceManagerImpl.extendTablespace():288-300`

---

## 四、架构设计总结

### 三层架构

```
┌─────────────────────────────────────────┐
│         SpaceManager                    │  表空间整体管理
│  - initializeTablespace()               │  - Extent 分配/释放
│  - allocateFragPage()                   │  - 碎片页管理
│  - extendTablespace()                   │  - 表空间扩展
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│        SegmentManager                   │  Segment 粒度管理
│  - createSegment()                      │  - 3阶段页面分配
│  - allocatePageForSegment()             │  - Extent 链表管理
│  - allocateExtentForSegment()           │  - INODE Page 管理
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│        ExtentManager                    │  Extent 粒度管理
│  - getExtentDescriptor()                │  - 跨 XDES Page 定位
│  - allocatePageInExtent()               │  - Bitmap 操作
│  - freePageInExtent()                   │  - 状态转移
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│     物理层（Page封装）                   │
│  FspHeaderPage, XdesPage, InodePage     │  - 页面读写封装
│  ExtentDescriptor, SegmentDescriptor    │  - 数据结构操作
└─────────────────────────────────────────┘
```

### Extent 状态转移图

```
表空间 FREE 路径：
FREE ─────────────────────> FREE_FRAG ─────────> FULL_FRAG
  ↑                              ↓                     ↓
  └──────────────────────────────┴─────────────────────┘
     (所有页面释放后回到 FREE)

Segment 路径：
FREE ─────────> FSEG_FREE ─────────> FSEG (NOT_FULL) ─────────> FSEG (FULL)
  ↑                                         ↓                         ↓
  └─────────────────────────────────────────┴─────────────────────────┘
     (Segment 删除后，Extent 归还到 FREE)
```

### 页面分配策略

**Segment 的 3阶段分配**（`SegmentManager.allocatePageForSegment()`）：

```
阶段1: 碎片页（前32页）
├─ 优点：减少空间浪费（小表不占用完整 Extent）
├─ 实现：从 SpaceManager.allocateFragPage() 分配
└─ 记录：保存在 INODE Entry 的碎片页数组（32个槽位）

阶段2: Partial Extent（第33+页）
├─ 来源：Segment 的 NOT_FULL 链表
├─ 优点：最大化空间利用率
└─ 状态：分配完最后一页后移到 FULL 链表

阶段3: Free Extent（NOT_FULL 为空时）
├─ 来源：Segment 的 FREE 链表
├─ 首次分配：从表空间 FREE 链表获取新 Extent
└─ 状态：分配第一页后移到 NOT_FULL 链表
```

---

## 五、待实现的工作

### ⏳ 阶段5: 集成修改

#### 1. SegmentManagerImpl 中的 TODO

**需要与 SpaceManager 集成**：

```java
// Line 382-385: allocateFragmentPageForSegment()
private PageId allocateFragmentPageForSegment(...) {
    // TODO: 从表空间 FREE_FRAG 链表分配页面
    // 需要 SpaceManager.allocateFragPage()
    return null;  // 当前返回 null，导致阶段1跳过
}
```

**修复方案**：
```java
private PageId allocateFragmentPageForSegment(MiniTransaction mtr, int spaceId,
                                               SegmentDescriptor segment) {
    // 使用 SpaceManager 分配碎片页
    PageId fragPage = spaceManager.allocateFragPage(mtr, spaceId);
    return fragPage;
}
```

**其他 TODO**：
- Line 93-96: `dropSegment()` 中释放碎片页（需要 `SpaceManager.freeFragPage()`）
- Line 156-157: `freePage()` 中释放表空间碎片页

#### 2. 构造函数注入 SpaceManager

**当前状态**：
```java
public class SegmentManagerImpl implements SegmentManager {
    private final BufferPool bufferPool;
    private final ExtentManager extentManager;
    // 缺少 SpaceManager
}
```

**修改方案**：
```java
public class SegmentManagerImpl implements SegmentManager {
    private final BufferPool bufferPool;
    private final ExtentManager extentManager;
    private final SpaceManager spaceManager;  // 新增

    public SegmentManagerImpl(BufferPool bufferPool,
                              ExtentManager extentManager,
                              SpaceManager spaceManager) {
        this.bufferPool = bufferPool;
        this.extentManager = extentManager;
        this.spaceManager = spaceManager;
    }
}
```

#### 3. 更新 SegmentManagerTest

需要在测试中创建 SpaceManager 并注入：
```java
@BeforeEach
void setUpManagers() {
    extentManager = new ExtentManagerImpl(bufferPool);
    spaceManager = new SpaceManagerImpl(bufferPool, extentManager);
    segmentManager = new SegmentManagerImpl(bufferPool, extentManager, spaceManager);
}
```

---

### ⏳ 阶段6: 完整集成测试

#### 1. SpaceIntegrationTest

创建完整的工作流测试：
```java
@Test
void testCompleteWorkflow() throws Exception {
    // 1. 初始化表空间
    spaceManager.initializeTablespace(mtr, SPACE_ID);

    // 2. 创建 Segment
    long segId = segmentManager.createSegment(mtr, SPACE_ID);

    // 3. 分配32个碎片页（阶段1）
    for (int i = 0; i < 32; i++) {
        PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segId);
        assertNotNull(page);
    }

    // 4. 分配第一个 Extent（阶段3）
    PageId page33 = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segId);
    int extent33 = page33.getPageNo() / 64;

    // 5. 验证 Extent 在 NOT_FULL 链表
    SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);
    assertEquals(1, segment.getNotFullList().getLength());

    // 6. 分配完整个 Extent（64页）
    for (int i = 1; i < 64; i++) {
        segmentManager.allocatePageForSegment(mtr, SPACE_ID, segId);
    }

    // 7. 验证 Extent 移到 FULL 链表
    assertEquals(0, segment.getNotFullList().getLength());
    assertEquals(1, segment.getFullList().getLength());

    // 8. 删除 Segment，验证空间回收
    int freeExtentsBefore = spaceManager.getStatistics(mtr, SPACE_ID).getFreeExtents();
    segmentManager.dropSegment(mtr, SPACE_ID, segId);
    int freeExtentsAfter = spaceManager.getStatistics(mtr, SPACE_ID).getFreeExtents();

    assertTrue(freeExtentsAfter > freeExtentsBefore, "Extents should be freed");
}
```

#### 2. 跨 XDES Page 测试

测试超大表空间（超过 256 个 Extent）：
```java
@Test
void testLargeTablespace() throws Exception {
    spaceManager.initializeTablespace(mtr, SPACE_ID);

    // 扩展到 Extent 300（需要创建 page 16384 的 XDES Page）
    spaceManager.extendTablespace(mtr, SPACE_ID, 300);

    // 验证 Extent 256 可以正确定位（位于 page 16384）
    ExtentDescriptor ext256 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 256);
    assertNotNull(ext256);
    assertEquals(256, ext256.getExtentNo());
}
```

#### 3. 性能测试

```java
@Test
void testPerformance_allocate1000Pages() throws Exception {
    spaceManager.initializeTablespace(mtr, SPACE_ID);
    long segId = segmentManager.createSegment(mtr, SPACE_ID);

    long startTime = System.currentTimeMillis();

    for (int i = 0; i < 1000; i++) {
        segmentManager.allocatePageForSegment(mtr, SPACE_ID, segId);
    }

    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;

    System.out.println("Allocated 1000 pages in " + duration + "ms");
    assertTrue(duration < 5000, "Should allocate 1000 pages within 5 seconds");
}
```

---

## 六、使用示例

### 完整的使用流程

```java
// 1. 创建管理器实例
BufferPool bufferPool = new BufferPool(BUFFER_POOL_SIZE, diskManager);
ExtentManager extentManager = new ExtentManagerImpl(bufferPool);
SpaceManager spaceManager = new SpaceManagerImpl(bufferPool, extentManager);
SegmentManager segmentManager = new SegmentManagerImpl(bufferPool, extentManager, spaceManager);

// 2. 初始化表空间
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    spaceManager.initializeTablespace(mtr, spaceId);
    mtr.commit();
}

// 3. 创建 Segment
long segmentId;
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    segmentId = segmentManager.createSegment(mtr, spaceId);
    mtr.commit();
}

// 4. 分配页面（自动执行3阶段策略）
PageId newPage;
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    newPage = segmentManager.allocatePageForSegment(mtr, spaceId, segmentId);

    // 使用页面...
    Page page = mtr.getPage(newPage);
    // ... 写入数据 ...

    mtr.commit();
}

// 5. 获取统计信息
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    SegmentManager.SegmentStatistics stats =
        segmentManager.getStatistics(mtr, spaceId, segmentId);

    System.out.println("Fragment pages: " + stats.getFragPagesUsed());
    System.out.println("Free extents: " + stats.getFreeExtentCount());
    System.out.println("Not-full extents: " + stats.getNotFullExtentCount());
    System.out.println("Full extents: " + stats.getFullExtentCount());
}

// 6. 删除 Segment（释放所有资源）
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    segmentManager.dropSegment(mtr, spaceId, segmentId);
    mtr.commit();
}
```

---

## 七、关键设计决策

### 1. 延迟分配策略（Lazy Allocation）

**决策**：扩展表空间时只创建 XDES Page，数据页面在使用时才分配

**理由**：
- 避免一次性 pin 过多页面，导致 Buffer Pool 耗尽
- 节省磁盘空间（不预先写入所有页面）
- 符合 InnoDB 的实际实现

**实现**：
- `FSP_SIZE`：逻辑大小（扩展后的目标大小）
- `FSP_FREE_LIMIT`：物理大小（实际已分配的页数）
- 页面通过 `mtr.newPage()` 按需创建

### 2. 3阶段分配策略

**决策**：Segment 的页面分配采用3阶段策略（碎片页 → Partial Extent → Free Extent）

**理由**：
- **阶段1（碎片页）**：小表不浪费空间（32页 = 512KB）
- **阶段2（Partial）**：最大化利用已分配的 Extent
- **阶段3（Free）**：大表可以获取完整 Extent，减少碎片

**权衡**：增加了复杂度，但显著提升空间利用率

### 3. 状态检查防止重新初始化

**决策**：`extendTablespace()` 检查 Extent 当前状态，跳过已分配的 Extent

**理由**：
- 防止覆盖 FREE_FRAG/FULL_FRAG 状态的 Extent
- 支持多次扩展表空间（增量扩展）

**实现**：检查 `extent.getState()`，如果不是 FREE，跳过初始化

---

## 八、更新历史

### 2026-01-13 - 阶段2-4完成 ✅

**本次更新内容**：
- ✅ 完成 ExtentManager（接口 + 实现 + 24个测试）
- ✅ 完成 SegmentManager（接口 + 实现 + 17个测试）
- ✅ 完成 SpaceManager（接口 + 实现 + 17个测试）
- ✅ 修复 Buffer Pool 耗尽问题（延迟分配）
- ✅ 修复 InodePage.findFreeEntry() bug
- ✅ 修复 extendTablespace() 重新初始化已分配 Extent 的问题

**新增文件清单**：
1. `ExtentManager.java` / `ExtentManagerImpl.java` / `ExtentManagerTest.java`
2. `SegmentManager.java` / `SegmentManagerImpl.java` / `SegmentManagerTest.java`
3. `SpaceManager.java` / `SpaceManagerImpl.java` / `SpaceManagerTest.java`

**测试覆盖**：
- ExtentManager: 24个测试（跨XDES Page、页面分配/释放、状态管理）
- SegmentManager: 17个测试（Segment创建/删除、Extent分配、页面分配）
- SpaceManager: 17个测试（表空间初始化、Extent分配/释放、碎片页、扩展）

**待集成**：
- SegmentManager 中的碎片页分配（需要注入 SpaceManager）
- 完整的集成测试（SpaceIntegrationTest）

### 2026-01-06 - 阶段1完成 ✅

**完成内容**：
- ✅ 完成所有7个核心数据结构的实现
- ✅ 完成所有7个单元测试类（120+测试用例）
- ✅ 完成 BaseStorageTest 测试基类

---

**文档结束**
**下次更新**: 完成阶段5（集成修改）和阶段6（集成测试）后更新
