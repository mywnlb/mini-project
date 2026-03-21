# 低优先级特性实现计划 (#10~#14)
# 日期: 2026-03-21
# 状态: 已审查

## 背景

mini-db SQL 层已完成 Lexer→Parser→Validator→SqlToRelConverter→RuleOptimizer→CostOptimizer→PhysicalPlanner→ExecNode 全流水线。当前缺少：多表 JOIN 重排序、真实统计信息、CTE、并行执行、NATURAL JOIN/USING。

## 实现顺序

**#14 → #12 → #11 → #10 → #13**

依赖关系：`#11(统计)` → `#10(JOIN重排)`，其余独立。
排序理由：#14 最简单（前端语法糖）→ #12 中等（前端+转换层）→ #11 基础设施 → #10 利用统计 → #13 最复杂。

---

## Feature #14: NATURAL JOIN / USING

### Fail-First Gate

**禁止设计**：
1. ❌ Parser 层直接展开为 ON 条件 — Parser 无法访问 TableMeta，NATURAL 需要知道共同列名
2. ❌ 在 ExecNode 层处理 NATURAL/USING — 6 个 JOIN 执行器全部要改，优化规则无法识别

**核心不变量**：
1. NATURAL/USING 必须在 RelNode 层之前完全展开为 ON equi-join 条件
2. SqlJoin 扩展必须向后兼容（原有 2 个构造器保持不变）
3. SqlValidator 检查 `condition==null` 的逻辑必须放行 natural/using 情况

### 修改文件

| 文件 | 操作 | 行号参考 |
|------|------|---------|
| `sql/lexer/TokenType.java` | 新增 `NATURAL`, `USING` | L6 JOIN 关键字行 |
| `sql/lexer/SqlLexer.java` | `initKeywords()` 注册 | L82 附近 |
| `sql/ast/SqlJoin.java` | 新增字段 `usingColumns`, `natural` | 全文 12 行 |
| `sql/ast/SqlNodeFactory.java` | 新增带 USING/NATURAL 的 join 工厂方法 | L29-35 |
| `sql/parser/SqlParser.java` | `isJoinStart()`, `parseJoinType()`, `parseFrom()` | L109-162 |
| `sql/validation/SqlValidator.java` | `validateFrom()` 放行 natural/using | L54 关键行 |
| `sql/planner/SqlToRelConverter.java` | `convertFrom()` 展开为 ON 条件 | L97-101 |

### 实现步骤

**1. Lexer（2 处）**
- `TokenType.java` L6: 在 `INNER,` 后新增 `NATURAL, USING,`
- `SqlLexer.java`: `initKeywords()` 新增 `"NATURAL"→NATURAL`, `"USING"→USING`

**2. AST — SqlJoin 扩展**

当前 record：
```java
record SqlJoin(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition)
```

扩展为：
```java
record SqlJoin(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition,
               List<String> usingColumns, boolean natural) {
    // 向后兼容构造器
    public SqlJoin(SqlNode left, SqlNode right, SqlNode condition) {
        this(JoinType.INNER, left, right, condition, null, false);
    }
    public SqlJoin(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition) {
        this(joinType, left, right, condition, null, false);
    }
}
```

**3. SqlNodeFactory 新增方法**
```java
public SqlJoin naturalJoin(JoinType joinType, SqlNode left, SqlNode right) {
    return new SqlJoin(joinType, left, right, null, null, true);
}
public SqlJoin usingJoin(JoinType joinType, SqlNode left, SqlNode right, List<String> usingColumns) {
    return new SqlJoin(joinType, left, right, null, usingColumns, false);
}
```

**4. Parser 修改（3 个方法）**

`isJoinStart()`：增加 `|| type == TokenType.NATURAL`

`parseJoinType()`：
```
if (type == NATURAL) {
    tokens.next();
    boolean natural = true;
    JoinType jt = parseOptionalJoinType(); // [INNER|LEFT|RIGHT|FULL] JOIN
    return (jt, natural);  // 需要传递 natural 标记
}
```

