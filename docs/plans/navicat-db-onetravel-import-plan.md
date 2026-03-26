# `db_onetravel.sql` 适配计划

## Summary

目标是让 mini-db 能尽可能兼容 Navicat/MySQL 8 导出的
[`db_onetravel.sql`](/C:/coding/java/self/miniproject/miniproject/mini-db/src/test/java/cn/zhangyis/minidb/sql/db_onetravel.sql)，
并把它作为稳定回归样本使用。

本轮目标明确为：

- 导入主流 DDL 与普通 `INSERT`
- 保留并落地表级 `UNIQUE KEY` / `INDEX`
- 原生支持 `DECIMAL`、`DATETIME`、`TIME`
- 支持十六进制 `0x...` BLOB 字面量插入
- 对 `ENGINE`、`ROW_FORMAT`、`CHARACTER SET`、`COLLATE`、`COMMENT`、`FOREIGN KEY` 采取“解析并忽略”策略
- 对文件末尾的 `CREATE FUNCTION` 等过程式对象先识别并跳过，不阻断整份脚本导入

关键事实：

- 当前 SQL 导入链会直接跳过表级 `UNIQUE KEY/INDEX`
- 当前 `StorageCatalog` 仍把 `DECIMAL` / `DATETIME` 降级映射到 `BIGINT`
- 样本中存在真实 `0x...` 十六进制 BLOB 插入，不是只有 BLOB 列定义
- 样本末尾存在 `CREATE FUNCTION queryParentCategoryInfo(...)`，包含 `DECLARE`、`WHILE`、`SELECT ... INTO`，不属于普通 DDL/DML 兼容范围

## Key Changes

### 1. 脚本执行器与导入边界

新增 `SqlScriptRunner` 或等价工具，职责如下：

- 读取整份 SQL 脚本
- 复用现有多语句切分逻辑，避免维护两套分号拆分规则
- 处理 `/* ... */` 和 `-- ...` 注释
- 统一处理 dump 头部 `SET ...`
- 对过程式对象做显式跳过统计，而不是让普通 SQL 引擎半解析后失败

导入范围固定为：

- `DROP TABLE IF EXISTS`
- `CREATE TABLE`
- `INSERT INTO ... VALUES ...`
- dump 头部 `SET ...`

首轮明确跳过：

- `CREATE FUNCTION`
- `CREATE PROCEDURE`
- `DECLARE`
- `WHILE`
- `SELECT ... INTO`
- `CALL`

跳过策略：

- 标记为 `skipped_unsupported`
- 不中断后续语句执行
- 在最终导入摘要中输出对象名与原因

### 2. Lexer / Parser 兼容 MySQL dump 语法

#### Lexer

在 `SqlLexer` 中增加：

- `/* ... */` 注释跳过
- `-- ` 行注释跳过
- 十六进制字面量 `0x...` 识别

#### Parser

在 `SqlParser` 中扩展 `CREATE TABLE`：

- 列级接受并忽略：
  - `CHARACTER SET`
  - `COLLATE`
  - `DEFAULT`
  - `AUTO_INCREMENT`
  - `UNSIGNED`
  - `COMMENT`
- 表级接受并解析：
  - `PRIMARY KEY`
  - `UNIQUE KEY`
  - `KEY`
  - `INDEX`
- 表级接受并忽略：
  - `CONSTRAINT ... FOREIGN KEY ... REFERENCES ... ON DELETE/UPDATE ...`
  - `USING BTREE`
- 表尾接受并忽略：
  - `ENGINE = ...`
  - `CHARACTER SET = ...`
  - `COLLATE = ...`
  - `COMMENT = ...`
  - `ROW_FORMAT = ...`

额外兼容的样本类型名：

- `MEDIUMTEXT` -> `TEXT`
- `MEDIUMBLOB` -> `BLOB`
- `JSON`
- `TIME`
- `CHAR(n)`
- `TINYINT`
- `SMALLINT`

### 3. 表级索引端到端接通

当前内核层并非没有索引能力：

- `storage.btree.IndexType` 已有 `PRIMARY`、`UNIQUE`、`SECONDARY`
- `UniqueBTree` 已有唯一性检查语义
- `CatalogManager.createIndexes(...)` 已能按 `IndexType` 创建真实索引

当前缺口在 SQL 导入链：

- `SqlParser` 目前会跳过表级 `UNIQUE KEY/INDEX`
- `CreateTableExec` 只传列与主键信息，不传表级索引
- `StorageCatalog.createIndex(...)` 当前一律按 `SECONDARY` 创建，未使用 `IndexMeta.unique()`

需要的改动：

- 扩展 `SqlCreateTable` / `RelCreateTable`，增加表级索引定义承载
- 扩展 `CreateTableExec`，把主键、唯一索引、普通索引一起传给 catalog
- 扩展 `CatalogSpi.createTable(...)` 或新增兼容入口，使建表可同时带索引定义
- 在 `StorageCatalog.createTable(...)` 中映射：
  - `PRIMARY KEY` -> `IndexType.PRIMARY`
  - `UNIQUE KEY` -> `IndexType.UNIQUE`
  - `KEY/INDEX` -> `IndexType.SECONDARY`
