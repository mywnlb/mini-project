# Implementation Plan: ALTER TABLE ADD COLUMN (Instant DDL)

## Overview

按设计文档的 12 步实现顺序，从 AST 扩展到存储层 CatalogManager，逐步打通 `ALTER TABLE ADD COLUMN` 的完整链路。每步可独立编译，测试在最后统一编写。核心原则：先持久化再更新内存（INV-1），TableDescriptor 重建而非修改（INV-4），DDL 后缓存失效（INV-5）。

## Tasks

- [x] 1. 扩展 SQL AST 和 Catalog 数据结构
  - [x] 1.1 扩展 SqlAlterTable record，新增 nullable 和 defaultValue 字段
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/ast/SqlAlterTable.java`
    - record 扩展为 `(table, columnName, columnType, nullable, defaultValue)`
    - 新增兼容工厂方法 `SqlAlterTable.of(table, columnName, columnType)` 默认 `nullable=true, defaultValue=null`
    - 更新所有现有调用点使用工厂方法保持向后兼容
    - _Requirements: 1.4, 1.1, 1.2_

  - [x] 1.2 扩展 SQL 层 ColumnMeta record，新增 nullable 和 defaultValue 字段
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/catalog/ColumnMeta.java`
    - record 扩展为 `(name, type, isPrimaryKey, nullable, defaultValue)`
    - 保留原三参数构造便捷方法或更新所有调用点
    - _Requirements: 1.5, 3.3_

- [x] 2. 增强 SqlParser 解析 NOT NULL / DEFAULT 子句
  - [x] 2.1 修改 SqlParser.parseAlterTable() 支持 NOT NULL 和 DEFAULT 子句
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/parser/SqlParser.java`
    - 解析 `<type>` 后检查 `NOT NULL` token，设置 `nullable=false`
    - 解析 `DEFAULT <literal>` 子句，提取默认值
    - 构造扩展后的 `SqlAlterTable(table, col, type, nullable, defaultValue)`
    - _Requirements: 1.1, 1.2, 1.3_

- [x] 3. 增强 SqlValidator 验证逻辑
  - [x] 3.1 新增 NOT NULL 无默认值拒绝逻辑
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/validation/SqlValidator.java`
    - 在 `validateAlterTable()` 现有检查之后新增：`!alter.nullable() && alter.defaultValue() == null` → 抛出 ValidationException
    - 错误消息包含 "Instant DDL requires nullable column or explicit DEFAULT value"
    - _Requirements: 2.5, INV-7_


- [x] 4. 扩展 DataSourceSpi 接口和 StorageDataSource 缓存失效
  - [x] 4.1 在 DataSourceSpi 接口新增 invalidateTable() default 方法
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/DataSourceSpi.java`
    - 新增 `default void invalidateTable(String tableName) {}` 空实现
    - MockDataSource 等无需缓存失效的实现自动继承空实现
    - _Requirements: 9.2_

  - [x] 4.2 在 StorageDataSource 实现 invalidateTable()
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/StorageDataSource.java`
    - 实现 `invalidateTable(tableName)` → `tableCache.remove(tableName.toUpperCase())`
    - _Requirements: 9.1, 9.3, INV-5_

- [x] 5. Checkpoint - 确保 SQL 层修改编译通过
  - 确保所有修改编译通过，现有测试不受影响，ask the user if questions arise.

- [x] 6. 实现 CatalogManager.alterTableAddColumn() 核心方法
  - [x] 6.1 新增 CatalogManager.alterTableAddColumn() 方法
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/storage/catalog/CatalogManager.java`
    - 方法签名：`public void alterTableAddColumn(String dbName, String tableName, String columnName, FieldType fieldType, DataField defaultValue)`
    - 执行流程：ensureInitialized() → getOrCreateDbLock(dbName) → lock.lock()
    - 在 lock 保护下：获取旧 TableDescriptor → 分配 columnId (INV-6) → 构造 ColumnDescriptor
    - 调用 SchemaRegistry.addColumnInstant() 创建新版本 schema
    - 重建 TableDescriptor（INV-4：不可修改原实例）
    - MTR 持久化 → 更新 CatalogCache（INV-1：先持久化再更新内存）
    - finally 块中 lock.unlock()
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.6, 5.7, 6.1, 6.2, 6.3, INV-1, INV-4, INV-6_

  - [x] 6.2 新增 persistTableUpdate() 私有方法
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/storage/catalog/CatalogManager.java`
    - 参考现有 `persistTableCreate()` 的 MTR 持久化模式
    - 使用 MiniTransaction 获取 CatalogMetaPage → 定位 TableMetaPage → 覆盖写入更新后的表元数据
    - 如需新增 `TableMetaPage.updateTable()` 方法则一并实现
    - _Requirements: 5.5, INV-1_