注意：`parseJoinType()` 当前返回 `JoinType`，需要返回更多信息。有两种方案：
- **方案 A**：返回一个 `record ParsedJoin(JoinType type, boolean natural)`
- **方案 B**：在 `parseFrom()` 中用局部变量直接处理

选择 **方案 B**（更简单）：在 `parseFrom()` 中先检测 NATURAL，然后调用原 `parseJoinType()`。

`parseFrom()` 改造：
```java
private SqlNode parseFrom() {
    SqlNode left = parseFromItem();
    while (isJoinStart(tokens.current().type())) {
        boolean natural = tokens.match(TokenType.NATURAL); // 消费 NATURAL
        JoinType joinType = parseJoinType();
        SqlNode right = parseFromItem();

        if (natural) {
            left = factory.naturalJoin(joinType, left, right);
        } else if (joinType != JoinType.CROSS && tokens.current().type() == TokenType.USING) {
            tokens.next(); // consume USING
            tokens.expect(TokenType.LPAREN);
            List<String> cols = parseUsingColumnList();
            tokens.expect(TokenType.RPAREN);
            left = factory.usingJoin(joinType, left, right, cols);
        } else {
            SqlNode condition = null;
            if (joinType != JoinType.CROSS) {
                tokens.expect(TokenType.ON);
                condition = parseExpression();
            }
            left = factory.join(joinType, left, right, condition);
        }
    }
    return left;
}
```

**5. SqlValidator 修改**

`validateFrom()` L54 当前检查：
```java
if (join.joinType() != JoinType.CROSS && join.condition() == null) {
    throw new ValidationException("Non-CROSS JOIN must have ON condition");
}
```

改为：
```java
if (join.joinType() != JoinType.CROSS && join.condition() == null
    && !join.natural() && join.usingColumns() == null) {
    throw new ValidationException("Non-CROSS JOIN must have ON/USING/NATURAL condition");
}
```

**6. SqlToRelConverter 展开**

`convertFrom()` 中处理 SqlJoin：
```java
if (from instanceof SqlJoin join) {
    RelNode left = convertFrom(join.left(), validated);
    RelNode right = convertFrom(join.right(), validated);
    SqlNode condition = join.condition();

    if (join.natural() || join.usingColumns() != null) {
        condition = expandNaturalOrUsing(join, validated);
    }
    return factories.join(left, right, condition, join.joinType());
}
```

`expandNaturalOrUsing()`:
```java
private SqlNode expandNaturalOrUsing(SqlJoin join, ValidatedSqlSelect validated) {
    List<String> commonCols;
    if (join.natural()) {
        // 从左右两侧 TableMeta 找同名列
        Set<String> leftCols = collectColumnNames(join.left(), validated);
        Set<String> rightCols = collectColumnNames(join.right(), validated);
        commonCols = leftCols.stream().filter(rightCols::contains).toList();
        if (commonCols.isEmpty()) {
            return null; // 退化为 CROSS JOIN
        }
    } else {
        commonCols = join.usingColumns();
    }
    // 生成 AND 链: leftAlias.col = rightAlias.col AND ...
    String leftAlias = resolveAlias(join.left());
    String rightAlias = resolveAlias(join.right());
    SqlNode result = null;
    for (String col : commonCols) {
        SqlNode eq = new SqlBinaryOp(SqlKind.BINARY_EQ,
            new SqlIdentifier(leftAlias + "." + col),
            new SqlIdentifier(rightAlias + "." + col));
        result = result == null ? eq : new SqlBinaryOp(SqlKind.AND, result, eq);
    }
    return result;
}
```

