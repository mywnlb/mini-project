# 实现计划：Navicat 客户端兼容性支持

## 概述

本计划将设计文档中的 7 个改动点转化为增量编码任务。改动范围限定在 SQL 解析层（`sql/parser`）和协议处理层（`server/handler`），不涉及存储引擎。每个任务构建在前一个任务之上，最终通过集成测试验证端到端行为。

## 任务

- [x] 1. SqlParser 多路集合操作支持
  - [x] 1.1 修改 `SqlParser.parseStatement()` 中 SELECT 分支，将 UNION/EXCEPT/INTERSECT 的 `if` 改为 `while` 循环
    - 文件：`mini-db/src/main/java/cn/zhangyis/minidb/sql/parser/SqlParser.java`
    - 每次迭代将前一个结果作为 `SqlSetOperation.left`，新解析的 SELECT 作为 `right`，形成左结合嵌套
    - 保持 `UNION ALL` / `EXCEPT ALL` / `INTERSECT ALL` 的 `all` 标志正确传递
    - _需求：1.1, 1.2, 1.3, 1.4_

  - [ ]* 1.2 编写 SqlParser 多路集合操作单元测试
    - 文件：`mini-db/src/test/java/cn/zhangyis/minidb/sql/SqlParserSetOperationTest.java`
    - 测试用例：两路 UNION、三路 UNION、混合 UNION/EXCEPT/INTERSECT、UNION ALL
    - 验证 AST 结构为左结合嵌套，验证 `opType` 和 `all` 标志正确
    - _需求：1.1, 1.2, 1.3, 1.4_

  - [ ]* 1.3 编写属性测试：多路集合操作左结合解析
    - 文件：`mini-db/src/test/java/cn/zhangyis/minidb/sql/SqlParserSetOperationPropertyTest.java`
    - 在 `mini-db/mini-db.gradle` 中添加 jqwik 依赖：`testImplementation 'net.jqwik:jqwik:1.8.2'`
    - **Property 1: 多路集合操作左结合解析**
    - 生成 2-5 个简单 SELECT，随机选择 UNION/EXCEPT/INTERSECT 连接，解析后验证 AST 节点数为 N-1，且最左 SELECT 为最深左叶子
    - **验证：需求 1.1, 1.3, 1.4**

- [x] 2. 检查点 — 确保 SqlParser 改动编译通过且测试通过
  - 确保所有测试通过，如有问题请询问用户。

- [x] 3. SystemVariableHandler 多系统变量 SELECT 拦截
  - [x] 3.1 新增 `SELECT_MULTI_SYSVAR` 和 `SELECT_SYSVAR_UNION` 正则模式
    - 文件：`mini-db/src/main/java/cn/zhangyis/minidb/server/handler/SystemVariableHandler.java`
    - `SELECT_MULTI_SYSVAR`：匹配 `SELECT @@var1 [AS alias1], @@var2 [AS alias2], ...`
    - `SELECT_SYSVAR_UNION`：匹配 `SELECT @@var1 UNION SELECT @@var2 UNION ...`
    - _需求：2.1, 2.2, 2.3_

  - [x] 3.2 在 `tryHandle` 中添加多变量 SELECT 处理分支
    - 多变量逗号形式：解析每个 `@@variable [AS alias]` 项，返回单行多列结果集
    - UNION 形式：解析每个 `SELECT @@variable`，返回多行单列结果集
    - 未知变量返回空字符串（复用 `resolveSystemVariable` 的 default 分支）
    - 新增分支必须在现有 `SELECT_SYSVAR` 单变量匹配之前检查
    - _需求：2.1, 2.2, 2.3, 2.4_

  - [x] 3.3 在 `executeIntercepted`、`canHandle`、`resultMetadata` 中同步添加多变量 SELECT 分支
    - 保持四个方法（tryHandle / executeIntercepted / canHandle / resultMetadata）的匹配逻辑一致
    - _需求：2.1, 2.2, 2.3, 2.4_

  - [ ]* 3.4 编写属性测试：多变量 SELECT 拦截正确性
    - 文件：`mini-db/src/test/java/cn/zhangyis/minidb/server/handler/SystemVariableHandlerPropertyTest.java`
    - **Property 2: 多变量 SELECT 拦截正确性**
    - 生成 1-5 个系统变量名（含已知和未知），可选别名，构造 SELECT 语句，验证返回 Row 的列数和值
    - **验证：需求 2.1, 2.2, 2.4**

  - [ ]* 3.5 编写属性测试：UNION 形式系统变量查询拦截正确性
    - 文件：同 3.4 的测试文件
    - **Property 3: UNION 形式系统变量查询拦截正确性**
    - 生成 2-5 个变量名，构造 UNION 查询，验证返回行数和值
    - **验证：需求 2.3**

- [x] 4. SystemVariableHandler SHOW TABLES FROM 支持
  - [x] 4.1 修改 `SHOW_TABLES` 正则，增加 `FROM db_name` 可选子句
    - 文件：`mini-db/src/main/java/cn/zhangyis/minidb/server/handler/SystemVariableHandler.java`
    - 改动前：`"(?i)^\\s*SHOW\\s+(?:FULL\\s+)?TABLES(?:\\s+WHERE\\s+.+)?\\s*$"`
    - 改动后：`"(?i)^\\s*SHOW\\s+(?:FULL\\s+)?TABLES(?:\\s+FROM\\s+\\S+)?(?:\\s+WHERE\\s+.+)?\\s*$"`
    - _需求：3.3, 5.3_

  - [x] 4.2 修改 `handleShowTables` 方法，增加 `dbName` 参数解析
    - 从 SQL 文本中解析 `FROM db_name`，若指定则使用 `session.catalog().listTables(dbName)`
    - 同步更新 `tryHandle` 和 `executeIntercepted` 中的调用
    - _需求：3.1, 3.2, 5.1, 5.2_

  - [ ]* 4.3 编写属性测试：SHOW TABLES 正则匹配完备性
    - 文件：同 3.4 的测试文件
    - **Property 4: SHOW TABLES 正则匹配完备性**
    - 随机组合 FULL/FROM/WHERE 子句，验证正则匹配
    - **验证：需求 3.3, 5.3**

