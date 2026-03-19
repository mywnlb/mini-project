# Requirements Document: ALTER TABLE ADD COLUMN (Instant DDL)

## Introduction

本功能为 mini-db 存储内核实现 ALTER TABLE ADD COLUMN 的 Instant DDL 支持。Instant DDL 的核心思想是：添加新列时不需要重写已有数据行，而是通过 Schema 版本化和默认值填充机制，使旧行在读取时自动获得新列的默认值。这是 MySQL 8.0 InnoDB Instant ADD COLUMN 的简化实现。

### 当前代码状态

- `StorageCatalog.addColumn()` 显式抛出 `UnsupportedOperationException`
- `SchemaRegistry` 已完整支持 `addColumnInstant()` + `fillInstantDefaults()` + version 对齐
- `SqlValidator.validateAlterTable()` 已实现表存在性检查和列名重复检查
- `SqlToRelConverter` 已处理 `SqlAlterTable → RelAlterTable`
- `PhysicalPlanner` 已处理 `RelAlterTable → AlterTableExec`
- `AlterTableExec` 已调用 `CatalogSpi.addColumn()`，但因 StorageCatalog 抛异常而失败
- `CatalogManager` 尚无 `alterTableAddColumn()` 方法（需新增）
- `TableDescriptor.columns` 是 `Collections.unmodifiableList`（需处理不可变约束）
- `StorageDataSource.tableCache` 无 invalidate 机制（需新增）
- `SqlAlterTable` AST 节点仅有 `(table, columnName, columnType)` 三个字段，缺少 nullable/defaultValue

### Kernel-Safe 上下文

- Module: sql/exec (AlterTableExec) + sql/catalog (StorageCatalog) + storage/catalog (CatalogManager, TableDescriptor) + storage/record/schema (SchemaRegistry)
- Invariants: I5（rowVersion 从记录 peek）、I6（Instant 默认值按 columnId + introducedVersion 填充）
- Forbidden Design 1: 直接修改现有 RecordSchema 对象并原地 add column → silent corruption
- Forbidden Design 2: 在 AlterTableExec 中直接调用 CatalogManager 绕过 RelAlterTable → recovery failure

## Glossary

- **SqlAlterTable**: ALTER TABLE 的 AST 节点，record 类型，当前字段为 `(table, columnName, columnType)`，本次需扩展 nullable/defaultValue
- **SqlValidator**: SQL 语义验证器，`validateAlterTable()` 已实现表存在性和列名重复检查
- **AlterTableExec**: ALTER TABLE 的物理执行器，已实现 `open()` 调用 `CatalogSpi.addColumn()`
- **RelAlterTable**: ALTER TABLE 的关系代数计划节点，已由 `SqlToRelConverter` 和 `PhysicalPlanner` 支持
- **CatalogSpi**: SQL 层 Catalog 接口，已定义 `addColumn(String tableName, ColumnMeta column)` 方法
- **StorageCatalog**: SQL 层与存储引擎之间的 Catalog 桥接层，实现 `CatalogSpi` 接口，当前 `addColumn()` 抛 `UnsupportedOperationException`
- **CatalogManager**: 存储引擎的元数据管理器，已有 per-DB `ReentrantLock`、`IdGenerator`、MTR 持久化模式，需新增 `alterTableAddColumn()` 方法
- **TableDescriptor**: 表描述符，`columns` 字段为 `Collections.unmodifiableList`（构造后不可变），持有 `SchemaRegistry` 引用
- **SchemaRegistry**: Schema 版本注册表，`currentSchema` 为 `volatile`，已实现 `addColumnInstant()` + `fillInstantDefaults()`
- **RecordSchema**: 某个 rowVersion 对应的 schema 快照
- **InstantColumnMeta**: Instant DDL 列元信息，以 `columnId` 为主键（F5），含 `introducedVersion`、`defaultBytes`、`type`
- **ColumnDescriptor**: record 层列描述符，以 columnId 为长期稳定标识
- **ColumnMeta (SQL 层)**: `cn.zhangyis.minidb.sql.catalog.ColumnMeta`，record 类型 `(name, type, isPrimaryKey)`，本次需扩展 nullable/defaultValue
- **ColumnMeta (Storage 层)**: `cn.zhangyis.minidb.storage.catalog.ColumnMeta`，含 `columnId`、`name`、`type`、`ordinal`、`defaultValue`
- **TypeBridge**: SQL 类型到存储类型的桥接工具，`FieldKind ↔ ColumnType` 转换
- **RowReader**: 统一记录读取器，`read()` 方法强制 I5（peekRowVersion 从记录 peek）+ I6（fillInstantDefaults）
- **rowVersion**: 记录中嵌入的 2 字节版本号（`dataStart + 13` 偏移），用于确定该行写入时的 schema 版本
- **StorageDataSource**: SQL 执行引擎的数据源实现，`tableCache` 为 `LinkedHashMap`，`openTable()` 用 `computeIfAbsent` 缓存
- **TableAccess**: `StorageDataSource` 内部类，缓存 `RowReader`、`RecordSchema`、`IndexDescriptor` 等
- **DataTuple**: 逻辑行表示，与物理格式无关的内存行数据
- **DataField**: 逻辑字段，`DataTuple` 中的单个字段值