### 测试要点
- `SELECT * FROM a NATURAL JOIN b` — 共同列自动匹配
- `SELECT * FROM a JOIN b USING (id)` — 单列
- `SELECT * FROM a JOIN b USING (id, name)` — 多列
- `SELECT * FROM a NATURAL LEFT JOIN b` — 外连接
- 无共同列 NATURAL JOIN → 退化为 CROSS（返回笛卡尔积）
- USING 中指定不存在的列 → 报错

---

## Feature #12: CTE (WITH 子句)

### Fail-First Gate

**禁止设计**：
1. ❌ 修改 SqlSelect record 添加 CTE 字段 — SqlSelect 被大量代码引用，改动爆炸半径太大
2. ❌ 在执行层处理 CTE 引用 — CTE 必须在逻辑计划阶段解析，否则优化器无法处理

**核心不变量**：
1. CTE 必须在 SqlToRelConverter 阶段完全内联为 RelNode 子树
2. CTE 名在作用域内优先于同名物理表
3. v1 不支持递归 CTE（自引用检测 → 抛异常）

### 修改/新建文件

| 文件 | 操作 |
|------|------|
| `sql/lexer/TokenType.java` | 新增 `WITH` |
| `sql/lexer/SqlLexer.java` | 注册 WITH |
| `sql/ast/SqlKind.java` | 新增 `CTE`, `WITH_SELECT` |
| `sql/ast/SqlCte.java` | **新建** `record SqlCte(String name, SqlSelect query)` |
| `sql/ast/SqlWithSelect.java` | **新建** `record SqlWithSelect(List<SqlCte> ctes, SqlSelect select)` |
| `sql/parser/SqlParser.java` | `parseStatement()` 新增 `case WITH` |
| `sql/validation/SqlValidator.java` | `validate()` 新增 `case SqlWithSelect`，CTE 名注册为虚拟表 |
| `sql/planner/SqlToRelConverter.java` | `convert()` 新增 `case SqlWithSelect` → 内联展开 |

### 实现步骤

**1. Lexer**
- `TokenType.java`: 新增 `WITH`
- `SqlLexer.java`: `keywords.put("WITH", TokenType.WITH)`

**2. AST**

```java
// SqlCte.java
public record SqlCte(String name, SqlSelect query) implements SqlNode {
    @Override public SqlKind kind() { return SqlKind.CTE; }
}

// SqlWithSelect.java
public record SqlWithSelect(List<SqlCte> ctes, SqlSelect select) implements SqlNode {
    @Override public SqlKind kind() { return SqlKind.WITH_SELECT; }
}
```

**3. Parser**

`parseStatement()` 新增：
```java
case WITH -> {
    List<SqlCte> ctes = parseCteList();
    SqlSelect mainSelect = parseSelect();
    yield new SqlWithSelect(ctes, mainSelect);
}
```

```java
private List<SqlCte> parseCteList() {
    List<SqlCte> ctes = new ArrayList<>();
    do {
        Token nameToken = tokens.expect(TokenType.IDENTIFIER);
        String name = nameToken.value();
        tokens.expect(TokenType.AS);
        tokens.expect(TokenType.LPAREN);
        SqlSelect cteQuery = parseSelect();
        tokens.expect(TokenType.RPAREN);
        ctes.add(new SqlCte(name, cteQuery));
    } while (tokens.match(TokenType.COMMA));
    return ctes;
}
```

**4. Validator**

`validate()` 新增 `case SqlWithSelect`:
```java
case SqlWithSelect withSelect -> {
    Map<String, TableMeta> cteSchemas = new LinkedHashMap<>();
    for (SqlCte cte : withSelect.ctes()) {
        // 检测递归引用
        if (referencesTable(cte.query(), cte.name())) {
            throw new ValidationException("Recursive CTE not supported: " + cte.name());
        }
        ValidatedSqlSelect validated = validateSelect(cte.query());
        TableMeta cteMeta = deriveCteSchema(cte.name(), validated);
        cteSchemas.put(cte.name().toUpperCase(), cteMeta);
    }
    // 验证主查询，CTE 名注入为虚拟表
    yield validateSelectWithCteScope(withSelect.select(), cteSchemas);
}
```

