# Reference: INODE / Segment / Extent / Page（InnoDB）

## 1. 层级关系
.ibd(tablespace) → INODE(段目录页) → Segment(段) → Extent(区, 1MB) → Page(页, 16KB)

## 2. 核心要点
- Segment：空间所有权 + 物理映射（由 INODE entry 持久化）
- Extent：连续页组，优化顺序 IO 与批量分配
- Page：最小 IO 单元，B+Tree 节点与记录承载体

## 3. 标准伪码：段分配页（partial→free→new extent）
```text
function segment_alloc_page(space, segment):
  if segment.partial not empty:
    e = segment.partial.first
    page = e.bitmap.alloc_one()
    if e full: move e to segment.full
    return page

  if segment.free not empty:
    e = segment.free.pop()
    move e to segment.partial
    page = e.bitmap.alloc_one()
    if e full: move e to segment.full
    return page

  e = space_alloc_extent(space)
  if e is null:
    space_extend(space)
    e = space_alloc_extent(space)
  segment.free.push(e)
  goto retry
```

## 4. INSERT过程中的MTR与段区分配（时序图）

### 4.1 正确的时序图（逻辑物理分层）

```mermaid
sequenceDiagram
    participant SQL as SQL Executor
    participant BTR as B+Tree (logic)
    participant SEG as Segment (logic)
    participant MTR as MiniTransaction
    participant INODE as INODE Page
    participant XDES as XDES Page
    participant DATA as Data Page

    SQL->>BTR: INSERT row

    Note over BTR,MTR: Phase 1: Search (in MTR)
    BTR->>MTR: create MTR
    activate MTR
    BTR->>MTR: getPage(leafPageId)
    MTR->>DATA: fetch leaf page
    DATA-->>MTR: leaf page
    MTR-->>BTR: leaf page

    alt leaf page has space
        BTR->>DATA: insert record
        BTR->>MTR: markDirty(leaf)
        BTR->>MTR: commit()
        deactivate MTR
        Note over MTR: write redo log
        BTR-->>SQL: INSERT OK

    else leaf page full (need new page)
        BTR->>MTR: commit() (release search MTR)
        deactivate MTR

        Note over BTR,XDES: Phase 2: Allocate + Insert (in new MTR)
        BTR->>MTR: create new MTR
        activate MTR

        BTR->>SEG: allocatePage(mtr) (LOGIC)
        Note over SEG: decide strategy:<br/>fragment page or full extent

        SEG->>MTR: getPage(inodePageId)
        MTR->>INODE: fetch inode page
        INODE-->>SEG: inode page

        SEG->>INODE: update segment inode (PHYSICAL)
        SEG->>MTR: markDirty(inode)

        SEG->>MTR: getPage(xdesPageId)
        MTR->>XDES: fetch xdes page
        XDES-->>SEG: xdes page

        SEG->>XDES: update extent bitmap (PHYSICAL)
        SEG->>MTR: markDirty(xdes)

        SEG->>MTR: newPage(spaceId)
        MTR->>DATA: allocate new page
        DATA-->>SEG: new page

        SEG->>DATA: initialize page
        SEG->>MTR: markDirty(new page)
        SEG-->>BTR: new page allocated

        BTR->>DATA: insert record to new page
        BTR->>MTR: markDirty(new page)

        BTR->>MTR: commit()
        deactivate MTR
        Note over MTR: write redo log atomically<br/>(inode + xdes + data)

        BTR-->>SQL: INSERT OK
    end
```

### 4.2 关键设计原则

1. **MTR由上层创建，作为参数传递**
   - ✅ B+Tree层创建MTR
   - ✅ Segment.allocatePage(mtr) - MTR作为参数传入
   - ❌ Segment内部不应该创建MTR

2. **决策和修改在同一个MTR中**
   - 决策阶段：读取INODE/XDES（通过mtr.getPage()）
   - 修改阶段：更新物理结构（调用markDirty()）
   - 提交阶段：mtr.commit() 生成redo log

3. **99%的INSERT只修改数据页**
   - 场景1：叶子节点有空间 → 只修改1个数据页
   - 场景2：叶子节点满，需要分裂 → 修改数据页 + INODE/XDES

## 5. 逻辑层 vs 物理层（分层设计）

### 5.1 为什么要分层

| 问题 | 混合设计 | 分层设计 |
|------|---------|---------|
| 职责 | ExtentDescriptor既负责40字节读写，又负责页面分配逻辑 | ExtentDescriptor只负责物理读写，Extent类负责分配逻辑 |
| 扩展性 | 要修改分配策略需要改物理类 | 修改Segment/Extent逻辑类即可 |
| 测试 | 单元测试需要模拟物理页面和逻辑行为 | 物理类测试字节操作，逻辑类测试算法 |
| 单一职责 | 违反（一个类两个变化方向） | 符合（物理变化和逻辑变化分离） |

### 5.2 分层架构

