# MySQL 风格 `information_schema` 实施计划

Kernel-Safe Mode: ON
Module: catalog-metadata (`storage.catalog` / `sql.catalog` / `server`)

## Summary

本计划将 minidb 的 `information_schema` 从当前 5 张虚拟表（SCHEMATA/TABLES/COLUMNS/STATISTICS/ENGINES）扩展为 7 张，新增 `KEY_COLUMN_USAGE` 和 `TABLE_CONSTRAINTS`，同时修复 index DDL 的 durable 路径缺口，确保重启后元数据一致。

3 个硬门槛：
1. 先修 `createIndex/dropIndex` 的 durable catalog 路径，再做索引相关视图
2. `SHOW` 与 `information_schema` 必须共用同一个只读数据源（当前已通过 `CatalogSpi` 实现）
3. 没有真实子系统的 MySQL `information_schema` 表必须显式 unsupported，不能伪造

---

## A. Module Context Snapshot

- **启动链**：不能绕开 `DatabaseBootstrap.java`。`information_schema` 只能读取 redo recovery 和 catalog bootstrap 完成后的 committed metadata
- **当前 metadata 真相源**：`CatalogManager.java`（storage 层）→ `StorageCatalog.java`（SQL 层桥接）→ `InformationSchemaProvider.java`（虚拟表）
- **当前已有 5 张虚拟表**：SCHEMATA / TABLES / COLUMNS / STATISTICS / ENGINES，字段从 CatalogSpi 实时派生
- **装饰器链**：`MetadataAwareCatalog`（读写路由）+ `MetadataAwareDataSource`（执行层拦截）
- **SHOW 命令**：已通过协议层转译为 information_schema 查询，共用 CatalogSpi 数据源

### 当前关键缺口（摸底确认）

**Index DDL 持久化路径断裂**：

| 组件 | 持久化？ | 问题 |
|------|----------|------|
| IndexMetaPage（B+Tree 元数据） | ✅ | IndexManager.persistDescriptor() 正常写入 |
| B+Tree 页面 | ✅ | BTree.create() 分配并提交 |
| TableMetaPage.secondaryIndexIds | ❌ | createIndex 后从未调用 persistTableUpdate() |
| DdlLog | ❌ | 无 CREATE_INDEX / DROP_INDEX 操作类型 |
| DB 锁 | ❌ | Index DDL 绕开 CatalogManager，不加数据库级锁 |

**崩溃场景**：IndexMetaPage 已写入但 TableMetaPage 未更新 → 重启后 TableDescriptor.secondaryIndexIds 缺失孤儿索引。

---

## B. Invariants

### Forbidden Designs

1. **禁止新增持久化 `information_schema` 系统表或任何 metadata 副本页**
   - 危险：复制 durable metadata 真相源
   - 破坏：catalog 单一来源与 recovery 一致性
   - Failure: `silent corruption` / `recovery failure`

2. **禁止在 provider 层用常量和空结果拼接"像 MySQL"的语义**
   - 危险：兼容表象掩盖真实 metadata 漂移
   - 破坏：字段 owner 唯一性
   - Failure: `silent corruption`

3. **禁止在 bootstrap 外部手动创建 catalog/txn/storage 组件查询 metadata**
   - 危险：绕开系统启动依赖链
   - 破坏：恢复后可见性顺序
   - Failure: `crash` / `recovery failure`

### Core Invariants

- **I1**. 每个 `information_schema` 字段必须有唯一 truth source
- **I2**. 定义类 metadata 只来自 committed catalog；统计类 metadata 只来自其专属 owner
- **I3**. `information_schema` 永远只读，写请求 fail-fast
- **I4**. 查询只能看到完整旧快照或完整新快照，不能看到半发布状态
- **I5**. 索引相关视图必须基于 durable index definition
- **I6**. 未实现子系统对应的 MySQL 表必须显式 unsupported

### Visibility / Concurrency

- 本任务不引入新的页布局、redo 对齐、ring buffer 或 wrap-around 规则
- DDL 仍沿用现有 `CatalogManager` 锁和 MTR/page latch 规则，不新增跨层锁顺序
- Index DDL 修复后必须走 CatalogManager 的 per-database ReentrantLock
- durable write 必须 happens-before metadata snapshot publish；对外只暴露不可变 DTO

---

## C. Implementation

### Phase 1：修复 Index DDL Durable 路径

**目标**：让 `createIndex` / `dropIndex` 走与 `createTable` 相同的原子持久化路径。

#### 1.1 CatalogManager 暴露 Index DDL 公开方法

新增两个公开方法：