`validateSelectWithCteScope()`: 在 `validateFrom()` 中，当遇到表名匹配 CTE 名时，使用 CTE 的虚拟 TableMeta（类似 derived table 的处理方式）。

**5. SqlToRelConverter**

`convert()` 新增 `case SqlWithSelect`:
```java
case SqlWithSelect withSelect -> {
    // 缓存 CTE RelNode
    Map<String, RelNode> cteCache = new HashMap<>();
    for (SqlCte cte : withSelect.ctes()) {
        SqlValidator v = new SqlValidator(catalog);
        SqlNode validated = v.validate(cte.query());
        cteCache.put(cte.name().toUpperCase(), convert(validated));
    }
    // 带 CTE 上下文转换主查询
    yield convertSelectWithCte(withSelect.select(), cteCache);
}
```

`convertFrom()` 中表引用解析时，先查 cteCache：匹配则包装为 `RelDerivedScan`。

### 测试要点
- `WITH t AS (SELECT 1 AS x) SELECT * FROM t` — 简单 CTE
- `WITH t AS (...) SELECT * FROM t a JOIN t b ON a.id = b.id` — 多次引用
- `WITH a AS (...), b AS (SELECT * FROM a ...) SELECT * FROM b` — CTE 链式引用
- CTE 名与物理表名相同时 CTE 优先
- 递归 CTE → 抛异常

---

## Feature #11: 统计信息收集

### Fail-First Gate

**禁止设计**：
1. ❌ 修改 ColumnMeta record 添加统计字段 — ColumnMeta 是 DDL 定义的结构元数据，与运行时统计混在一起会在 CREATE TABLE 时造成混乱
2. ❌ 在 CostModel.selectivity() 中直接查询数据源扫描统计 — 每次调用 selectivity 都触发 I/O，查询优化阶段性能灾难

**核心不变量**：
1. 统计信息与结构元数据（TableMeta/ColumnMeta）物理分离
2. 无统计时 CostModel 必须完全回退到原有硬编码选择率（零行为变化）
3. 统计信息是快照（ANALYZE 时点采集），不随 DML 实时更新

### 新建/修改文件

| 文件 | 操作 |
|------|------|
| `sql/catalog/ColumnStatistics.java` | **新建** NDV/min/max/nullCount |
| `sql/catalog/TableStatistics.java` | **新建** rowCount + Map<col, ColumnStatistics> |
| `sql/catalog/StatisticsStore.java` | **新建** 接口 |
| `sql/catalog/InMemoryStatisticsStore.java` | **新建** ConcurrentHashMap 实现 |
| `sql/lexer/TokenType.java` | 新增 `ANALYZE` |
| `sql/lexer/SqlLexer.java` | 注册 ANALYZE |
| `sql/ast/SqlKind.java` | 新增 `ANALYZE_TABLE` |
| `sql/ast/SqlAnalyzeTable.java` | **新建** AST 节点 |
| `sql/parser/SqlParser.java` | `parseStatement()` 新增 `case ANALYZE` |
| `sql/rel/RelAnalyzeTable.java` | **新建** 逻辑节点 |
| `sql/exec/AnalyzeTableExec.java` | **新建** 全表扫描收集统计 |
| `sql/exec/PhysicalPlanner.java` | 处理 RelAnalyzeTable |
| `sql/optimize/cost/CostModel.java` | 构造器新增 StatisticsStore 参数；selectivity() 改造 |
| `sql/optimize/cost/CostOptimizer.java` | 传递 StatisticsStore |

### 实现步骤

**1. 数据结构**