## Requirements

### Requirement 1: AST 节点扩展（前置依赖）

**User Story:** 作为数据库开发者，我需要 `SqlAlterTable` AST 节点支持 nullable 和 defaultValue 信息，以便 Instant DDL 能正确判断新列是否合法。

**当前代码状态:** `SqlAlterTable` 是 record 类型，仅有 `(table, columnName, columnType)` 三个字段。`SqlParser` 解析 ALTER TABLE 时未提取 nullable/default 信息。

#### Acceptance Criteria

1. WHEN SqlParser 解析 `ALTER TABLE t ADD COLUMN col INT` 语句, THE SqlAlterTable SHALL 默认 nullable=true（Instant DDL 安全默认值）
2. WHEN SqlParser 解析 `ALTER TABLE t ADD COLUMN col INT NOT NULL DEFAULT 0` 语句, THE SqlAlterTable SHALL 携带 nullable=false 和 defaultValue 信息
3. WHEN SqlParser 解析 `ALTER TABLE t ADD COLUMN col INT DEFAULT NULL` 语句, THE SqlAlterTable SHALL 携带 nullable=true 和 defaultValue=NULL
4. THE SqlAlterTable record SHALL 扩展为 `(table, columnName, columnType, nullable, defaultValue)` 或等价结构
5. THE SQL 层 ColumnMeta record SHALL 扩展为 `(name, type, isPrimaryKey, nullable, defaultValue)` 以传递完整列信息到 StorageCatalog

### Requirement 2: SQL 语法解析与语义验证

**User Story:** 作为数据库用户，我希望执行 `ALTER TABLE t ADD COLUMN col type` 语句时，系统能正确解析语法并验证语义合法性，以便在执行前发现错误。

**当前代码状态:** `SqlValidator.validateAlterTable()` 已实现表存在性检查和列名重复检查。需新增 NOT NULL + 无默认值的拒绝逻辑。

#### Acceptance Criteria

1. WHEN 用户提交 `ALTER TABLE t ADD COLUMN col type` 语句, THE SqlValidator SHALL 验证目标表存在于 Catalog 中（已实现）
2. WHEN 用户提交 ALTER TABLE 语句且目标表不存在, THE SqlValidator SHALL 抛出 ValidationException 并包含表名信息（已实现）
3. WHEN 用户提交 ALTER TABLE ADD COLUMN 语句且列名已存在于目标表中, THE SqlValidator SHALL 抛出 ValidationException 并包含列名信息（已实现）
4. WHEN 用户提交 ALTER TABLE ADD COLUMN 语句且列类型为系统支持的 SqlType, THE SqlValidator SHALL 返回经过验证的 SqlAlterTable 节点
5. WHEN 用户提交 ALTER TABLE ADD COLUMN 语句且新列为 NOT NULL 但未指定默认值, THE SqlValidator SHALL 抛出 ValidationException 提示 Instant DDL 要求 nullable 列或提供默认值（需新增）

### Requirement 3: 执行计划生成与执行

**User Story:** 作为数据库用户，我希望 ALTER TABLE ADD COLUMN 语句能通过执行引擎正确执行，以便新列被添加到表结构中。

