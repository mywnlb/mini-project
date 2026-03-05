# Examples（常见问答）

## Q1 段和区有什么用？
- Extent：连续 IO/预读/批量分配
- Segment：对象级空间归属与生命周期（DROP INDEX 可快速回收）
- Page：最小 IO 单元

## Q2 段是不是只是逻辑意义上的？
- 语义上是逻辑对象
- 但有落盘元数据（INODE entry）维护 extent 列表 → 不是纯抽象
- 类比文件系统 inode
