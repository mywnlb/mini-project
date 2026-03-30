# 三轮复核后的 Spec-Kit 规范化计划：MySQL 风格 `information_schema`

Kernel-Safe Mode: ON  
Module: catalog-metadata (`storage.catalog` / `sql.catalog` / `server`)

## Summary
- 这次不再把 `spec-kit` 当“参考方法”，而是把它作为本次工作的正式规范框架。先安装并初始化 `spec-kit`，再用它的标准工件来约束本次 feature：`/speckit.constitution -> /speckit.specify -> /speckit.clarify -> /speckit.plan -> /speckit.tasks -> /speckit.analyze`。
- 三轮复核后，计划收紧为 3 个硬门槛：
- 先修 `createIndex/dropIndex` 的 durable catalog 路径，再做索引相关视图。
- `SHOW` 与 `information_schema` 必须共用同一个只读 `MetadataRepository`。
- 没有真实子系统的 MySQL `information_schema` 表必须显式 unsupported，不能伪造。
- 本次 feature 的 spec-kit feature id 固定为 `001-information-schema-mysql`，所有正式工件都放在 `specs/001-information-schema-mysql/`。

## A. Module Context Snapshot
- 启动链不能绕开 `DatabaseBootstrap.java`。`information_schema` 只能读取 redo recovery 和 catalog bootstrap 完成后的 committed metadata。
- 当前 durable metadata 真相源在 `CatalogManager.java`，SQL 层桥接在 `StorageCatalog.java`，兼容层入口在 `InformationSchemaProvider.java`。
- 当前代码已经有一版浅层 `information_schema`，但仍是 compatibility layer：`SCHEMATA/TABLES/COLUMNS/STATISTICS/ENGINES` 已有，字段来源和更新时间未系统化。
- 需要按 MySQL 的思路把元数据分为 4 类 source family：
- `DD_DEFINITION`：schema、table、column 定义
- `DD_PLUS_INDEX_RUNTIME`：表行数、索引定义、约束定义、索引 cardinality
- `ANALYZE_STATS`：未来列统计/直方图
- `SERVER_RUNTIME`：engine/runtime capability
- 当前最关键事实必须先写进 spec：`StorageCatalog.createIndex/dropIndex()` 尚未形成清晰的 durable catalog 更新闭环，因此 `STATISTICS/KEY_COLUMN_USAGE/TABLE_CONSTRAINTS` 的可靠实现必须先以 durable index metadata 修复为前提。

## B. Invariants
### Forbidden Designs
- 禁止新增持久化 `information_schema` 系统表或任何 metadata 副本页。
- 危险：复制 durable metadata 真相源。
- 破坏：catalog 单一来源与 recovery 一致性。
- Failure: `silent corruption` / `recovery failure`
- 禁止继续在协议层或 provider 层用常量和空结果拼接“像 MySQL”的语义。
- 危险：兼容表象掩盖真实 metadata 漂移。
- 破坏：字段 owner 唯一性。
- Failure: `silent corruption`
- 禁止在 bootstrap 外部手动创建 catalog/txn/storage 组件查询 metadata。
- 危险：绕开系统启动依赖链。
- 破坏：恢复后可见性顺序。
- Failure: `crash` / `recovery failure`

### Core Invariants
- I1. 每个 `information_schema` 字段必须在 spec 中声明唯一 truth source。
- I2. 定义类 metadata 只来自 committed catalog；统计类 metadata 只来自其专属 owner。
- I3. `information_schema` 永远只读，写请求 fail-fast。
- I4. 查询只能看到完整旧快照或完整新快照，不能看到半发布状态。
- I5. 索引相关视图必须基于 durable index definition。
- I6. 未实现子系统对应的 MySQL 表必须显式 unsupported。

### Visibility / Concurrency
- 本任务不引入新的页布局、redo 对齐、ring buffer 或 wrap-around 规则。
- DDL 仍沿用现有 `CatalogManager` 锁和 MTR/page latch 规则，不新增跨层锁顺序。
- durable write 必须 happens-before metadata snapshot publish；对外只暴露不可变 DTO。

## C. Implementation
### 1. 用 spec-kit 建立正式工作流
- 安装步骤固定为：
- 1. 按官方 `uv` 文档安装 `uv`
- 2. `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git@v0.4.3`
- 3. 在现有仓库根目录执行 `specify init --here --force --ai codex --script ps`
- `v0.4.3` 取自 `spec-kit releases`；该版本页面显示发布时间为 `2026-03-26`。
- 初始化后，以 `.specify/` 和 `specs/001-information-schema-mysql/` 为 authoritative spec-kit 目录；现有 `docs/plans/` 只保留摘要，不再充当规范真相源。

