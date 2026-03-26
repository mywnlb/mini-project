Kernel-Safe Mode: ON
Module: catalog-metadata (`storage.catalog` / `sql.catalog` / `server`)

# v1 `information_schema` Implementation Plan

## Summary
- 实现只读、虚拟的 `information_schema`，不新增任何持久化系统表，不改 storage 物理格式，不引入新的 redo / recovery 责任。
- v1 支持 `SCHEMATA`、`TABLES`、`COLUMNS`、`STATISTICS` 四张表，走现有 SQL 引擎与 MySQL 协议链路，不再用“空结果集兼容拦截”冒充支持。
- 同步补上“0 行 SELECT 仍返回列元数据”的协议缺口，否则 `information_schema` 在过滤后为空时会退化成 `OK` 包，语义错误。

## A. Module Context Snapshot
- 文档依据：`md/context.md`、`md/instructions.md`、`docs/catlog_ddl.md`、`md/catlog/MySQL · 源码分析 · 8.0 · DDL的那些事.md`、`docs/plans/next-features-roadmap.md`、`docs/plans/mysql-protocol-implementation-plan.md`。
- 当前基线：`SystemVariableHandler` 会拦截任意 `SELECT ... FROM information_schema...` 并返回空结果；`SHOW TABLES` 也是协议层旁路，不是 SQL 引擎能力。
- 当前 SQL 限制：`SqlParser.parseQualifiedTableName()` 会丢掉 schema 前缀；`StorageCatalog` / `StorageDataSource` 默认绑定启动时数据库；`ResultSetWriter` / `BinaryResultSetWriter` 依赖首行推断列元数据，0 行结果不正确。
- 启动链约束：仍然只能经 `DatabaseBootstrap.start()` 完成 `CatalogManager.bootstrap()` 后由协议层复用既有 catalog，不允许为 `information_schema` 在链外新建 kernel 组件。

## B. Invariants

### Forbidden Designs
- 禁止把 `information_schema` 落成新的持久化 system table。危险：复制 catalog 真相源，破坏 “metadata single source of truth”，并把只读兼容功能错误地下沉为 storage 格式变更。Failure type：silent corruption / recovery failure。
- 禁止继续用正则拦截 + 硬编码/空行伪造 `information_schema`。危险：结果与 `CatalogManager` 脱节，prepared/text protocol 在 0 行时仍无列元数据。Failure type：silent corruption。
- 禁止为了 metadata 查询在 Bootstrap 外单独创建 `CatalogManager` / `TransactionManager` / `StorageDataSource`。危险：破坏启动依赖链。Failure type：crash / recovery failure。

### Core Invariants
- I1. `information_schema` 行数据只能从现有 catalog/索引元数据即时派生，不能维护第二份持久化副本。
- I2. `information_schema` 必须严格只读；任何 `INSERT/UPDATE/DELETE/DDL` 命中虚拟表都要 fail-fast，且不能触达 storage 写路径。
- I3. 支持表上的 `SELECT` 即使返回 0 行，也必须返回正确列定义；文本协议、二进制协议、`COM_STMT_PREPARE` 三条链路一致。
- I4. 只修改 SQL/catalog/server 层；不新增页布局、MTR 时序、redo 记录、DDL replay 逻辑。
- I5. metadata 可见性只能基于 `CatalogManager` 已提交状态；查询结果允许看到提交前或提交后状态，但不能看到半更新状态。

### Required Invariant Set
- Correctness invariants：I1-I5。
- Alignment rules：N/A，本方案不引入页内偏移、对齐、ring buffer。
- Wrap-around rules：N/A，本方案不引入计数环或覆写缓冲。
- Happens-before：DDL 对外可见必须继续以 `CatalogManager` 现有“先持久化再更新 cache”的顺序为准；`information_schema` 只读 cache/descriptor，不读取未提交临时状态。
- Silent-corruption conditions：硬编码 metadata、对 unsupported 表静默返回空、0 行 SELECT 返回 `OK` 包、对虚拟表误走真实 DML。
- Invariant-only fields：内部保留表名映射（如 `__minidb_info_tables`）、虚拟表列定义枚举、只读表白名单。