```java
// ColumnStatistics.java
public record ColumnStatistics(
    String columnName, long ndv, Comparable<?> minValue, Comparable<?> maxValue,
    long nullCount, long totalRows
) {
    /** 等值选择率 = 1/NDV，无统计回退 0.1 */
    public double selectivityEq() { return ndv > 0 ? 1.0 / ndv : 0.1; }

    /** 范围选择率，基于 min/max 线性插值 */
    public double selectivityRange(Comparable<?> value, boolean isLessThan) {
        if (minValue == null || maxValue == null) return 0.3;
        // ... 线性插值
    }
}

// TableStatistics.java
public record TableStatistics(
    String tableName, long rowCount,
    Map<String, ColumnStatistics> columnStats, long collectionTimestamp
) {
    public ColumnStatistics column(String name) {
        return columnStats.get(name.toUpperCase());
    }
}
```

**2. StatisticsStore**

```java
public interface StatisticsStore {
    TableStatistics getTableStatistics(String tableName);
    void putTableStatistics(String tableName, TableStatistics stats);
}

// InMemoryStatisticsStore: ConcurrentHashMap<String, TableStatistics>
```

**3. ANALYZE TABLE 命令**

Lexer: `ANALYZE` → Parser: `ANALYZE TABLE tableName`

AnalyzeTableExec 逻辑：
```
scan 全表 → 对每列:
  HashSet 收集去重值 → NDV
  比较 min/max
  计数 null
→ 构建 TableStatistics → 写入 StatisticsStore
```

**4. CostModel 改造（核心）**

构造器扩展（向后兼容）：
```java
public CostModel(boolean enableIndexLookup, StatisticsStore statsStore) {
    this.enableIndexLookup = enableIndexLookup;
    this.statsStore = statsStore; // nullable
}
```

`selectivity()` 改造（当前 L54-66）：
```java
private double selectivity(SqlNode condition) {
    if (condition instanceof SqlBinaryOp binOp) {
        return switch (binOp.kind()) {
            case BINARY_EQ -> {
                if (statsStore != null) {
                    ColumnStatistics cs = resolveColumnStats(binOp);
                    if (cs != null) yield cs.selectivityEq(); // 1/NDV
                }
                yield 0.1; // 回退
            }
            case BINARY_LT, BINARY_GT, BINARY_LE, BINARY_GE -> {
                if (statsStore != null) {
                    ColumnStatistics cs = resolveColumnStats(binOp);
                    if (cs != null) yield cs.selectivityRange(...);
                }
                yield 0.3; // 回退
            }
            // ... 其余不变
        };
    }
    return 0.3;
}
```

`resolveColumnStats()`: 从 binOp 中提取列名（SqlIdentifier）+ 表名 → 查 statsStore。

### 测试要点
- ANALYZE TABLE 后查询 StatisticsStore → NDV/min/max 正确
- CostModel 有统计时 selectivityEq() = 1/NDV
- CostModel 无统计时回退 0.1（零行为变化）
- 统计信息影响 JOIN 算法选择（例如右表 NDV 很大时偏向 SortMerge）

---

## Feature #10: 多 JOIN 重排序

### Fail-First Gate

**禁止设计**：
1. ❌ 重排 OUTER JOIN — LEFT/RIGHT/FULL JOIN 的语义不满足交换律，重排会导致语义错误（丢失 NULL 填充行）
2. ❌ 在 Parser 层做 join 重排 — Parser 不知道表大小，无法做代价决策

**核心不变量**：
1. 只重排连续的 INNER JOIN 链，OUTER JOIN 作为屏障不参与
2. 重排后 join 条件必须正确关联到新的左右表
3. 重排结果必须与原始 join 在语义上等价（结果集相同）

### 新建/修改文件

| 文件 | 操作 |
|------|------|
| `sql/optimize/JoinGraph.java` | **新建** join 图抽象 |
| `sql/optimize/JoinReorderRule.java` | **新建** 贪心重排规则 |
| `sql/optimize/RuleOptimizer.java` | L29 注册 JoinReorderRule |
| `sql/optimize/cost/CostModel.java` | 可能新增 `estimateJoinPairCost()` 辅助方法 |

