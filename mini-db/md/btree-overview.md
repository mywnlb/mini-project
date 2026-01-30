# MiniDB B+Tree 存储引擎

## 概述

MiniDB B+Tree 是一个完整的、生产级别的 B+Tree 索引实现，提供高效的数据存储和检索能力。该实现涵盖了现代数据库存储引擎的核心功能。

## 功能特性

| 特性 | 描述 |
|------|------|
| 基本操作 | 插入、删除、精确搜索、范围扫描 |
| 页面管理 | 页分裂、页合并、空间回收 |
| 并发控制 | 读写锁、乐观锁、Latch Coupling |
| 索引类型 | 主键索引、唯一索引、二级索引、复合键索引 |
| 持久化 | WAL 日志、崩溃恢复、Checkpoint |
| 优化 | 前缀压缩、批量加载、索引重建 |
| 诊断 | 统计信息、树结构验证、可视化 |

## 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      Application Layer                       │
├─────────────────────────────────────────────────────────────┤
│  IndexManager  │  WalBTree  │  PrefixCompressedBTree        │
├─────────────────────────────────────────────────────────────┤
│                        B+Tree Core                           │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐        │
│  │ BTree   │  │ Unique  │  │Duplicate│  │Composite│        │
│  │         │  │ BTree   │  │KeyBTree │  │KeyBTree │        │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘        │
├─────────────────────────────────────────────────────────────┤
│                     Page Operations                          │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐        │
│  │ Search  │  │ Insert  │  │ Delete  │  │ Split/  │        │
│  │         │  │         │  │         │  │ Merge   │        │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘        │
├─────────────────────────────────────────────────────────────┤
│                    Concurrency Control                       │
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │  Latch Manager  │  │ Concurrent Ops  │                   │
│  └─────────────────┘  └─────────────────┘                   │
├─────────────────────────────────────────────────────────────┤
│                      WAL & Recovery                          │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐        │
│  │   Log   │  │   Log   │  │   WAL   │  │Recovery │        │
│  │ Buffer  │  │ Reader  │  │ Manager │  │ Manager │        │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘        │
├─────────────────────────────────────────────────────────────┤
│                    Buffer Pool & Disk                        │
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │   BufferPool    │  │   DiskManager   │                   │
│  └─────────────────┘  └─────────────────┘                   │
└─────────────────────────────────────────────────────────────┘
```

## 模块列表

### 核心模块

| 模块 | 文件数 | 描述 |
|------|--------|------|
| 基础组件 | 3 | 比较器、记录构建器 |
| 单页操作 | 5 | 搜索、插入、删除 |
| 分裂合并 | 5 | 页分裂、页合并 |
| B+Tree 核心 | 5 | 多层树结构、路径追踪 |
| 游标扫描 | 4 | 范围扫描、迭代器 |
| 并发控制 | 4 | 锁管理、并发操作 |
| 统计诊断 | 3 | 统计收集、树验证 |
| 批量加载 | 5 | 批量插入、索引重建 |
| 索引类型 | 4 | 唯一索引、重复键 |
| 复合键 | 6 | 多列索引 |
| 索引管理 | 3 | 元数据持久化 |
| WAL | 7 | 日志、恢复 |
| 前缀压缩 | 7 | 键压缩 |

### 文件清单

共计 **56** 个源文件，**17** 个测试文件。

## 快速开始

```java
// 1. 初始化存储
DiskManager diskManager = new DiskManager("data.db");
BufferPool bufferPool = new BufferPool(1000, diskManager);

// 2. 创建 B+Tree
try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
    BTree btree = BTree.create(1L, 0, bufferPool, new IntKeyComparator(), mtr);

    // 3. 插入数据
    byte[] record = SimpleRecordBuilder.buildRecord(1, new byte[]{1, 2, 3}, 2);
    btree.insert(record, IntKeyComparator.intToBytes(1), mtr);

    // 4. 搜索数据
    BTreeSearchResult result = btree.search(IntKeyComparator.intToBytes(1), mtr);

    // 5. 范围扫描
    try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
        for (BTreeRangeScanner.ScanEntry entry : scanner) {
            // 处理每条记录
        }
    }

    mtr.commit();
}
```

## 文档索引

| 文档 | 描述 |
|------|------|
| [架构设计](./btree-architecture.md) | 整体架构和设计原则 |
| [核心操作](./btree-core-operations.md) | 搜索、插入、删除、分裂、合并 |
| [并发控制](./btree-concurrency.md) | 锁机制和并发策略 |
| [实现要点](./btree-implementation-notes.md) | 不变量、物理布局、代码细节 |
| [WAL 实现](./wal-implementation.md) | WAL 日志系统和崩溃恢复 |
| [索引类型](./btree-index-types.md) | 唯一索引、复合键索引 |
| [前缀压缩](./btree-compression.md) | 键压缩技术 |
| [API 参考](./btree-api-reference.md) | 完整 API 文档 |
| [性能优化](./btree-performance.md) | 批量加载、调优建议 |

## 版本信息

- **版本**: 1.0
- **作者**: MiniDB Team
- **许可**: MIT License
