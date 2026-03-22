# mini-db 下一阶段特性路线图

> 生成日期：2026-03-22
> 基于：对 mini-db 全部源码（390+ 主代码文件、94 测试文件）的深度扫描

---

## 当前项目状态总览

### 已完成模块

| 层 | 模块 | 完成度 |
|----|------|--------|
| **SQL 词法/语法** | Lexer、Parser、AST（含 NATURAL/USING/CTE/窗口函数/CAST） | 100% |
| **SQL 验证** | SqlValidator（类型推导、作用域、聚合校验、CTE 递归检测） | 100% |
| **SQL 逻辑计划** | RelNode 层级（18+ 节点类型含 Semi/Anti Join） | 100% |
| **SQL 优化** | 8 条规则（Filter 下推、投影裁剪、子查询解关联、Limit 下推等） | 100% |
| **#10 DP JOIN 重排序** | DPJoinEnumerator + JoinGraph + JoinReorderRule | 100% |
| **#11 统计信息** | Histogram + ColumnStatistics + AnalyzeTableExec + CostModel 集成 | 100% |
| **#12 CTE 列名列表** | SqlCte 扩展 + Parser + Validator 校验 | 100% |
| **#13 并行执行** | 4 个并行算子 + QueryThreadPool + PhysicalPlanner 集成 | 100% |
| **#14 NATURAL/USING JOIN** | 端到端（Lexer→Parser→Validator→Converter→Exec） | 90% |
| **执行器** | 20+ ExecNode（含 Window、Semi/Anti Hash Join、Index NL Join） | 100% |
| **SqlSession** | 统一 SQL 执行入口（parse→validate→optimize→plan→execute） | 100% |
| **存储 B-Tree** | 插入/删除/搜索/分裂/合并/压缩 | 100% |
| **存储 Buffer Pool** | LRU 淘汰、并发哈希段、脏页刷新 | 100% |
| **存储 Redo Log** | 环形缓冲、Group Commit、Checkpoint、崩溃恢复 | 100% |
| **存储 MVCC** | ReadView、版本链、Undo Log、锁管理、死锁检测 | 100% |
| **存储 MTR** | Mini-Transaction 页级原子操作 | 100% |
| **存储 Tablespace** | InnoDB 风格 Extent/Segment/Inode 管理 | 100% |
| **SQL↔Storage 桥接** | StorageDataSource（MVCC 读路径 + Overlay 写路径） | 100% |

### 残留缺口

| 缺口 | 位置 | 影响 | 工作量 |
|------|------|------|--------|
| #14 派生表 NATURAL JOIN | SqlToRelConverter L320 TODO | 派生表无法推导列名 | ~10 行 |

---

## 推荐实现的 5 个新特性

按优先级从高到低排列：

---

## Feature #15: EXPLAIN 查询计划可视化

### 动机

项目拥有完整的 CBO 优化器管线（8 条规则 + DP JOIN 枚举 + Histogram），但用户无法查看优化结果。EXPLAIN 是调试优化器、验证索引选择、评估并行策略的唯一窗口。

### 现有基础

- **已有 `explain()` 方法**：所有 24+ RelNode 子类都实现了 `public String explain()`，返回缩进文本树
- **已有 CostModel**：可输出每个节点的代价估算和行数
- **缺失**：Lexer 无 EXPLAIN 令牌、Parser 无 EXPLAIN 语法、无 ExplainExec 执行器

### 实现计划

#### Step 1: Lexer & Parser

| 文件 | 改动 |
|------|------|
| `TokenType.java` | 新增 `EXPLAIN` 枚举值 |
| `SqlLexer.java` | `initKeywords()` 注册 `"EXPLAIN"` |
| `SqlKind.java` | 新增 `EXPLAIN_QUERY` |
| `SqlParser.java` | `parseStatement()` 新增 EXPLAIN 分支，解析 `EXPLAIN [FORMAT=TEXT\|JSON] <query>` |