- [x] 5. SystemVariableHandler SHOW TABLE STATUS FROM/LIKE 支持
  - [x] 5.1 增强 `handleShowTableStatus` 方法，解析 FROM 和 LIKE 子句
    - 文件：`mini-db/src/main/java/cn/zhangyis/minidb/server/handler/SystemVariableHandler.java`
    - FROM 子句：指定目标数据库
    - LIKE 子句：将 `%` → `.*`、`_` → `.` 转为正则，过滤表名（对用户输入中的正则特殊字符进行转义）
    - 同步更新 `tryHandle` 和 `executeIntercepted` 中的调用
    - _需求：4.1, 4.2, 4.3, 4.4_

  - [ ]* 5.2 编写属性测试：SHOW TABLE STATUS LIKE 过滤正确性
    - 文件：同 3.4 的测试文件
    - **Property 5: SHOW TABLE STATUS LIKE 过滤正确性**
    - 生成随机表名列表和 LIKE 模式，验证过滤结果符合 MySQL LIKE 语义
    - **验证：需求 4.2, 4.3, 4.4**

- [x] 6. SystemVariableHandler SHOW DATABASES 独立处理
  - [x] 6.1 新增 `SHOW_DATABASES` 正则，从 `SHOW_MISC` 中移除 `DATABASES`
    - 文件：`mini-db/src/main/java/cn/zhangyis/minidb/server/handler/SystemVariableHandler.java`
    - 新增：`SHOW_DATABASES = Pattern.compile("(?i)^\\s*SHOW\\s+DATABASES\\s*$")`
    - 修改 `SHOW_MISC`：移除 `DATABASES` 关键字
    - _需求：6.1_

  - [x] 6.2 新增 `handleShowDatabases` 方法，在 `tryHandle`、`executeIntercepted`、`canHandle`、`resultMetadata` 四个方法中添加分支
    - 调用 `session.catalog().listDatabases()` 获取数据库列表
    - 若列表为空或 currentDatabase 非 null 则确保包含 currentDatabase
    - 若无任何数据库信息，返回包含 `minidb` 的默认结果
    - 结果列名为 `Database`
    - `SHOW_DATABASES` 匹配必须在 `SHOW_MISC` 之前检查
    - _需求：6.1, 6.2, 6.3_

  - [ ]* 6.3 编写属性测试：SHOW DATABASES 包含当前数据库
    - 文件：同 3.4 的测试文件
    - **Property 6: SHOW DATABASES 包含当前数据库**
    - 生成随机数据库名设为 currentDatabase，验证 SHOW DATABASES 结果包含该名称
    - **验证：需求 6.2**

- [x] 7. 检查点 — 确保 SystemVariableHandler 所有改动编译通过且测试通过
  - 确保所有测试通过，如有问题请询问用户。

- [x] 8. CommandDispatcher 引号感知分号拆分
  - [x] 8.1 新增 `splitStatements(String rawSql)` 静态方法
    - 文件：`mini-db/src/main/java/cn/zhangyis/minidb/server/handler/CommandDispatcher.java`
    - 状态机遍历字符，跟踪 `inSingleQuote` / `inDoubleQuote` / `inBacktick` 状态
    - 仅在引号外的 `;` 处拆分，正确处理转义字符 `\'`
    - 返回 `List<String>`，跳过空语句
    - _需求：7.1, 7.2, 7.3_

  - [x] 8.2 替换 `handleQuery` 中的 `rawSql.split(";")` 为 `splitStatements(rawSql)`
    - 文件：同 8.1
    - _需求：7.1_

  - [ ]* 8.3 编写 CommandDispatcher 分号拆分单元测试
    - 文件：`mini-db/src/test/java/cn/zhangyis/minidb/server/handler/CommandDispatcherSplitTest.java`
    - 测试用例：普通多语句拆分、单引号内分号不拆分、双引号内分号不拆分、反引号内分号不拆分、连续分号跳过空语句、转义引号处理
    - _需求：7.1, 7.2, 7.3_

  - [ ]* 8.4 编写属性测试：引号感知分号拆分保持语句完整性
    - 文件：同 3.4 的测试文件或 `mini-db/src/test/java/cn/zhangyis/minidb/server/handler/CommandDispatcherSplitPropertyTest.java`
    - **Property 7: 引号感知分号拆分保持语句完整性**
    - 生成不含未引用分号的 SQL 列表，拼接后拆分，验证还原为原始列表
    - **验证：需求 7.1**

- [x] 9. 最终检查点 — 确保所有改动编译通过且全部测试通过
  - 确保所有测试通过，如有问题请询问用户。

## 备注

- 标记 `*` 的任务为可选任务，可跳过以加速 MVP 交付
- 每个任务引用了具体的需求编号，确保可追溯性
- 属性测试需要在 `mini-db/mini-db.gradle` 中添加 jqwik 依赖（任务 1.3 中处理）
- 本次改动不涉及 `cn.zhangyis.minidb.storage.*`，无需遵循 AGENTS.md 中的存储层安全规则
- `tryHandle` / `executeIntercepted` / `canHandle` / `resultMetadata` 四个方法必须保持匹配逻辑一致