- `CatalogManager.createIndex(dbName, tableName, indexName, indexType, columns)`
  - 获取 per-database ReentrantLock
  - 通过 IndexManager 创建 B+Tree 和 IndexMetaPage
  - 更新 TableDescriptor.secondaryIndexIds
  - 调用 `persistTableUpdate()` 将 TableMetaPage 写盘
  - 在同一 MTR 内完成，保证原子性

- `CatalogManager.dropIndex(dbName, tableName, indexName)`
  - 获取 per-database ReentrantLock
  - IndexManager.dropIndex() 标记删除
  - 更新 TableDescriptor.secondaryIndexIds
  - 调用 `persistTableUpdate()` 将 TableMetaPage 写盘

#### 1.2 DdlLog 扩展（可选，按需）

- 新增 `DdlLogType.CREATE_INDEX` / `DROP_INDEX`
- `DdlLogRecord` 启用已预留的 indexId 字段
- `DdlLogReplay` 新增索引操作回放逻辑
- `DdlReplayGuard` 新增 `INDEX_ABSENT` / `INDEX_PRESENT` 守卫

> 注：如果 Phase 1.1 的原子 MTR 已经保证了 IndexMetaPage + TableMetaPage 的一致性，DdlLog 扩展可以延后到需要跨表空间原子性时再做。

#### 1.3 StorageCatalog 重构

- `StorageCatalog.createIndex()` 改为委托 `CatalogManager.createIndex()`
- `StorageCatalog.dropIndex()` 改为委托 `CatalogManager.dropIndex()`
- 移除直接操作 IndexManager 的代码

### Phase 2：扩展 information_schema v1 Surface

**目标**：在已有 5 张表基础上，新增 `KEY_COLUMN_USAGE` 和 `TABLE_CONSTRAINTS`。

#### 2.1 InformationSchemaNames 注册新表

- 新增 `KEY_COLUMN_USAGE` → `__MINIDB_INFO_KEY_COLUMN_USAGE`
- 新增 `TABLE_CONSTRAINTS` → `__MINIDB_INFO_TABLE_CONSTRAINTS`

#### 2.2 InformationSchemaProvider 新增行构造器

- `buildKeyColumnUsageRows()`：从 IndexDescriptor（PRIMARY / UNIQUE）提取列信息
  - 字段：CONSTRAINT_CATALOG / CONSTRAINT_SCHEMA / CONSTRAINT_NAME / TABLE_CATALOG / TABLE_SCHEMA / TABLE_NAME / COLUMN_NAME / ORDINAL_POSITION / POSITION_IN_UNIQUE_CONSTRAINT
  - 来源：durable PK/UNIQUE 的 IndexDescriptor.columns

- `buildTableConstraintsRows()`：从 IndexDescriptor（PRIMARY / UNIQUE）提取约束信息
  - 字段：CONSTRAINT_CATALOG / CONSTRAINT_SCHEMA / CONSTRAINT_NAME / TABLE_SCHEMA / TABLE_NAME / CONSTRAINT_TYPE / ENFORCED
  - 来源：IndexDescriptor.indexType → PRIMARY KEY / UNIQUE

#### 2.3 Unsupported 表显式处理

以下 MySQL `information_schema` 表在 v1 中显式 unsupported（查询返回正确列定义 + 0 行，或抛出明确错误）：

- `ROUTINES`（无存储过程子系统）
- `TRIGGERS`（无触发器子系统）
- `EVENTS`（无事件调度器）
- `VIEWS`（无视图子系统）
- `USER_PRIVILEGES`（无权限子系统）
- `SCHEMA_PRIVILEGES`（同上）
- `TABLE_PRIVILEGES`（同上）
- `REFERENTIAL_CONSTRAINTS`（无外键子系统）

### Phase 3：SHOW 与 information_schema 对齐验证

**目标**：确保 `SHOW TABLES`、`SHOW TABLE STATUS`、`SHOW DATABASES`、`SHOW INDEX` 与对应 `information_schema` 查询结果一致。

- 当前 SHOW 已转译为 information_schema 查询，理论上天然对齐
- 需要补齐 `SHOW INDEX FROM <table>` 的转译（如尚未实现）
- 验证 text protocol / binary protocol / prepared statement / JDBC 列元数据一致

### Phase 4：测试矩阵

测试内联到各 Phase，不堆到最后。

---

## D. 字段来源冻结

### SCHEMATA.*
- **来源**：`DatabaseDescriptor`
- **更新时机**：`createDatabase` / `dropDatabase`

### TABLES 定义字段
- **来源**：`TableDescriptor` + engine registry
- **更新时机**：`createTable` / `dropTable` / `alterTableAddColumn` / index DDL