新增 AST 节点：
```java
public record SqlExplain(SqlNode query, String format) implements SqlNode {
    @Override public SqlKind kind() { return SqlKind.EXPLAIN_QUERY; }
}
```

#### Step 2: 执行管道

| 文件 | 改动 |
|------|------|
| `SqlSession.java` | `execute()` 中检测 SqlExplain → 走 EXPLAIN 路径 |
| `PhysicalPlanner.java` | 新增 `planExplain()` 方法 |

EXPLAIN 路径：
1. 对内层 query 执行完整 validate → convert → optimize → plan
2. 收集逻辑计划 explain() + 物理算子类型 + CostModel 估算
3. 包装为 Row 返回

#### Step 3: 新建 ExplainExec

```java
public class ExplainExec implements ExecNode {
    // 输出格式：
    // | OPERATOR          | EST_ROWS | EST_COST | DETAILS              |
    // | RelProject        | 5        | 5.0      | fields=3             |
    // |   RelFilter       | 2        | 5.5      | condition=dept_id=10 |
    // |     RelScan       | 5        | 5.0      | table=EMPLOYEES      |
}
```

#### Step 4: 增强输出（可选）

- `EXPLAIN ANALYZE`：实际执行并收集真实行数、耗时
- `EXPLAIN FORMAT=JSON`：结构化 JSON 输出
- 每个 RelNode 的 `explain()` 增加 cost/rows 信息

### 关键文件

| 文件 | 改动类型 |
|------|----------|
| `sql/lexer/TokenType.java` | 修改 |
| `sql/lexer/SqlLexer.java` | 修改 |
| `sql/ast/SqlKind.java` | 修改 |
| `sql/ast/SqlExplain.java` | 新建 |
| `sql/parser/SqlParser.java` | 修改 |
| `sql/exec/ExplainExec.java` | 新建 |
| `sql/exec/SqlSession.java` | 修改 |
| `sql/exec/PhysicalPlanner.java` | 修改 |

### 测试 (≥3)

| # | 场景 | 验证 |
|---|------|------|
| 1 | `EXPLAIN SELECT * FROM users` | 输出包含 RelScan 节点 |
| 2 | `EXPLAIN SELECT * FROM e JOIN d ON ...` | 输出包含 JOIN 算法类型（Hash/SortMerge） |
| 3 | `EXPLAIN` 3 表 JOIN 后 DP 重排 | 输出展示重排后的 JOIN 顺序 |
| 4 | `EXPLAIN SELECT ... WHERE id = 1`（有统计信息） | 输出中 EST_ROWS 反映统计值而非默认 |
| 5 | `EXPLAIN` 不实际执行查询 | 数据不被修改 |

### 工作量估算

约 200-300 行新代码，涉及 6 个文件。

---

## Feature #16: SHOW / DESCRIBE 元数据查询

### 动机

数据库的基本可用性要求用户能查看表结构。当前只能通过代码调用 `catalog.getTable()` 获取元数据，无 SQL 接口。

### 现有基础

- **CatalogSpi 已提供完整接口**：
  - `listTables(String database)` → `List<String>`
  - `getColumns(String tableName)` → `List<ColumnMeta>`
  - `getIndexes(String tableName)` → `List<IndexMeta>`
  - `tableExists(String tableName)` → `boolean`
- **ColumnMeta**：name, type(SqlType), isPrimaryKey, nullable, defaultValue
- **IndexMeta**：indexName, tableName, columns, primary, unique

### 实现计划

#### 支持的语法

```sql
SHOW TABLES                          -- 列出所有表
SHOW COLUMNS FROM <table>            -- 表的列信息
SHOW INDEXES FROM <table>            -- 表的索引信息
DESCRIBE <table>                     -- 同 SHOW COLUMNS FROM
SHOW CREATE TABLE <table>            -- DDL 语句重建
```

#### Step 1: Lexer & Parser