### 2. `/speckit.constitution` 先固化 AGENTS 规则
- `.specify/memory/constitution.md` 必须吸收本仓库的 storage 规则，而不是通用模板原样落地。
- constitution 固定写入 5 条原则：
- 启动链忠实性
- 单一 metadata 真相源
- durable-before-visible
- read-only metadata surface
- harness-gated delivery
- 额外写入 3 个 feature gate：
- durable index metadata 未完成前，禁止扩展索引相关视图
- `SHOW` 与 `information_schema` 必须共用 `MetadataRepository`
- unsupported family 不允许伪实现

### 3. `/speckit.specify` 先定义 WHAT/WHY
- `specs/001-information-schema-mysql/spec.md` 只定义需求、用户故事、成功标准，不写实现细节。
- `spec.md` 固定的 v1 支持面：
- `SCHEMATA`
- `TABLES`
- `COLUMNS`
- `STATISTICS`
- `KEY_COLUMN_USAGE`
- `TABLE_CONSTRAINTS`
- `ENGINES`
- `spec.md` 固定 unsupported：
- `ROUTINES`
- `TRIGGERS`
- `EVENTS`
- `VIEWS`
- `USER_PRIVILEGES`
- `SCHEMA_PRIVILEGES`
- `TABLE_PRIVILEGES`
- `REFERENTIAL_CONSTRAINTS`
- `spec.md` 固定 3 个用户故事：
- US1：客户端能像 MySQL 一样稳定查询 `SCHEMATA/TABLES/COLUMNS/ENGINES`
- US2：客户端能稳定查询 `STATISTICS/KEY_COLUMN_USAGE/TABLE_CONSTRAINTS`，并在重启后保持一致
- US3：`SHOW`、text protocol、binary protocol、prepared statement、JDBC 在 metadata 行为上对齐；未实现 family 显式 unsupported

### 4. `/speckit.clarify` 消除仍会影响实现的歧义
- clarify 只允许保留 2 个决策结果：
- `DATA_LENGTH/INDEX_LENGTH` 在没有真实 owner 前保持显式占位值，不做伪精确
- `STATISTICS.CARDINALITY` 的 owner 必须是索引级统计，不再复用表级 row count
- 如果 clarify 后仍存在 owner 不明确的字段，该字段不得进入 v1 surface。

### 5. `/speckit.plan` 产出技术计划和模型
- `research.md` 固定记录：
- MySQL `information_schema` 的 source family
- 当前仓库真实 owner
- 当前缺口：durable index DDL、`CARDINALITY` owner、`SHOW` parity
- `data-model.md` 固定定义只读 DTO：
- `SchemaInfo`
- `TableInfo`
- `ColumnInfo`
- `IndexInfo`
- `ConstraintInfo`
- `EngineInfo`
- `TableStatsInfo`
- `contracts/` 固定至少包含：
- `information-schema-surface.md`
- `metadata-repository.md`
- `show-parity.md`
- `quickstart.md` 固定给出验收 SQL，不允许留给实现者自行设计。
- `plan.md` 固定的技术顺序：
- 1. 修 `createIndex/dropIndex` 的 durable catalog 路径
- 2. 引入统一只读 `MetadataRepository`
- 3. 把 provider 重构为 registry + resolver，并完成 v1 surface
- 4. 把 `SHOW` 迁移到同一 repository
- 5. 补齐 harness 与 invariant-to-code mapping

### 6. 字段来源和更新时间必须在 plan 中冻结
- `SCHEMATA.*`
- 来源：`DatabaseDescriptor`
- 更新时间：`createDatabase/dropDatabase`
- `TABLES` 的定义字段
- 来源：`TableDescriptor` + engine registry
- 更新时间：`createTable/dropTable/alterTableAddColumn/durable index DDL`
- `TABLES.TABLE_ROWS`
- 来源：主键 `IndexDescriptor.recordCount`
- 更新时间：DML commit 后索引计数更新
- `COLUMNS.*`
- 来源：`TableDescriptor.columns` + type mapping
- 更新时间：`createTable/alterTableAddColumn`
- `STATISTICS.*`
- 来源：`IndexDescriptor`
- 更新时间：`createTable/createIndex/dropIndex`
- `STATISTICS.CARDINALITY`
- 来源：索引级统计 owner
- 更新时间：DML commit 或 analyze refresh
- `KEY_COLUMN_USAGE.*` 与 `TABLE_CONSTRAINTS.*`
- 来源：durable PK/UNIQUE 定义
- 更新时间：索引 DDL
- `ENGINES.*`
- 来源：server runtime/constants
- 更新时间：engine capability 变化时
- `DATA_LENGTH/INDEX_LENGTH`
- 来源：未来物理统计 owner
- v1：显式占位