```
┌─────────────────────────────────────────┐
│  Logical Layer (逻辑层)                 │
│  ┌───────────────────────────────────┐  │
│  │ TableSpace                        │  │ ← 表空间管理
│  │  - allocateSegment()              │  │
│  │  - allocateExtentForSegment()     │  │
│  │  - allocateFragmentPage()         │  │
│  │  - returnExtentToFree()           │  │
│  └───────────────────────────────────┘  │
│         ↓                  ↓             │
│  ┌─────────────┐    ┌──────────────┐    │
│  │ Segment     │    │ Extent       │    │ ← 段/区逻辑
│  │ - allocate  │    │ - allocate   │    │
│  │ - free      │    │ - free       │    │
│  │ - 32碎片策略│    │ - findFree   │    │
│  └─────────────┘    └──────────────┘    │
└─────────────────────────────────────────┘
         ↓                  ↓
┌─────────────────────────────────────────┐
│  Physical Layer (物理层)                │
│  ┌──────────────┐  ┌─────────────────┐  │
│  │ FspHeaderPg  │  │ InodePage       │  │ ← 物理页面
│  │ XdesPage     │  │                 │  │
│  └──────────────┘  └─────────────────┘  │
│         ↓                  ↓             │
│  ┌────────────────┐ ┌──────────────────┐│
│  │ExtentDesc      │ │SegmentDesc       ││ ← 纯物理结构
│  │ - get/putXxx   │ │ - get/putXxx     ││   (只有getter/setter)
│  │ - isPageFree() │ │ - getFragPageNo()││
│  │ - setPageBit() │ │ - setFragPageNo()││
│  └────────────────┘ └──────────────────┘│
└─────────────────────────────────────────┘
```

### 5.3 职责划分

**物理层（ExtentDescriptor, SegmentDescriptor, InodePage, FspHeaderPage）**：
- ✅ get/put基本字段（segmentId, state, bitmap, fragArray等）
- ✅ initialize初始化
- ✅ 底层的位操作（isPageFree, setPageBit）
- ❌ 不包含分配逻辑（allocatePage, findFreePage, allocateEntry）

**逻辑层（Extent, Segment, TableSpace）**：
- ✅ 空间分配决策
- ✅ 分配算法（32个碎片页策略、extent分配策略）
- ✅ 链表操作（从FREE链表拿extent等）
- ✅ 调用物理层的getter/setter
- ✅ 在MTR中协调多个物理页面的修改

### 5.4 代码示例对比

**Before（混合设计）：**
```java
// ExtentDescriptor既有物理操作又有逻辑
public class ExtentDescriptor {
    // 物理层
    public long getSegmentId() { return page.getLong(offset + 0); }
    public void setSegmentId(MTR mtr, long id) { page.putLong(offset + 0, id); }

    // 逻辑层（不应该在这里！）
    public void allocatePage(MTR mtr, int pageOffset) { ... }
    public void freePage(MTR mtr, int pageOffset) { ... }
    public int findFreePage() {
        // 遍历bitmap查找空闲页
        for (int i = 0; i < 64; i++) {
            if (isPageFree(i)) return i;
        }
        return -1;
    }
}
```

**After（分层设计）：**
```java
// ExtentDescriptor只负责物理操作
public class ExtentDescriptor {
    // 物理层：只有get/set和底层位操作
    public long getSegmentId() { return page.getLong(offset + 0); }
    public void setSegmentId(MTR mtr, long id) { page.putLong(offset + 0, id); }
    public boolean isPageFree(int pageOffset) { return getBit(pageOffset * 2); }
    public void setPageBit(MTR mtr, int pageOffset, boolean free) { setBit(pageOffset * 2, free); }
}

// Extent负责逻辑操作
public class Extent {
    private ExtentDescriptor descriptor;  // 物理结构

    // 逻辑层：分配算法
    public int allocatePage(MTR mtr) {
        int pageOffset = findFreePage();  // 查找算法
        if (pageOffset != -1) {
            descriptor.setPageBit(mtr, pageOffset, false);  // 调用物理层
            return getStartPageNo() + pageOffset;
        }
        return -1;
    }

    private int findFreePage() {
        // 遍历bitmap（逻辑）
        for (int i = 0; i < 64; i++) {
            if (descriptor.isPageFree(i)) {  // 调用物理层
                return i;
            }
        }
        return -1;
    }
}
```

## 6. MTR使用关键要点

1. **MTR生命周期**：
   - 在B+Tree/SQL层创建：`try (MTR mtr = new MiniTransaction(bufferPool))`
   - 作为参数传递给Segment/Extent：`segment.allocatePage(mtr)`
   - 决策和修改都在**同一个MTR**中完成
   - commit时自动生成redo log

2. **决策阶段（读取物理结构，不修改）**：
   ```java
   Page inodePage = mtr.getPage(inodePageId);  // 读取
   SegmentDescriptor seg = inodePage.getEntry(idx);
   int fragSlot = seg.findFreeFragSlot();  // 决策
   // 此时还没有调用markDirty
   ```

3. **修改阶段（更新物理结构）**：
   ```java
   seg.setFragPageNo(mtr, fragSlot, newPageNo);  // 修改
   mtr.markDirty(inodePage);  // 标记脏页
   ```

4. **原子性保证**：
   - 决策依赖的状态（如碎片数组）和修改操作必须原子
   - 如果分两个MTR，中间可能被其他线程修改
   - 通过MTR的pin机制保证并发安全

## 7. 重构检查清单

重构现有代码为分层设计时，检查以下要点：

- [ ] ExtentDescriptor移除allocatePage/freePage/findFreePage
- [ ] SegmentDescriptor移除分配逻辑方法
- [ ] InodePage移除allocateEntry/freeEntry/findFreeEntry
- [ ] FspHeaderPage移除allocateSegmentId
- [ ] 创建Extent逻辑类（封装ExtentDescriptor）
- [ ] 创建Segment逻辑类（封装SegmentDescriptor + 32碎片策略）
- [ ] 创建TableSpace逻辑类（管理FSP Header + FREE/FRAG链表）
- [ ] 所有逻辑类的方法都接受MTR作为参数
- [ ] 测试分为物理层测试（字节操作）和逻辑层测试（算法）
