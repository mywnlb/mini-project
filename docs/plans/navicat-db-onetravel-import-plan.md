# `db_onetravel.sql` 适配计划

## Summary

目标是让 mini-db 能兼容 Navicat/MySQL 8 导出的
[`db_onetravel.sql`](/C:/coding/java/self/miniproject/miniproject/mini-db/src/test/java/cn/zhangyis/minidb/sql/db_onetravel.sql)，
并把它作为稳定回归样本使用。

本轮目标：

- 导入主流 DDL 与普通 `INSERT`
- 保留并落地表级 `UNIQUE KEY` / `INDEX`
- 原生支持 `DECIMAL`、`DATETIME`、`TIME`
- 支持十六进制 `0x...` BLOB 字面量插入
- 对 `ENGINE`、`ROW_FORMAT`、`CHARACTER SET`、`COLLATE`、`COMMENT`、`FOREIGN KEY` 采取"解析并忽略"策略

## 实现状态审计（经逐文件验证）

### 已完成项（无需改动）

| 项目 | 实现位置 | 验证状态 |
|------|----------|----------|
| Lexer: `/* */`、`-- `、`0x...` hex | `SqlLexer.java:119-141, 190-198` | ✅ |
| Parser: UNIQUE KEY / KEY / INDEX / FOREIGN KEY | `SqlParser.java:369-390, 1147-1197` | ✅ |
| Parser: CHARACTER SET / COLLATE / COMMENT / AUTO_INCREMENT / UNSIGNED | `SqlParser.java:479-502` | ✅ |
| Parser: ENGINE / ROW_FORMAT 等表尾选项 | `SqlParser.java:1199-1211` | ✅ |
| Parser: MEDIUMTEXT→TEXT / MEDIUMBLOB→BLOB / JSON / TIME / CHAR / TINYINT / SMALLINT | `SqlParser.parseColumnType():510-546` | ✅ |
| SqlCreateTable AST 承载 TableIndexDef(name, columns, primary, unique) | `SqlCreateTable.java:9-33` | ✅ |
| CreateTableExec 遍历索引并传 unique 标志 | `CreateTableExec.java:38-44` | ✅ |
| StorageCatalog.createIndex() 区分 UNIQUE/SECONDARY | `StorageCatalog.java:271` | ✅ |
| StorageCatalog.sqlTypeToFieldType() 原生映射 DECIMAL/DATE/TIME/DATETIME | `StorageCatalog.java:425-442` | ✅ |
| FieldKind 包含 DECIMAL/DATETIME/TIME/DATE | `FieldKind.java` | ✅ |
| TypeCoercion 处理全部类型转换 | `TypeCoercion.java:37-52` | ✅ |
| DataField 工厂方法: decimal/date/time/datetime | `DataField.java` | ✅ |
| StorageDataSource 二级索引维护 (insert/update/delete) | `StorageDataSource.java:106-335` | ✅ |
| UniqueBTree 唯一约束检查 | `UniqueBTree.java` | ✅ |
| 多语句引号感知分号拆分 | `CommandDispatcher.splitStatements():78-118` | ✅ |

### 索引路径端到端验证

**聚簇索引（PRIMARY KEY）：**
- `StorageCatalog.createTable()` → `IndexDefinition.primary()` → `CatalogManager.createTable()` 原子创建
- 数据通过 `rowToTuple()` 存储在主键 B+Tree 叶子节点（真正聚簇存储）
- 查询走 `supportsLookup()` + `loadCommittedRow()` B+Tree 直接查找

**唯一索引（UNIQUE KEY）：**
- `CreateTableExec:38-44` → `catalog.createIndex(IndexMeta(..., unique=true))`
- `StorageCatalog.createIndex():271` → `IndexType.UNIQUE`
- INSERT 时 `ensureSecondaryIndexesAvailable():363-393` 唯一约束检查
- COMMIT 时 `SecondaryIndexAccess.insert()` 防御性检查

**脚本导入场景下索引可见性：** `loadTableAccess()` 在首次 INSERT 时触发，此时 `TableDescriptor.secondaryIndexIds` 已包含所有索引。

### 剩余工作

**仅需新增一个脚本执行器 + 集成测试，零现有文件修改：**

1. `SqlScriptRunner.java` — SQL 脚本导入工具类
2. `SqlScriptRunnerTest.java` — 集成测试（含聚簇索引 / 唯一索引语义验证）

## Key Changes

### 1. 新增 SqlScriptRunner

**路径:** `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/SqlScriptRunner.java`

职责：
- 逐行读取 SQL 脚本
- 以 `;` 为语句边界累积完整语句
- 分类语句（SET / DROP TABLE / CREATE TABLE / INSERT / UNKNOWN）
- SET 语句直接跳过（不进入 SqlSession）
- DDL/DML 委托 `SqlSession.execute()` 执行
- 每条语句独立 try/catch，失败不中断后续导入
- 输出导入摘要（tables/rows/skipped/errors）

### 2. 集成测试

**路径:** `mini-db/src/test/java/cn/zhangyis/minidb/sql/SqlScriptRunnerTest.java`

## Test Plan

### 1. 基础导入测试
- SET + DROP TABLE + CREATE TABLE + INSERT 小脚本
- 验证 ImportSummary 各计数正确

### 2. 聚簇索引语义测试
- CREATE TABLE 带 PRIMARY KEY
- INSERT 多行并 SELECT 验证数据完整
- INSERT 重复主键 → Duplicate primary key 异常
- 复合主键同样验证

### 3. 唯一索引语义测试
- CREATE TABLE 带 UNIQUE KEY
- INSERT 唯一值 → 成功
- INSERT 重复值 → Duplicate key for unique index 异常
- 通过 `catalog.getIndexes()` 验证索引真实落地

### 4. 普通索引测试
- CREATE TABLE 带 KEY / INDEX
- INSERT 重复值 → 成功（非唯一索引允许重复）

### 5. SET 语句跳过测试
- 纯 SET 脚本验证 statementsSkipped 计数

### 6. 全量 db_onetravel.sql 回归
- BufferPool 加大到 2048+ 页
- `tablesCreated >= 300`
- `rowsInserted > 0`
- 选取代表性表验证：
  - 含 UNIQUE KEY 的表 → 索引存在
  - 含复合 PRIMARY KEY 的表 → 数据可查
  - 含 DECIMAL/DATETIME 列的表 → 类型正确
- 输出错误列表供调查

## Assumptions

- 外键、字符集、排序规则、引擎、行格式仅做语法兼容，不做真实语义。
- 文件末尾 `CREATE FUNCTION` 已被用户删除，不需要 delimiter 切换处理。