| 文件 | 改动 |
|------|------|
| `TokenType.java` | 新增 `SHOW`, `DESCRIBE`, `COLUMNS`, `INDEXES`, `TABLES` |
| `SqlLexer.java` | 注册 5 个新关键字 |
| `SqlKind.java` | 新增 `SHOW_TABLES`, `SHOW_COLUMNS`, `SHOW_INDEXES`, `DESCRIBE_TABLE`, `SHOW_CREATE_TABLE` |
| `SqlParser.java` | 新增 `parseShow()`, `parseDescribe()` 方法 |

新增 AST 节点：
```java
public record SqlShow(SqlKind showKind, String tableName) implements SqlNode { ... }
```

#### Step 2: 执行器

| 文件 | 改动 |
|------|------|
| `ShowTablesExec.java` | 新建：调用 `catalog.listTables()` |
| `ShowColumnsExec.java` | 新建：调用 `catalog.getColumns()` |
| `ShowIndexesExec.java` | 新建：调用 `catalog.getIndexes()` |
| `ShowCreateTableExec.java` | 新建：从 TableMeta 重建 CREATE TABLE DDL |
| `PhysicalPlanner.java` | 新增 SqlShow → ExecNode 路由 |
| `SqlSession.java` | execute() 中检测 SHOW/DESCRIBE |

输出示例：
```
SHOW TABLES:
| TABLE_NAME |
| USERS      |
| ORDERS     |

DESCRIBE USERS:
| COLUMN  | TYPE    | PRIMARY | NULLABLE | DEFAULT |
| ID      | INT32   | YES     | NO       | NULL    |
| NAME    | VARCHAR | NO      | YES      | NULL    |
```

#### Step 3: Validator（轻量）

- SHOW TABLES：无需验证
- SHOW COLUMNS/INDEXES FROM t：验证表存在
- DESCRIBE t：验证表存在

### 关键文件

| 文件 | 改动类型 |
|------|----------|
| `sql/lexer/TokenType.java` | 修改 |
| `sql/lexer/SqlLexer.java` | 修改 |
| `sql/ast/SqlKind.java` | 修改 |
| `sql/ast/SqlShow.java` | 新建 |
| `sql/parser/SqlParser.java` | 修改 |
| `sql/exec/ShowTablesExec.java` | 新建 |
| `sql/exec/ShowColumnsExec.java` | 新建 |
| `sql/exec/ShowIndexesExec.java` | 新建 |
| `sql/exec/ShowCreateTableExec.java` | 新建 |
| `sql/exec/PhysicalPlanner.java` | 修改 |
| `sql/exec/SqlSession.java` | 修改 |

### 测试 (≥3)

| # | 场景 | 验证 |
|---|------|------|
| 1 | `SHOW TABLES` 创建 3 个表后执行 | 返回 3 行 |
| 2 | `DESCRIBE USERS` | 列名、类型、主键标记正确 |
| 3 | `SHOW INDEXES FROM USERS` | 返回索引列表含主键索引 |
| 4 | `SHOW COLUMNS FROM non_existent` | 抛出异常 |
| 5 | `SHOW CREATE TABLE USERS` | 输出可被 Parser 重新解析 |

### 工作量估算

约 250-350 行新代码，涉及 9 个文件。

---

## Feature #17: Prepared Statement / 参数化查询

### 动机

参数化查询是数据库的基础能力：防止 SQL 注入、支持计划缓存复用。当前 SqlSession.execute() 只接受完整 SQL 字符串。

### 现有基础

- **Lexer**：`nextToken()` 的 switch-case 中无 `?` 处理
- **SqlSession**：单一 `execute(String sql)` 入口
- **FilterExec.resolveValue()**：已有完整的值解析框架，扩展性好
- **无计划缓存机制**

### 实现计划

#### 支持的用法

```java
PreparedQuery pq = session.prepare("SELECT * FROM users WHERE id = ? AND name = ?");
pq.bind(1, 42);
pq.bind(2, "alice");
List<Row> rows = pq.execute();
// 或简写：
List<Row> rows = session.execute("SELECT * FROM users WHERE id = ?", 42);
```

#### Step 1: Lexer & AST

