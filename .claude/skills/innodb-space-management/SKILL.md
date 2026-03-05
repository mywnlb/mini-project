---
name: innodb-space-management
description: Explain InnoDB space management (inode, segment, extent, page) including allocation, B+Tree growth, and space reclamation.
---

# InnoDB Space Management Skill

## 适用范围
用于回答 MySQL/InnoDB 的空间管理问题，重点讲清 **INODE → Segment → Extent → Page** 的层级关系，以及它们如何影响：
- 页/区分配与表空间扩展
- B+Tree 页分裂与索引增长
- 空间回收（DROP INDEX / DROP TABLE）
- 顺序 IO、预读与碎片

## 强制解释主线（必须出现）
Tablespace(.ibd) → INODE(段目录) → Segment(空间所有权) → Extent(连续分配单元) → Page(最小IO单元) → Record

## 必须澄清的边界
- Segment 语义上是逻辑对象，但有落盘元数据（INODE entry）维护 extent 列表，因此不是“纯抽象概念”。
- Extent（默认 64×16KB=1MB）用于批量分配与提升物理局部性。
- Page（默认 16KB）是最小 IO 单位，真实数据/索引最终都落在 Page 中。

## 回答格式要求
1) 结论 1~2 句  
2) 分层解释（INODE/Segment/Extent/Page 各自职责）  
3) 给一个具体流程（INSERT 分配 或 DROP INDEX 回收 或 页分裂）  
4) 纠正常见误解（尤其是 Segment 是否纯逻辑）

## 资源
- 参考资料：reference.md
- 常见问答：examples.md
- 用户补充笔记：user-notes-segment-extent-page.md / why-need-segment-extent.md
