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
