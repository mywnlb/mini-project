# 需求文档：Navicat 客户端兼容性支持

## 简介

Navicat 在连接 MySQL 兼容数据库时，会在握手和浏览阶段发送一系列探测查询（系统变量查询、元数据查询等）。当前 mini-db 的 SQL 解析器和协议拦截层无法正确处理其中部分查询，导致连接失败或功能异常。本需求旨在适配 Navicat 客户端的关键探测查询，使其能够正常连接并浏览 mini-db 数据库，同时不简单吞掉异常，而是返回系统已经能够提供的真实数据。

## 术语表

- **SQL_Parser**: mini-db 的 SQL 解析器（`cn.zhangyis.minidb.sql.parser.SqlParser`），负责将 SQL 文本解析为 AST
- **SystemVariableHandler**: MySQL 协议层的兼容性查询拦截器（`cn.zhangyis.minidb.server.handler.SystemVariableHandler`），在 SQL 引擎之前拦截客户端探测查询并直接返回响应
- **CommandDispatcher**: MySQL 协议层的命令分发器（`cn.zhangyis.minidb.server.handler.CommandDispatcher`），负责路由 COM_QUERY 等命令到对应处理逻辑
- **Set_Operation**: SQL 集合操作（UNION / UNION ALL / EXCEPT / INTERSECT），当前解析器仅支持两路集合操作
- **SHOW_Command**: MySQL 的 SHOW 系列命令，用于查询数据库元数据
- **Navicat_Probe_Query**: Navicat 客户端在连接和浏览过程中自动发送的探测查询

## 需求

### 需求 1：支持多路 UNION 链式查询解析

**用户故事：** 作为一个使用 Navicat 连接 mini-db 的开发者，我希望系统能正确解析包含多个 UNION 的链式查询，以便 Navicat 的系统变量探测查询不会因解析失败而中断连接。

**背景：** Navicat 在连接时会发送类似 `SELECT @@var1 UNION SELECT @@var2 UNION SELECT @@var3` 的多路 UNION 查询。当前 SQL_Parser 在 `parseStatement()` 中仅处理一次 UNION 操作，解析完第一个 `SELECT ... UNION SELECT ...` 后遇到第二个 `UNION` 关键字时，因为已不在 UNION 解析循环中，触发 "Extra tokens after statement" 错误。

#### 验收标准

1. WHEN SQL_Parser 解析包含两个或更多 UNION 关键字的 SELECT 语句时，THE SQL_Parser SHALL 将所有 UNION 操作解析为左结合的嵌套 SqlSetOperation AST 节点
2. WHEN SQL_Parser 解析 `SELECT a UNION SELECT b UNION ALL SELECT c` 时，THE SQL_Parser SHALL 生成等价于 `(SELECT a UNION SELECT b) UNION ALL SELECT c` 的 AST 结构
3. WHEN SQL_Parser 解析包含多路 UNION 的查询且末尾无多余 token 时，THE SQL_Parser SHALL 成功完成解析且不抛出 SqlParseException
4. WHEN SQL_Parser 解析包含多路 EXCEPT 或 INTERSECT 的查询时，THE SQL_Parser SHALL 同样支持链式解析

### 需求 2：支持多系统变量 SELECT 查询拦截

**用户故事：** 作为一个使用 Navicat 连接 mini-db 的开发者，我希望系统能正确处理同时查询多个系统变量的 SELECT 语句，以便 Navicat 的连接初始化阶段能获取所需的服务器配置信息。

**背景：** Navicat 在连接时会发送 `SELECT @@var1, @@var2, @@var3` 形式的多变量查询，或者通过 UNION 拼接多个 `SELECT @@var` 查询。当前 SystemVariableHandler 的 `SELECT_SYSVAR` 正则仅匹配单个 `SELECT @@variable` 模式，无法拦截多变量查询，导致这些查询落入 SQL 引擎后因不支持 `@@` 语法而报错。

#### 验收标准

1. WHEN SystemVariableHandler 接收到 `SELECT @@var1, @@var2, ...` 形式的多变量查询时，THE SystemVariableHandler SHALL 拦截该查询并返回包含所有请求变量值的单行结果集
2. WHEN SystemVariableHandler 接收到包含 `@@variable AS alias` 形式的别名语法时，THE SystemVariableHandler SHALL 使用别名作为结果列名
3. WHEN SystemVariableHandler 接收到包含 UNION 的多个 `SELECT @@variable` 查询时，THE SystemVariableHandler SHALL 拦截该查询并返回每个变量值作为独立行的结果集
4. WHEN 查询中引用的系统变量在 mini-db 中未定义时，THE SystemVariableHandler SHALL 返回空字符串作为该变量的值，而非抛出异常

### 需求 3：SHOW FULL TABLES 支持 WHERE 条件过滤

**用户故事：** 作为一个使用 Navicat 浏览 mini-db 数据库的开发者，我希望 `SHOW FULL TABLES WHERE Table_type != 'VIEW'` 能正确返回所有基表列表，以便 Navicat 的对象浏览器能正常显示表结构。

**背景：** Navicat 在浏览数据库时发送 `SHOW FULL TABLES WHERE Table_type != 'VIEW'` 来获取非视图的表列表。当前 SystemVariableHandler 的 SHOW_TABLES 正则能匹配该语句，但 `handleShowTables` 方法忽略了 WHERE 条件，直接返回所有表且 Table_type 固定为 `BASE TABLE`。由于 mini-db 不支持视图，所有表都是基表，因此当前行为在语义上是正确的，但需要确保 WHERE 子句不会导致匹配失败或异常。

#### 验收标准