- 修复 `StorageCatalog.createIndex(...)`，使其尊重 `IndexMeta.primary()/unique()`

### 4. 二级索引与唯一约束的 DML 维护

本项不能只做到“建出来”，还要保证后续写入一致：

- `StorageDataSource` 当前写路径主要围绕主键树
- `supportsLookup()` 也只认单列主键

因此需要补齐：

- insert 时同步维护普通索引与唯一索引
- update 时处理索引键变化前后的删除/插入
- delete 时同步移除二级索引项
- 唯一索引写入时执行重复键检查，而不是只在主键上检查

注意：

- 查询优化是否立刻利用这些二级索引可以后置
- 但唯一索引约束必须在本轮生效

### 5. `DECIMAL`、`DATETIME`、`TIME` 原生类型支持

当前问题：

- `SqlType` 虽有 `DECIMAL`、`DATETIME`
- 但 `StorageCatalog.sqlTypeToFieldType(...)` 仍把它们降级到 `BIGINT`
- `FieldKind` 目前没有 `DECIMAL`、`DATETIME`、`TIME`

因此本轮不是“接线”，而是“扩内核类型”：

- 在 `FieldKind` / `FieldType` 中新增：
  - `DECIMAL`
  - `DATETIME`
  - `TIME`
- 定义物理表示：
  - `DECIMAL`：保留 precision / scale 元数据与稳定编码
  - `DATETIME`：统一日期时间编码，不再伪装成 `BIGINT`
  - `TIME`：独立时间类型编码
- 同步修改：
  - `SqlType`
  - `TypeCoercion`
  - `StorageCatalog`
  - `StorageDataSource`
  - `TypeMapping`
  - `DataField`
  - 记录编解码路径

目标不是名义支持，而是避免再走 `BIGINT` 降级路径。

### 6. 十六进制 BLOB 字面量支持

样本中存在真实语句，例如：

- `INSERT INTO tb_sys_config ... 0x...`
- `INSERT INTO tb_merchant_pay ... 0x...`

因此必须支持：

- lexer 识别 `0x...`
- parser 产出二进制字面量节点或等价值节点
- `TypeCoercion` 将其转为 `byte[]`
- `StorageDataSource` 对 `BLOB` / `MEDIUMBLOB` 路径接收 `byte[]`
- 查询读取时至少保证不崩溃；若协议层暂不做精确二进制展示，首轮可先保证写入与存在性校验

### 7. `SET` 兼容统一

复用现有 `SystemVariableHandler` 兼容逻辑，保证脚本导入路径和网络协议路径表现一致。

至少吞掉：

- `SET NAMES utf8mb4`
- `SET FOREIGN_KEY_CHECKS = 0`
- 其他 dump 里的通用 `SET ...`

不额外引入完整 `SET` AST。

## Test Plan

### 1. 词法 / 脚本测试

- `/* Navicat dump */` 可跳过
- `-- Table structure ...` 可跳过
- 分号切分不会被字符串、反引号、注释干扰
- `SET NAMES` / `SET FOREIGN_KEY_CHECKS` 走 no-op 成功路径

### 2. DDL 兼容测试

覆盖代表性 `CREATE TABLE`，确认以下项不再报错：

- `ENGINE / ROW_FORMAT / CHARACTER SET / COLLATE / COMMENT`
- `PRIMARY KEY`
- `UNIQUE KEY`
- `KEY / INDEX`
- `FOREIGN KEY / REFERENCES`
- `TINYINT / SMALLINT / CHAR / TEXT / JSON / TIME / BLOB / MEDIUMBLOB`

### 3. 索引语义测试

- 建表时普通索引和唯一索引被真实落地到 catalog
- 唯一索引重复插入时报错
- 普通索引允许重复值
- update 修改索引键时，索引项同步更新
- delete 后二级索引项同步移除

### 4. 类型语义测试

- `DECIMAL(65,2)` 插入和读取不再走 `BIGINT`
- `DATETIME` 插入和读取保持日期时间语义
- `TIME` 插入和读取保持时间语义
- `0x...` BLOB 字面量可插入 BLOB 列

### 5. 全量脚本回归

对 `db_onetravel.sql` 执行整份导入，验收：

- 主流表结构成功导入
- 普通 `INSERT` 绝大多数成功
- 表级 `UNIQUE KEY/INDEX` 被保留
- BLOB 十六进制插入成功
- `CREATE FUNCTION queryParentCategoryInfo` 被明确跳过并记录
- 最终输出导入摘要

## Assumptions

- 本轮将普通索引、唯一索引、`DECIMAL`、`DATETIME`、`TIME` 都纳入实现范围。
- 不再接受 `DECIMAL/DATETIME/TIME -> BIGINT` 的旧降级逻辑。
- 外键、字符集、排序规则、引擎、行格式仍只做语法兼容，不做真实语义。
- `CREATE FUNCTION` 等过程式对象本轮不实现，只做安全跳过。