## C. Implementation
- 在 parser 层做最小且显式的内部重写：仅把 `information_schema.SCHEMATA/TABLES/COLUMNS/STATISTICS` 转成保留内部表名；其他 `db.table` 继续保持现有行为，避免把本次任务扩成通用 schema 语义重构。
- 新增 `InformationSchemaProvider`，集中生成四张虚拟表的 `TableMeta` 和 `Row`。字段取值规则固定：无法从现有内核可靠推导的 MySQL 字段返回稳定默认值，如 `INDEX_LENGTH=0`、`DATA_LENGTH=0`、`TABLE_CATALOG='def'`、字符集/排序规则用当前项目固定值，不做猜测。
- 在 SQL 层加装饰器而不是改 storage：新增 `MetadataAwareCatalog` 和 `MetadataAwareDataSource`，命中内部保留表时走 provider，其他表完全委托现有 catalog/dataSource。
- 扩展 `CatalogSpi` 的跨库只读能力为默认方法：`listDatabases()`、`getTable(database, table)`、`getColumns(database, table)`、`getIndexes(database, table)`；`StorageCatalog` 实现真实版本，`MockCatalog`/测试 stub 走最小实现，避免大面积改测试。
- 缩窄 `SystemVariableHandler`：去掉对通用 `information_schema` 查询的 blanket intercept；保留 `SHOW` / `@@var` 等真正协议层兼容项。Unsupported 的 `information_schema` 表要显式报错，不再静默返回空。
- 补协议结果元数据通道：新增结果列描述工具，由 parse/validate 后推导输出列名和类型；`CommandDispatcher`、`ResultSetWriter`、`BinaryResultSetWriter`、`ServerPreparedStatement` 使用显式列元数据写空结果集，并在 `COM_STMT_PREPARE` 返回真实 result-column metadata。
- 只读 enforcement：`MetadataAwareDataSource` 对虚拟表的 `insertRow/updateRows/deleteRows/invalidateTable` 直接抛错；`CatalogSpi` 上的 `createTable/dropTable/addColumn/createIndex/dropIndex` 不允许命中保留表名。
- Invariant-to-code mapping 在实现完成后补到 provider、decorator、parser rewrite、result writers 四处，逐条对应 I1-I5。

## D. Tests
- 单元测试 1：provider 生成 `SCHEMATA/TABLES/COLUMNS/STATISTICS` 时与底层 catalog 一致，验证 I1。
- 单元测试 2：对虚拟表执行 DML/DDL fail-fast，且底层 dataSource/catalog 未被调用，验证 I2。
- 单元测试 3：parser 仅重写受支持的 `information_schema` 表名，普通 `db.table` 行为不回归，验证 I4。
- 协议集成测试 1：`SELECT TABLE_SCHEMA, TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME` 在 text protocol 返回正确列与行，覆盖用户示例与 I1。
- 协议集成测试 2：相同查询过滤到 0 行时，text protocol 仍返回列定义而非 `OK` 包，验证 I3。
- 二进制协议测试：`COM_STMT_PREPARE/EXECUTE` 查询 `information_schema.COLUMNS`，分别覆盖有行与 0 行，验证 I3。
- 回归测试：`SHOW TABLES` 继续可用；unsupported `information_schema.UNKNOWN` 返回显式错误；现有 `TextProtocolIntegrationTest` 保持通过。
- 并发测试：并发执行 `CREATE TABLE` 与 `SELECT ... FROM information_schema.TABLES`，断言只出现提交前或提交后视图，不出现半行/重复行，验证 I5。

## Assumptions
- v1 范围只做四张最常用表，不扩到 `KEY_COLUMN_USAGE`、`TABLE_CONSTRAINTS`、`PARTITIONS`。
- 不在本次改动里顺手修复“普通 SQL 路径对 `COM_INIT_DB` 的完整跨库重绑定”这一既有限制；若底层 catalog 暴露多库信息，`information_schema` 可读多库 metadata，但这不等于普通 DML 已具备完整跨库能力。
- 不新增 storage 持久化结构，不引入新的 MTR/redo/recovery 测试矩阵；若后续要给 `DATA_LENGTH/INDEX_LENGTH` 提供真实物理值，再单独走 storage 方案评审。