| 文件 | 改动 |
|------|------|
| `TokenType.java` | 新增 `QUESTION_MARK` |
| `SqlLexer.java` | `nextToken()` 新增 `case '?'` |
| `SqlKind.java` | 新增 `PARAMETER` |
| `SqlParameter.java` | 新建：`record SqlParameter(int index) implements SqlNode` |
| `SqlParser.java` | `parsePrimary()` 中识别 `?`，按出现顺序自增 index |

#### Step 2: 参数绑定

| 文件 | 改动 |
|------|------|
| `PreparedQuery.java` | 新建：持有解析后的 SqlNode + 参数绑定映射 |
| `SqlSession.java` | 新增 `prepare(String sql)` 和 `execute(String sql, Object... params)` |

```java
public class PreparedQuery {
    private final SqlNode parsedAst;
    private final int paramCount;
    private final Map<Integer, Object> bindings = new HashMap<>();

    public void bind(int index, Object value) { ... }
    public List<Row> execute() { ... }
}
```

#### Step 3: 参数替换

两种策略（推荐 B）：

**策略 A — AST 替换**：执行前将 SqlParameter 节点替换为 SqlLiteral
```java
// 在 execute() 时遍历 AST，将 SqlParameter(i) 替换为 bindings.get(i) 对应的 SqlLiteral
```

**策略 B — 运行时解析**：执行器中动态解析参数值
```java
// FilterExec.resolveValue() 中：
if (node instanceof SqlParameter param) {
    return executionContext.getParameter(param.index());
}
```

推荐策略 B，因为：
- 不修改 AST（利于计划缓存）
- 改动集中在 resolveValue() 一处
- 执行器已有完善的值解析框架

#### Step 4: 计划缓存（可选）

```java
public class QueryPlanCache {
    private final Map<String, CachedPlan> cache; // sql_template → (RelNode, ExecNode)
    // LRU 淘汰，DDL 时全量失效
}
```

### 关键文件

| 文件 | 改动类型 |
|------|----------|
| `sql/lexer/TokenType.java` | 修改 |
| `sql/lexer/SqlLexer.java` | 修改 |
| `sql/ast/SqlKind.java` | 修改 |
| `sql/ast/SqlParameter.java` | 新建 |
| `sql/parser/SqlParser.java` | 修改 |
| `sql/exec/PreparedQuery.java` | 新建 |
| `sql/exec/SqlSession.java` | 修改 |
| `sql/exec/ExecutionContext.java` | 修改（添加参数存储） |
| `sql/exec/FilterExec.java` | 修改（resolveValue 扩展） |
| `sql/exec/InsertExec.java` | 修改（VALUES 中参数解析） |
| `sql/exec/UpdateExec.java` | 修改（SET 中参数解析） |

### 测试 (≥3)

| # | 场景 | 验证 |
|---|------|------|
| 1 | `SELECT * FROM users WHERE id = ?` bind(1, 1) | 返回 1 行 |
| 2 | `INSERT INTO users VALUES (?, ?)` bind 两次不同值 | 两行均插入 |
| 3 | 同一 PreparedQuery 多次 execute 不同参数 | 每次结果独立正确 |
| 4 | 参数数量不匹配 | 抛出异常 |
| 5 | 未绑定参数就 execute | 抛出异常 |

### 工作量估算

约 200-300 行新代码（不含计划缓存），涉及 9 个文件。

---

## Feature #18: 交互式 REPL（命令行客户端）

### 动机

项目已有完整的 SqlSession 端到端管线和 StorageDataSource 存储桥接，但只能通过测试代码调用。一个交互式 REPL 让 mini-db 真正可用。

### 现有基础

- **SqlSession**：`execute(String sql)` → `List<Row>` 已完备
- **StorageDataSource**：SQL↔Storage 桥接已就绪
- **ExecutionContext**：事务管理已完整
- **无 main() 入口点**（仅有 TestMain 用于验证）
- **无 JDBC Driver**

### 实现计划

#### Step 1: 新建 MiniDbServer（引导类）