### TABLES.TABLE_ROWS
- **来源**：主键 `IndexDescriptor.recordCount`
- **更新时机**：DML commit 后索引计数更新

### COLUMNS.*
- **来源**：`TableDescriptor.columns` + type mapping
- **更新时机**：`createTable` / `alterTableAddColumn`

### STATISTICS.*
- **来源**：`IndexDescriptor`
- **更新时机**：`createTable` / `createIndex` / `dropIndex`

### STATISTICS.CARDINALITY
- **来源**：索引级统计 owner
- **更新时机**：DML commit 或 analyze refresh

### KEY_COLUMN_USAGE.* 与 TABLE_CONSTRAINTS.*
- **来源**：durable PK/UNIQUE IndexDescriptor
- **更新时机**：index DDL

### ENGINES.*
- **来源**：server runtime / constants
- **更新时机**：engine capability 变化时

### DATA_LENGTH / INDEX_LENGTH
- **来源**：未来物理统计 owner
- **v1**：显式占位值，不做伪精确

---

## E. Tests

### Phase 1 测试（Index DDL Durable）

1. **单元测试：createIndex 后 TableMetaPage 持久化验证**
   - createIndex → 强制刷盘 → 重新加载 CatalogBootstrap → 验证 secondaryIndexIds 包含新索引
   - 覆盖 **I5**

2. **单元测试：dropIndex 后 TableMetaPage 持久化验证**
   - dropIndex → 强制刷盘 → 重新加载 → 验证 secondaryIndexIds 不包含已删索引
   - 覆盖 **I5**

3. **单元测试：createIndex 的 DB 锁保护**
   - 并发 createIndex 同一张表 → 验证无数据竞争，索引 ID 不重复
   - 覆盖 **I4**

### Phase 2 测试（information_schema 扩展）

4. **单元测试：SCHEMATA/TABLES/COLUMNS 与 committed catalog 一致**
   - createDatabase + createTable + addColumn → 查询 information_schema → 逐字段对比
   - 覆盖 **I1, I2**

5. **单元测试：STATISTICS/KEY_COLUMN_USAGE/TABLE_CONSTRAINTS 即时变化**
   - createIndex → 查询三张表 → 验证新索引出现
   - dropIndex → 再查 → 验证已删索引消失
   - 重启 → 再查 → 验证与重启前一致
   - 覆盖 **I4, I5**

6. **单元测试：alterTableAddColumn 后 COLUMNS 正确更新**
   - 覆盖 **I1, I2**

7. **单元测试：未实现 family 返回明确 unsupported**
   - 查询 ROUTINES / TRIGGERS 等 → 验证行为（0 行或明确错误）
   - 覆盖 **I6**

### Phase 3 测试（对齐验证）

8. **协议测试：text / binary / prepared / JDBC 列元数据一致**
   - 0 行结果仍返回列定义
   - 覆盖 **I3**

9. **一致性测试：SHOW 与 information_schema 逐项对齐**
   - `SHOW TABLES` vs `SELECT ... FROM INFORMATION_SCHEMA.TABLES`
   - `SHOW DATABASES` vs `SELECT ... FROM INFORMATION_SCHEMA.SCHEMATA`
   - `SHOW INDEX FROM t` vs `SELECT ... FROM INFORMATION_SCHEMA.STATISTICS`
   - 覆盖 **I1, I2**

### 跨 Phase 测试

10. **并发测试：DDL 与 metadata 查询并发**
    - DDL 线程执行 createTable/createIndex，查询线程读 information_schema
    - 查询只能看到完整旧快照或完整新快照
    - 覆盖 **I4**

11. **恢复测试：DDL 后重启一致性**
    - createTable + createIndex → kill → recovery → information_schema 只暴露 committed 状态
    - 覆盖 **I4, I5**

---

## F. Assumptions

- 当前仓库没有 grants、view、trigger、routine、event、foreign key 子系统，相关 MySQL 表不进入 v1
- `DATA_LENGTH` / `INDEX_LENGTH` 在没有真实物理统计 owner 前保持显式占位
- SHOW 已通过协议层转译为 information_schema 查询，共用 CatalogSpi 数据源
- 现有 `TableMeta` / `ColumnMeta` / `IndexMeta` DTO 可直接复用，不新建额外 DTO 层

---

## G. References

- [MySQL INFORMATION_SCHEMA overview](https://dev.mysql.com/doc/mysql-infoschema-excerpt/8.0/en/information-schema.html)
- [MySQL data dictionary and INFORMATION_SCHEMA](https://dev.mysql.com/doc/refman/8.4/en/data-dictionary-information-schema.html)