**当前代码状态:** 完整链路已存在：`SqlToRelConverter` → `RelAlterTable` → `PhysicalPlanner` → `AlterTableExec`。`AlterTableExec.open()` 已调用 `catalog.addColumn()`。需更新 `AlterTableExec` 以传递扩展后的 ColumnMeta（含 nullable/defaultValue）。

#### Acceptance Criteria

1. WHEN SqlValidator 验证通过后, THE SqlToRelConverter SHALL 生成 RelAlterTable 计划节点（已实现）
2. WHEN PhysicalPlanner 接收到 RelAlterTable 节点, THE PhysicalPlanner SHALL 生成 AlterTableExec 执行器（已实现）
3. WHEN AlterTableExec 执行 open() 方法, THE AlterTableExec SHALL 构造包含 nullable 和 defaultValue 的 SQL 层 ColumnMeta，并调用 CatalogSpi.addColumn()（需更新）
4. WHEN AlterTableExec 执行成功, THE AlterTableExec SHALL 通过 next() 返回包含成功消息的 Row（已实现）
5. IF AlterTableExec 执行过程中 CatalogSpi.addColumn() 抛出异常, THEN THE AlterTableExec SHALL 将异常传播给调用者（已实现）

### Requirement 4: StorageCatalog 桥接层实现

**User Story:** 作为存储引擎开发者，我希望 StorageCatalog 能将 SQL 层的 ADD COLUMN 请求正确转换并委托给存储引擎的 CatalogManager，以便 SQL 层与存储层解耦。

**当前代码状态:** `StorageCatalog.addColumn()` 显式抛出 `UnsupportedOperationException`。`StorageCatalog` 已有 `sqlTypeToFieldType()` 和 `requireBufferPool()` 辅助方法。`CatalogManager` 尚无 `alterTableAddColumn()` 方法。

#### Acceptance Criteria

1. WHEN StorageCatalog.addColumn() 被调用, THE StorageCatalog SHALL 通过 `sqlTypeToFieldType()` 将 SQL 层 ColumnMeta 转换为存储层 FieldType
2. WHEN StorageCatalog.addColumn() 被调用, THE StorageCatalog SHALL 调用 CatalogManager 的 `alterTableAddColumn()` 方法（需新增）执行实际的 schema 变更
3. WHEN StorageCatalog.addColumn() 成功完成, THE StorageCatalog SHALL 不再抛出 UnsupportedOperationException
4. WHEN StorageCatalog.addColumn() 被调用, THE StorageCatalog SHALL 先调用 `requireBufferPool("addColumn")` 确保存储引擎可用
5. WHEN StorageCatalog.addColumn() 成功完成, THE StorageCatalog SHALL 触发 StorageDataSource 的 tableCache 失效（见 Requirement 9）

### Requirement 5: CatalogManager 新增 alterTableAddColumn 方法

**User Story:** 作为存储引擎开发者，我希望 CatalogManager 能原子地完成 Instant ADD COLUMN 的 schema 变更并持久化，以便崩溃恢复后新列定义不丢失。

**当前代码状态:** `CatalogManager` 已有 `getOrCreateDbLock()` 实现 per-DB ReentrantLock，已有 `IdGenerator` 分配全局唯一 ID，已有 `persistTableCreate()` 等 MTR 持久化模式可参考。需新增 `alterTableAddColumn()` 方法。

#### Acceptance Criteria

1. WHEN CatalogManager.alterTableAddColumn() 被调用, THE CatalogManager SHALL 通过 `getOrCreateDbLock()` 获取 per-DB ReentrantLock 保证同库 DDL 串行执行
2. WHEN CatalogManager.alterTableAddColumn() 被调用, THE CatalogManager SHALL 通过 IdGenerator 为新列分配全局唯一的 columnId
3. WHEN CatalogManager 执行 Instant ADD COLUMN, THE CatalogManager SHALL 调用 `SchemaRegistry.addColumnInstant()` 创建新版本的 RecordSchema 并注册 InstantColumnMeta
4. WHEN CatalogManager 执行 Instant ADD COLUMN, THE CatalogManager SHALL 重建 TableDescriptor 以包含新列（因 `columns` 为 `Collections.unmodifiableList`，不可原地修改）
5. WHEN CatalogManager 执行 Instant ADD COLUMN, THE CatalogManager SHALL 在 MiniTransaction 保护下持久化更新后的表元数据到 TableMetaPage
6. WHEN CatalogManager 执行 Instant ADD COLUMN, THE CatalogManager SHALL 更新 CatalogCache 中的 TableDescriptor 引用
7. IF CatalogManager 持久化过程中发生异常, THEN THE CatalogManager SHALL 保持 TableDescriptor 和 SchemaRegistry 的原始状态不变（先持久化，再更新内存）