### 实现步骤

**1. JoinGraph**

```java
class JoinGraph {
    List<RelNode> relations;           // 参与 join 的基表节点
    List<JoinEdge> edges;              // join 谓词
    List<SqlNode> residualPredicates;  // 非equi条件

    record JoinEdge(int leftIdx, int rightIdx, SqlNode condition) {}

    /** 从左深 RelJoin 树扁平化 */
    static JoinGraph flatten(RelJoin root) {
        // 递归遍历：
        //   RelJoin(INNER) → 继续拆解左右子树
        //   其他节点 → 作为一个 relation
        // 收集所有 join condition 中的 equi 对
    }

    /** 从贪心结果重建 RelJoin 树 */
    RelNode rebuild(List<MergePair> mergeOrder) { ... }
}
```

**2. 贪心算法**

```
Input: JoinGraph(N relations, M edges)
Output: 最优 join 顺序

while relations.size() > 1:
    bestCost = MAX_VALUE
    bestPair = null
    for each edge (Ri, Rj) where Ri,Rj 尚在:
        cost = costModel.estimateRows(Ri) * costModel.estimateRows(Rj) * selectivity(edge.condition)
        if cost < bestCost:
            bestCost = cost
            bestPair = (Ri, Rj, edge)
    if bestPair == null:
        // 剩余 relations 间无 edge → 按大小合并（cartesian）
        选最小的两个合并
    合并 bestPair 为新 relation Rk
    更新 edges: 所有连接到 Ri 或 Rj 的 edge 重定向到 Rk
```

**3. JoinReorderRule**

```java
public class JoinReorderRule extends RelOptRule {
    private final CostModel costModel;

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelJoin)) return false;
        return countInnerJoinLeaves((RelJoin) node) >= 3;
    }

    @Override
    public RelNode apply(RelNode node) {
        JoinGraph graph = JoinGraph.flatten((RelJoin) node);
        if (graph.relations().size() > 10) return node; // 放弃过大搜索空间
        return greedyReorder(graph);
    }
}
```

**4. RuleOptimizer 注册**

在 `RuleOptimizer` 构造器中，JoinReorderRule 放在 `FilterJoinPushdownRule` **之后**、`JoinCommuteRule` **之前**：

```java
ruleList.add(FilterJoinPushdownRule.INSTANCE);         // 已有
ruleList.add(enableIndexedLookup ? ... : ...);         // 已有 PushFilterIntoScanRule
ruleList.add(new JoinReorderRule(costModel));           // 新增
ruleList.add(JoinCommuteRule.INSTANCE);                 // 已有，作为 fallback
```

注意：RuleOptimizer 构造器需要接受 CostModel 参数（当前没有），需要扩展。

**5. v2 可选：DP 枚举**

```
dp[bitmask] = 子集的最优 RelNode
for size 2..N:
    for each subset S of size:
        for each partition (S1, S2) of S:
            if hasEdge(S1, S2):
                cost = dp[S1].cost + dp[S2].cost + joinCost
                update dp[S] if better
```

N<=10 → 2^10=1024，完全可行。

### 测试要点
- 3 表 A(1000行) JOIN B(10行) JOIN C(100行) → 重排为 B JOIN C JOIN A
- 4 表 star schema: fact JOIN dim1 JOIN dim2 JOIN dim3
- 混合 INNER + LEFT JOIN: LEFT 不参与重排
- 2 表不触发（< 3）
- 结果正确性：重排前后 SELECT 结果相同

---

## Feature #13: 并行执行

### Fail-First Gate

**禁止设计**：
1. ❌ 修改 ExecNode 接口添加并行方法 — 破坏所有 40+ 个执行器实现，违反最小侵入原则
2. ❌ 在 next() 中启动线程 — 每次 next() 调用创建线程，开销远大于收益；正确做法是在 open() 时启动