1. WHEN SystemVariableHandler 接收到 `SHOW FULL TABLES WHERE Table_type != 'VIEW'` 时，THE SystemVariableHandler SHALL 返回当前数据库中所有表的列表，每行包含表名和 `BASE TABLE` 类型
2. WHEN SystemVariableHandler 接收到 `SHOW FULL TABLES FROM db_name WHERE Table_type != 'VIEW'` 时，THE SystemVariableHandler SHALL 正确匹配该语句并返回结果
3. THE SystemVariableHandler 的 SHOW_TABLES 正则 SHALL 匹配包含 `FROM db_name` 子句的 SHOW TABLES 变体

### 需求 4：SHOW TABLE STATUS 支持 FROM 和 LIKE 子句

**用户故事：** 作为一个使用 Navicat 浏览 mini-db 数据库的开发者，我希望 `SHOW TABLE STATUS FROM db_name` 和 `SHOW TABLE STATUS LIKE 'pattern'` 能正确返回表元数据，以便 Navicat 能正常显示表的详细信息。

**背景：** Navicat 在浏览表详情时发送 `SHOW TABLE STATUS FROM db_name` 或 `SHOW TABLE STATUS FROM db_name LIKE 'table_name'`。当前 SHOW_TABLE_STATUS 正则能匹配这些变体，但 `handleShowTableStatus` 方法不解析 FROM 和 LIKE 子句，始终返回当前数据库的所有表。需要适配 LIKE 子句以支持按表名过滤。

#### 验收标准

1. WHEN SystemVariableHandler 接收到 `SHOW TABLE STATUS FROM db_name` 时，THE SystemVariableHandler SHALL 返回该数据库中所有表的状态信息（18 列标准格式）
2. WHEN SystemVariableHandler 接收到 `SHOW TABLE STATUS LIKE 'pattern'` 时，THE SystemVariableHandler SHALL 仅返回表名匹配该 LIKE 模式的表状态信息
3. WHEN SystemVariableHandler 接收到 `SHOW TABLE STATUS FROM db_name LIKE 'pattern'` 时，THE SystemVariableHandler SHALL 结合数据库名和 LIKE 模式过滤返回结果
4. THE SystemVariableHandler SHALL 支持 LIKE 模式中的 `%` 通配符（匹配任意字符序列）和 `_` 通配符（匹配单个字符）

### 需求 5：SHOW FULL TABLES 支持 FROM 子句

**用户故事：** 作为一个使用 Navicat 连接 mini-db 的开发者，我希望 `SHOW FULL TABLES FROM db_name` 能正确返回指定数据库的表列表，以便 Navicat 在切换数据库时能正常加载表结构。

**背景：** Navicat 在切换数据库或刷新对象浏览器时发送 `SHOW FULL TABLES FROM db_name WHERE Table_type != 'VIEW'`。当前 SHOW_TABLES 正则不包含 `FROM db_name` 子句的匹配，可能导致匹配失败。

#### 验收标准

1. WHEN SystemVariableHandler 接收到 `SHOW TABLES FROM db_name` 时，THE SystemVariableHandler SHALL 返回该数据库中所有表的列表
2. WHEN SystemVariableHandler 接收到 `SHOW FULL TABLES FROM db_name` 时，THE SystemVariableHandler SHALL 返回该数据库中所有表的列表，每行包含表名和 `BASE TABLE` 类型
3. THE SystemVariableHandler 的 SHOW_TABLES 正则 SHALL 匹配 `SHOW [FULL] TABLES [FROM db_name] [WHERE ...]` 的所有合法组合

### 需求 6：SHOW DATABASES 返回有效数据库列表

**用户故事：** 作为一个使用 Navicat 连接 mini-db 的开发者，我希望 `SHOW DATABASES` 能返回可用的数据库列表，以便 Navicat 的数据库下拉列表能正常显示。

**背景：** 当前 `SHOW DATABASES` 被 SHOW_MISC 正则匹配后返回空结果集。Navicat 依赖该命令填充数据库列表，空结果会导致用户无法在 Navicat 中选择数据库。应返回系统已知的数据库名称（至少包含当前连接的默认数据库）。

#### 验收标准

1. WHEN SystemVariableHandler 接收到 `SHOW DATABASES` 时，THE SystemVariableHandler SHALL 返回包含至少一个数据库名称的结果集，结果列名为 `Database`
2. THE SystemVariableHandler SHALL 在 SHOW DATABASES 结果中包含当前连接的默认数据库名称
3. IF 当前连接未设置默认数据库，THEN THE SystemVariableHandler SHALL 返回包含 `minidb` 作为默认数据库名的结果集

### 需求 7：多语句分号拆分兼容性增强

**用户故事：** 作为一个使用 Navicat 连接 mini-db 的开发者，我希望系统能正确处理 Navicat 发送的以分号分隔的多语句查询，以便所有语句都能被正确执行或拦截。

**背景：** CommandDispatcher 当前使用简单的 `split(";")` 进行多语句拆分，这在字符串字面量中包含分号时会产生错误拆分。Navicat 可能发送包含字符串字面量的多语句查询。

#### 验收标准

1. WHEN CommandDispatcher 接收到以分号分隔的多条 SQL 语句时，THE CommandDispatcher SHALL 依次执行每条语句并返回对应的响应
2. WHEN 多语句中某条语句被 SystemVariableHandler 拦截时，THE CommandDispatcher SHALL 对该语句返回拦截结果，并继续处理后续语句
3. IF 多语句中某条语句执行失败，THEN THE CommandDispatcher SHALL 返回该语句的错误信息并停止处理后续语句