### Requirement 6: TableDescriptor 不可变列表处理

**User Story:** 作为存储引擎开发者，我需要在 ADD COLUMN 后让 TableDescriptor 反映新的列结构，同时尊重其 columns 字段的不可变设计。

**当前代码状态:** `TableDescriptor.columns` 在构造器中通过 `Collections.unmodifiableList(new ArrayList<>(columns))` 创建，构造后不可变。`SchemaRegistry` 引用是 final 但其内部状态可变（`addColumnInstant()` 修改内部 map 和 volatile currentSchema）。

#### Acceptance Criteria

1. WHEN Instant ADD COLUMN 成功执行, THE CatalogManager SHALL 创建新的 TableDescriptor 实例，其 columns 列表包含新列的 ColumnMeta（含正确的 columnId、name、type、ordinal）
2. WHEN 创建新 TableDescriptor 时, THE CatalogManager SHALL 复用原 TableDescriptor 的 SchemaRegistry 引用（因 SchemaRegistry 内部已通过 addColumnInstant 更新）
3. WHEN 创建新 TableDescriptor 时, THE CatalogManager SHALL 保留原 TableDescriptor 的所有不可变字段（tableId、tableName、databaseId、spaceId、createTime）和可变字段（primaryIndexId、secondaryIndexIds）
4. THE 新 TableDescriptor 的 SchemaRegistry 的 currentSchema SHALL 指向包含新列的最新版本
5. THE TableDescriptor 的 SchemaRegistry SHALL 保留所有历史版本的 RecordSchema，以便旧行读取时使用对应版本的 schema

### Requirement 7: Instant 默认值填充（读路径）

**User Story:** 作为数据库用户，我希望在 ADD COLUMN 之后查询旧行时，新列能自动显示默认值，以便查询结果完整一致。

**当前代码状态:** `RowReader.read()` 已实现完整读路径：peekRowVersion → registry.get(rowVersion) → parseOffsets → decode → fillInstantDefaults。`SchemaRegistry.fillInstantDefaults()` 已实现按 columnId + introducedVersion 填充。无需修改读路径代码，仅需验证端到端正确性。

#### Acceptance Criteria

1. WHEN RowReader 读取 rowVersion 小于当前 schema version 的记录, THE SchemaRegistry.fillInstantDefaults() SHALL 为该记录填充新列的默认值（已实现）
2. WHEN RowReader 读取 rowVersion 等于当前 schema version 的记录, THE SchemaRegistry.fillInstantDefaults() SHALL 不修改该记录的任何字段（已实现）
3. THE RowReader SHALL 通过 peekRowVersion() 从记录本身获取 rowVersion，不允许由调用者传入（I5 不变量，已实现）
4. THE SchemaRegistry SHALL 严格按照 InstantColumnMeta 的 columnId 和 introducedVersion 规则填充默认值（I6 不变量，已实现）
5. WHEN 多次执行 ADD COLUMN 后读取最早版本的行, THE SchemaRegistry SHALL 为所有 introducedVersion 大于该行 rowVersion 的列填充默认值（已实现）

### Requirement 8: 写路径 Schema 版本对齐

**User Story:** 作为数据库用户，我希望在 ADD COLUMN 之后插入新行时，新行使用最新的 schema version 写入，以便新行包含所有列。

**当前代码状态:** `StorageDataSource.TableAccess.dml()` 已使用 `table.getSchemaRegistry().getCurrentSchema()` 获取最新 schema。`RecordFormat.encodeTo()` 已将 `currentSchema.version` 写入 ROW_VERSION 字段。需确保 DDL 后 tableCache 失效使 dml() 获取到更新后的 schema。

#### Acceptance Criteria