**核心不变量**：
1. ParallelScanExec 必须实现标准 ExecNode 接口（open/next/close）
2. close() 必须终止所有后台线程，不能泄漏
3. 任何分区线程异常必须传播到 next() 调用方
4. parallelism=1 时行为等价于 ScanExec（零额外开销）

### 新建/修改文件

| 文件 | 操作 |
|------|------|
| `sql/exec/ParallelScanExec.java` | **新建** 分区并行扫描 |
| `sql/exec/GatherExec.java` | **新建** 收集多子计划输出（v2 用） |
| `sql/exec/ExecutionContext.java` | 新增 `parallelism` 字段 |
| `sql/exec/PhysicalPlanner.java` | parallelism>1 时生成 ParallelScanExec |
| `sql/datasource/DataSourceSpi.java` | 新增默认分区方法 |

### 实现步骤

**1. DataSourceSpi 扩展**

```java
default int partitionCount(String tableName) { return 1; }
default Iterator<Row> scanPartition(String tableName, int partitionId, int totalPartitions) {
    return scan(tableName); // 默认不分区
}
```

MockDataSource 实现：按 row index % totalPartitions 分区。
StorageDataSource 实现：按 page range 分区。

**2. ExecutionContext 扩展**

```java
private int parallelism = 1;
public int parallelism() { return parallelism; }
public void setParallelism(int p) { this.parallelism = Math.max(1, p); }
```

**3. ParallelScanExec**

```java
class ParallelScanExec implements ExecNode {
    private final String tableName;
    private final DataSourceSpi dataSource;
    private final int parallelism;
    private BlockingQueue<Row> queue;           // 容量 1024
    private ExecutorService executor;
    private AtomicInteger finishedCount;
    private AtomicReference<Throwable> error;
    private volatile boolean closed;
    private static final Row POISON = new Row(Map.of()); // 终止信号

    @Override
    public void open() {
        int partitions = Math.min(parallelism, dataSource.partitionCount(tableName));
        queue = new ArrayBlockingQueue<>(1024);
        finishedCount = new AtomicInteger(0);
        error = new AtomicReference<>();
        executor = Executors.newFixedThreadPool(partitions);

        for (int i = 0; i < partitions; i++) {
            final int pid = i;
            executor.submit(() -> {
                try {
                    Iterator<Row> iter = dataSource.scanPartition(tableName, pid, partitions);
                    while (iter.hasNext() && !closed) {
                        queue.put(iter.next());
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    if (finishedCount.incrementAndGet() == partitions) {
                        queue.put(POISON); // 所有分区完成
                    }
                }
            });
        }
    }

    @Override
    public Row next() {
        if (error.get() != null) throw new RuntimeException(error.get());
        Row row = queue.take(); // 阻塞等待
        if (row == POISON) return null;
        if (error.get() != null) throw new RuntimeException(error.get());
        return row;
    }

    @Override
    public void close() {
        closed = true;
        if (executor != null) executor.shutdownNow();
    }
}
```

**4. PhysicalPlanner 集成**

在 `planInternal()` 中处理 `RelScan` 时：
```java
if (executionContext != null && executionContext.parallelism() > 1
    && dataSource.partitionCount(scan.tableName()) > 1) {
    return new ParallelScanExec(scan, dataSource, executionContext.parallelism());
} else {
    return new ScanExec(scan, dataSource);
}
```

### 测试要点
- 并行 scan 结果集与串行一致（顺序无关，比较 Set）
- parallelism=1 → 退化为串行（ScanExec）
- 分区线程异常 → next() 抛出异常
- close() 后线程全部终止（无泄漏）
- 并行 scan + filter + aggregate 端到端正确

---

## 验证方案

每个特性完成后执行：
1. `mvn compile -pl mini-db` — 编译通过
2. 新增测试用例 ≥ 3 个
3. `mvn test -pl mini-db` — 全量回归
4. 端到端 SQL 执行验证