- [x] 7. 实现 StorageCatalog.addColumn() 桥接逻辑
  - [x] 7.1 替换 StorageCatalog.addColumn() 中的 UnsupportedOperationException
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/catalog/StorageCatalog.java`
    - 调用 `requireBufferPool("addColumn")`
    - 通过 `sqlTypeToFieldType()` 转换 SQL 类型为存储层 FieldType
    - 处理 nullable 标记：`fieldType = fieldType.withNullable(true)` 或等价方式
    - 转换默认值为 DataField（新增 `convertDefaultValue()` 辅助方法）
    - 委托 `catalogManager.alterTableAddColumn(databaseName, tableName, column.name(), fieldType, defaultField)`
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 8. 更新执行器和物理计划器，注入 DataSourceSpi
  - [x] 8.1 更新 AlterTableExec 构造器接收 DataSourceSpi，open() 传递扩展 ColumnMeta + 触发缓存失效
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/AlterTableExec.java`
    - 构造器新增 `DataSourceSpi dataSource` 参数
    - `open()` 中构造包含 nullable/defaultValue 的 ColumnMeta
    - `catalog.addColumn()` 成功后调用 `dataSource.invalidateTable(tableName)` (INV-5)
    - _Requirements: 3.3, 9.1, INV-5_

  - [x] 8.2 可选：更新 RelAlterTable 传递 DataSourceSpi 引用
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/rel/RelAlterTable.java`（如需要）
    - _Requirements: 3.3_

  - [x] 8.3 更新 PhysicalPlanner 注入 dataSource 给 AlterTableExec
    - 修改 `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`
    - `RelAlterTable` 分支传递 `dataSource` 给 `AlterTableExec` 构造器
    - _Requirements: 3.2, 9.2_

- [x] 9. Checkpoint - 确保完整链路编译通过
  - 确保所有修改编译通过，现有测试不受影响，ask the user if questions arise.


- [x] 10. 编写端到端测试和属性测试
  - [x] 10.1 编写 Test 1：端到端 ADD COLUMN + SELECT 旧行默认值填充
    - 创建表 → 插入数据 → ALTER TABLE ADD COLUMN age INT DEFAULT 0 → SELECT 验证旧行 age=0
    - 插入新行含 age=25 → SELECT 验证新行 age=25
    - _Requirements: 7.1, 7.4, 8.1, 10.2, 10.3, INV-3_

  - [x] 10.2 编写 Test 2：连续两次 ADD COLUMN + 多版本读取
    - 创建表 → 插入 row1(v1) → ADD COLUMN a → 插入 row2(v2) → ADD COLUMN b → 插入 row3(v3)
    - SELECT 验证：row1 两列均为默认值，row2 仅 b 为默认值，row3 无默认值填充
    - _Requirements: 7.5, 10.2, 10.3, INV-3_

  - [x] 10.3 编写 Test 3：SqlValidator 拒绝 NOT NULL 无默认值
    - ALTER TABLE ADD COLUMN c INT NOT NULL → 断言抛出 ValidationException
    - ALTER TABLE ADD COLUMN c INT NOT NULL DEFAULT 0 → 断言成功执行
    - _Requirements: 2.5, INV-7_

  - [x] 10.4 编写 Test 4：DDL 后缓存失效验证
    - 创建表 → 插入数据 → SELECT 触发 tableCache 加载 → ADD COLUMN → SELECT 验证新 schema 生效
    - _Requirements: 9.1, 9.3, 9.4, INV-5_

  - [ ]* 10.5 编写 Test 5：Schema 版本注册正确性（属性测试）
    - **Property P3: Schema 版本单调递增**
    - 直接调用 CatalogManager.alterTableAddColumn()
    - 断言 SchemaRegistry.getCurrentSchema().getVersion() == oldVersion + 1
    - 断言旧版本 schema 仍可通过 registry.get(oldVersion) 获取
    - 断言新列 columnId 由 IdGenerator 分配，全局唯一
    - **Validates: Requirements 5.2, 5.3, 6.4, 6.5, INV-6**

  - [ ]* 10.6 编写 Test 6：并发 DDL 串行化
    - **Property P5: DDL 原子性**
    - 并发提交 10 个 ALTER TABLE ADD COLUMN (col_0 到 col_9)
    - 断言所有 ADD COLUMN 成功，最终 schema version == 初始 + 10
    - 断言所有 columnId 唯一，SchemaRegistry 包含 11 个版本
    - **Validates: Requirements 11.3, 11.4, INV-6**

  - [ ]* 10.7 编写属性测试：Round-Trip 一致性
    - **Property P1: Round-Trip 属性**
    - 对于任意有效 DataTuple，INSERT 后 SELECT 读取结果字段值一致
    - **Validates: Requirements 10.4**

  - [ ]* 10.8 编写属性测试：Instant 默认值一致性
    - **Property P2: Instant 默认值一致性**
    - ADD COLUMN 之前写入的行（rowVersion < newVersion），读取时新列值等于 InstantColumnMeta.getDefaultValue()
    - **Validates: Requirements 7.1, 7.4, INV-3**

  - [ ]* 10.9 编写属性测试：零数据迁移
    - **Property P4: 零数据迁移**
    - alterTableAddColumn() 执行期间不修改任何已有数据页（仅修改 TableMetaPage）
    - **Validates: Requirements 10.1**

- [x] 11. Final checkpoint - 确保所有测试通过
  - 确保所有测试通过，ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选，可跳过以加速 MVP 交付
- 每个任务引用具体的 Requirements 编号以保证可追溯性
- Checkpoint 任务确保增量验证
- 属性测试验证设计文档中的 P1-P5 正确性属性
- 单元测试验证具体场景和边界条件
- 实现顺序严格遵循设计文档的 12 步依赖关系