1. WHEN ADD COLUMN 之后执行 INSERT, THE StorageDataSource SHALL 使用 SchemaRegistry.getCurrentSchema() 获取最新 schema 进行编码（已实现，依赖 tableCache 失效后重建 TableAccess）
2. WHEN ADD COLUMN 之后执行 INSERT 且未提供新列的值, THE StorageDataSource SHALL 为 nullable 新列填充 NULL 值
3. WHEN ADD COLUMN 之后执行 INSERT, THE RecordFormat.encodeTo() SHALL 将 currentSchema.version 写入记录的 ROW_VERSION 字段（已实现）

### Requirement 9: DDL 后缓存失效

**User Story:** 作为存储引擎开发者，我希望 ADD COLUMN 执行后 StorageDataSource 的表缓存被正确失效，以便后续查询使用更新后的 schema。

**当前代码状态:** `StorageDataSource.tableCache` 是 `LinkedHashMap`，`openTable()` 用 `computeIfAbsent` 缓存。当前无任何 invalidate 机制。需要设计缓存失效触发方式。

**设计约束:** StorageCatalog 持有 CatalogManager 引用但不持有 StorageDataSource 引用。需要通过回调、事件或直接在 StorageDataSource 中添加 invalidate 方法，由 AlterTableExec 或 StorageCatalog 触发。

#### Acceptance Criteria

1. WHEN StorageCatalog.addColumn() 成功完成, THE 系统 SHALL 使 StorageDataSource 中缓存的对应表的 TableAccess 对象失效
2. THE 缓存失效机制 SHALL 通过以下方式之一实现：(a) StorageDataSource 暴露 `invalidateTable(String tableName)` 方法，由 AlterTableExec 调用；或 (b) DataSourceSpi 接口新增 `invalidateTable` 方法
3. WHEN 缓存失效后首次访问该表, THE StorageDataSource SHALL 重新从 CatalogManager 加载 TableDescriptor 并构建新的 TableAccess
4. WHEN 缓存失效后重建 TableAccess, THE TableAccess SHALL 使用更新后的 SchemaRegistry（含新版本 RecordSchema）和新的 columns 列表

### Requirement 10: 版本隔离不变性

**User Story:** 作为数据库用户，我希望 ADD COLUMN 不影响已有数据行的物理存储，以便 Instant DDL 真正做到零数据迁移。

**当前代码状态:** 读路径已通过 RowReader + SchemaRegistry 实现版本隔离。写路径已通过 currentSchema.version 写入 ROW_VERSION 实现版本标记。

#### Acceptance Criteria

1. THE CatalogManager SHALL 在执行 Instant ADD COLUMN 时不修改任何已有数据页的内容
2. WHEN 读取 ADD COLUMN 之前写入的行, THE RowReader SHALL 使用该行 rowVersion 对应的 RecordSchema 解析物理字段，再通过 fillInstantDefaults 补充新列（已实现）
3. WHEN 读取 ADD COLUMN 之后写入的行, THE RowReader SHALL 使用新版本的 RecordSchema 直接解析所有字段，无需填充默认值（已实现）
4. FOR ALL 有效的 DataTuple，先 INSERT 再 SELECT 读取 SHALL 产生等价的字段值（round-trip 属性）

### Requirement 11: 并发安全

**User Story:** 作为数据库用户，我希望 ADD COLUMN 在并发 DML 操作下安全执行，以便生产环境中不出现数据损坏。

**当前代码状态:** `CatalogManager` 已有 `getOrCreateDbLock()` 返回 per-DB ReentrantLock。`SchemaRegistry.currentSchema` 已是 `volatile`。

#### Acceptance Criteria

1. WHILE 有活跃的 DML 事务正在执行, THE CatalogManager SHALL 通过 per-DB ReentrantLock 保证 DDL 操作的原子性（已有基础设施）
2. THE SchemaRegistry 的 currentSchema 字段 SHALL 使用 volatile 修饰，以保证 DDL 完成后对所有线程立即可见（已实现）
3. WHEN 并发执行多个 ALTER TABLE ADD COLUMN, THE CatalogManager SHALL 串行处理同一数据库的 DDL 请求，每次 ADD COLUMN 递增 schema version
4. IF DDL 执行期间发生异常, THEN THE CatalogManager SHALL 保证 SchemaRegistry 和 TableDescriptor 回滚到 DDL 之前的状态（先持久化再更新内存的顺序保证）