```java
public class MiniDbServer {
    // 初始化存储引擎全链路：
    // DiskManager → BufferPool → RedoLogManager → CatalogManager
    // → TransactionManager → UndoLogManager → StorageDataSource
    // → ExecutionContext → SqlSession

    public SqlSession createSession() { ... }
    public void shutdown() { ... }
}
```

#### Step 2: 新建 MiniDbRepl（交互式命令行）

```java
public class MiniDbRepl {
    public static void main(String[] args) {
        MiniDbServer server = new MiniDbServer(config);
        SqlSession session = server.createSession();

        // REPL 循环
        while (true) {
            String sql = readLine("mini-db> ");
            if (sql.equals("exit") || sql.equals("quit")) break;
            try {
                List<Row> rows = session.execute(sql);
                printTable(rows);        // 格式化表格输出
                println(rows.size() + " row(s)");
            } catch (Exception e) {
                println("ERROR: " + e.getMessage());
            }
        }
        server.shutdown();
    }
}
```

#### Step 3: 结果格式化

```
mini-db> SELECT * FROM users;
+----+---------+---------+
| ID | NAME    | DEPT_ID |
+----+---------+---------+
|  1 | alice   |      10 |
|  2 | bob     |      20 |
|  3 | charlie |      10 |
+----+---------+---------+
3 row(s) in set (12 ms)
```

#### Step 4: 元命令（可选）

| 命令 | 功能 |
|------|------|
| `\dt` | 同 SHOW TABLES |
| `\d <table>` | 同 DESCRIBE |
| `\timing` | 切换计时显示 |
| `\q` | 退出 |

### 关键文件

| 文件 | 改动类型 |
|------|----------|
| `MiniDbServer.java` | 新建（存储引擎引导） |
| `MiniDbRepl.java` | 新建（REPL 主循环） |
| `TableFormatter.java` | 新建（结果格式化输出） |
| `MiniDbConfig.java` | 新建（数据目录、缓冲池大小等配置） |

### 测试 (≥3)

| # | 场景 | 验证 |
|---|------|------|
| 1 | 启动 → CREATE TABLE → INSERT → SELECT → 关闭 → 重启 → SELECT | 数据持久化 |
| 2 | BEGIN → INSERT → ROLLBACK → SELECT | 事务回滚生效 |
| 3 | 格式化输出对齐 | 列宽自适应，中文不错位 |
| 4 | 非法 SQL | 输出 ERROR 而非崩溃 |

### 工作量估算

约 400-500 行新代码（含格式化），涉及 4 个新文件。

---

## Feature #19: 表达式计算增强（函数库）

### 动机

当前 FilterExec.resolveValue() 支持基础算术和 CAST/CASE，但缺少字符串函数（UPPER/LOWER/LENGTH/SUBSTR/CONCAT）、数学函数（ABS/ROUND/CEIL/FLOOR）、日期函数等。这些是 SQL 实用性的基本要求。

### 现有基础

- **函数框架**：`sql/functions/` 目录存在，已有若干内置函数
- **SqlFunctionCall AST 节点**：已存在，Parser 已能解析 `FUNC(args...)`
- **FilterExec.resolveValue()**：已处理 `SqlFunctionCall`，通过名称分派

### 实现计划

#### 目标函数列表

**字符串函数**：
| 函数 | 语法 | 说明 |
|------|------|------|
| UPPER | `UPPER(str)` | 转大写 |
| LOWER | `LOWER(str)` | 转小写 |
| LENGTH | `LENGTH(str)` | 字符串长度 |
| SUBSTR | `SUBSTR(str, pos, len)` | 子串 |
| CONCAT | `CONCAT(s1, s2, ...)` | 拼接 |
| TRIM | `TRIM(str)` | 去除首尾空格 |
| REPLACE | `REPLACE(str, from, to)` | 替换 |
| COALESCE | `COALESCE(a, b, ...)` | 第一个非 NULL 值 |