### 7. `/speckit.tasks` 和 `/speckit.analyze` 作为实施门禁
- `tasks.md` 必须按依赖顺序组织：
- Phase 1：constitution 和 spec 完整化
- Phase 2：durable index metadata 基础设施
- Phase 3：US1
- Phase 4：US2
- Phase 5：US3
- Final：invariant-to-code mapping 与 drift 防护
- `tasks.md` 必须把测试任务内联到每个 story，不允许把测试全堆到最后。
- `/speckit.analyze` 必须在实施前运行一次，检查：
- spec 与 plan 是否漏掉 source family
- tasks 是否遗漏 durable index 先决条件
- unsupported matrix 是否与当前子系统能力一致

### 8. Public Interfaces / Types
- 新增统一只读接口：`MetadataRepository`
- 新增不可变 DTO：`SchemaInfo`、`TableInfo`、`ColumnInfo`、`IndexInfo`、`ConstraintInfo`、`EngineInfo`、`TableStatsInfo`
- `InformationSchemaProvider` 从硬编码行构造器改为 registry + resolver
- `SHOW` 命令改为共用 `MetadataRepository`

## D. Tests
- 单元测试：`MetadataRepository` 对 `SCHEMATA/TABLES/COLUMNS` 的输出与 committed catalog 一致。
- 覆盖 I1、I2
- 单元测试：`createIndex/dropIndex` 后 `STATISTICS/KEY_COLUMN_USAGE/TABLE_CONSTRAINTS` 即时变化，重启后保持一致。
- 覆盖 I4、I5
- 单元测试：`alterTableAddColumn` 后 `COLUMNS` 与 `TABLES.UPDATE_TIME` 正确更新。
- 覆盖 I1、I2
- 单元测试：未实现 family 返回明确 unsupported。
- 覆盖 I6
- 协议测试：text、binary、prepared、JDBC 的列元数据与结果语义一致，0 行结果仍返回列定义。
- 覆盖 I3
- 并发测试：DDL 与 metadata 查询并发时，只能看到完整旧快照或完整新快照。
- 覆盖 I4
- 一致性测试：`SHOW TABLES`、`SHOW TABLE STATUS`、`SHOW DATABASES` 与对应 `information_schema` 查询逐项对齐。
- 覆盖 I1、I2
- 恢复测试：DDL 后重启，bootstrap 完成后 `information_schema` 只暴露 committed 状态。
- 覆盖 I4、I5

## Assumptions
- 当前仓库没有 grants、view、trigger、routine、event、foreign key 子系统，所以相关 MySQL 表不进入 v1。
- `DATA_LENGTH/INDEX_LENGTH` 在没有真实物理统计 owner 前保持显式占位，不做伪精确。
- 现有协议层基础能力可复用；本次核心是用 spec-kit 产物把需求、实现、任务、验证串成同一套规范链。
- 当前仍是 Plan Mode，所以这里只定义 spec-kit 驱动的正式计划，不执行安装或改动。

## Sources
- [GitHub spec-kit](https://github.com/github/spec-kit)
- [Spec Kit Quick Start](https://github.github.com/spec-kit/quickstart.html)
- [Spec Kit Installation Guide](https://github.github.com/spec-kit/installation.html)
- [spec-kit releases](https://github.com/github/spec-kit/releases)
- [uv installation docs](https://docs.astral.sh/uv/getting-started/installation/)
- [OpenAI Harness Engineering](https://openai.com/zh-Hans-CN/index/harness-engineering/)
- [MySQL INFORMATION_SCHEMA overview](https://dev.mysql.com/doc/mysql-infoschema-excerpt/8.0/en/information-schema.html)
- [MySQL data dictionary and INFORMATION_SCHEMA](https://dev.mysql.com/doc/refman/8.4/en/data-dictionary-information-schema.html?ff=nopfpls)