**数学函数**：
| 函数 | 语法 | 说明 |
|------|------|------|
| ABS | `ABS(n)` | 绝对值 |
| ROUND | `ROUND(n, d)` | 四舍五入 |
| CEIL | `CEIL(n)` | 向上取整 |
| FLOOR | `FLOOR(n)` | 向下取整 |
| MOD | `MOD(a, b)` | 取模 |

**NULL 处理**：
| 函数 | 语法 | 说明 |
|------|------|------|
| IFNULL | `IFNULL(a, b)` | NULL 则返回 b |
| NULLIF | `NULLIF(a, b)` | a=b 则返回 NULL |

#### 实现方式

在 `FilterExec.resolveValue()` 的 `SqlFunctionCall` 分支中扩展名称分派：

```java
case "UPPER" -> args.get(0).toString().toUpperCase();
case "LENGTH" -> args.get(0).toString().length();
case "SUBSTR" -> args.get(0).toString().substring((int)args.get(1), ...);
// ...
```

或提取为独立的 `FunctionRegistry`：

```java
public class FunctionRegistry {
    private static final Map<String, SqlFunction> BUILTINS = Map.of(
        "UPPER", (args) -> ((String) args.get(0)).toUpperCase(),
        "ABS", (args) -> Math.abs(((Number) args.get(0)).doubleValue()),
        // ...
    );
}
```

### 关键文件

| 文件 | 改动类型 |
|------|----------|
| `sql/functions/FunctionRegistry.java` | 新建或扩展 |
| `sql/exec/FilterExec.java` | 修改（函数求值分派） |
| `sql/exec/ProjectExec.java` | 修改（投影中函数求值） |

### 测试 (≥3)

| # | 场景 | 验证 |
|---|------|------|
| 1 | `SELECT UPPER(name) FROM users` | 返回全大写值 |
| 2 | `SELECT LENGTH(name), SUBSTR(name, 1, 3) FROM users` | 长度和子串正确 |
| 3 | `SELECT * FROM users WHERE ABS(score - 50) < 10` | 过滤逻辑正确 |
| 4 | `SELECT COALESCE(NULL, NULL, 'default')` | 返回 'default' |
| 5 | `SELECT CONCAT(name, '-', dept_id) FROM users` | 拼接正确 |

### 工作量估算

约 150-250 行新代码，涉及 2-3 个文件。

---

## 实施顺序建议

```
#15 EXPLAIN  ←──────── 最高优先级，调试优化器的唯一窗口
    ↓
#16 SHOW/DESCRIBE ←── 基本可用性
    ↓
#19 函数库 ←────────── SQL 实用性（工作量最小）
    ↓
#17 Prepared Statement ← 安全性 + 性能
    ↓
#18 REPL ←──────────── 端到端可用（依赖 #15 + #16 更完整）
```

理由：
1. **#15 EXPLAIN** 优先——后续所有特性开发都会从可视化执行计划中受益
2. **#16 SHOW/DESCRIBE** 其次——REPL 需要这些命令才有实用价值
3. **#19 函数库** 工作量最小（150 行），立即提升 SQL 表达能力
4. **#17 Prepared Statement** 为后续 JDBC/连接池打基础
5. **#18 REPL** 最后——依赖前面特性才能提供完整体验

---

## 附录：#14 NATURAL/USING JOIN 残留修复

### 问题

`SqlToRelConverter.java` 第 320 行 TODO：派生表的列名无法推导。

### 修复位置

`collectColumnNamesRecursive()` 方法中 `SqlDerivedTable` 分支。

### 修复方案

```java
if (from instanceof SqlDerivedTable derived) {
    SqlSelect innerSelect = derived.select();
    for (SqlNode proj : innerSelect.projection().nodes()) {
        if (proj instanceof SqlAlias alias) {
            cols.add(alias.alias().toUpperCase());
        } else if (proj instanceof SqlIdentifier id) {
            String name = id.name();
            cols.add((name.contains(".") ? name.substring(name.indexOf('.') + 1) : name).toUpperCase());
        }
    }
    return;
}
```

工作量：~10 行。建议在开始 #15 之前先修复此项。
