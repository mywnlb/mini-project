# Semi-Join / Anti-Join 优化实现计划

## Context

当前 mini-db 对 `IN (SELECT ...)` 和 `EXISTS (SELECT ...)` 子查询的处理方式是**逐行迭代执行**：
`SqlInSubquery` 留在 `RelFilter.condition` 中，由 `FilterExec.evaluateInSubquery()` 对外层**每一行**重新编译并执行整个子查询计划。

- 外表 M 行、子查询 N 行 → 总代价 O(M × N)，且每行都有 Parse + Validate + Plan 的开销
- PolarDB-X 文档明确指出：这种迭代方式在数据量较大时"可能会非常慢"
- 正确做法是**子查询去关联化（Subquery Unnesting）**——将 IN/EXISTS 转换为 Semi-Join / Anti-Join 物理算子

## PolarDB-X 参考

参考 `docs/sql_优化/` 下的 PolarDB 文档，本方案借鉴以下设计思路：

### 已借鉴

| PolarDB 技术 | 本方案对应 | 出处 |
|---|---|---|
| **子查询去关联化**：IN → SemiJoin，NOT IN → AntiJoin | `SubqueryUnnestingRule` 优化规则 | `1查询优化器介绍.md` RBO 阶段、`5子查询优化和执行.md` |
| **SemiHashJoin 物理算子**：build 右表 hash 表，probe 左表，命中即输出左行 | `SemiHashJoinExec` / `AntiHashJoinExec` 执行器 | `4JOIN优化和执行.md` Hash Join 原理 |
| **CBO 选择 JOIN 算法**：根据代价选择 NL/Hash/SortMerge | `CostModel` 扩展 semi/anti join 代价估计 | `1查询优化器介绍.md` CBO 阶段 |
| **Filter + JOIN 条件分离**：WHERE 中非子查询部分保留为 Filter | 规则实现中的 AND 条件拆分逻辑 | `2查询改写与下推.md` 谓词下推 |

### 未借鉴（不适用于单节点架构）

| PolarDB 技术 | 不借鉴原因 |
|---|---|
| 子查询下推到存储层 MySQL | mini-db 是单节点数据库，无计算/存储分离 |
| JoinClustering 多表 JOIN 重排序 | 当前无需跨分片优化 |
| Lookup Join (BKAJoin) 用于 Semi-Join | 需要远程分批 IN 查询能力，单节点用 Hash 更优 |

---

## 当前问题分析

### 执行路径（当前）

```
SQL: SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)

Parser  → SqlInSubquery(SqlIdentifier("id"), SqlSelect(...), false)
Validator → 校验列存在性
Converter → RelFilter(RelScan("users"), condition=SqlInSubquery(...))
Optimizer → 无专用规则，SqlInSubquery 保持原样
Planner → FilterExec(ScanExec("users"), condition=SqlInSubquery(...))
Executor → 对每行 users：编译+执行 SELECT user_id FROM orders → O(M × N)
```

### 执行路径（优化后）

```
SQL: SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)

Parser  → SqlInSubquery(SqlIdentifier("id"), SqlSelect(...), false)
Validator → 校验列存在性
Converter → RelFilter(RelScan("users"), condition=SqlInSubquery(...))
Optimizer → SubqueryUnnestingRule 匹配
         → RelSemiJoin(RelScan("users"), RelScan("orders"), id = user_id)
Planner → SemiHashJoinExec(ScanExec("users"), ScanExec("orders"), "id", "user_id")
Executor → Build orders hash 表 O(N) + Probe users O(M) → 总代价 O(M + N)
```

---

## 修改文件清单

| 操作 | 文件路径 | 说明 |
|---|---|---|
| **新建** | `sql/rel/RelSemiJoin.java` | Semi-Join 逻辑节点 |
| **新建** | `sql/rel/RelAntiJoin.java` | Anti-Join 逻辑节点 |
| **新建** | `sql/optimize/SubqueryUnnestingRule.java` | IN/EXISTS → Semi/Anti-Join 转换规则 |
| **新建** | `sql/exec/SemiHashJoinExec.java` | Semi-Join Hash 执行器 |
| **新建** | `sql/exec/AntiHashJoinExec.java` | Anti-Join Hash 执行器 |
| **修改** | `sql/optimize/RuleOptimizer.java` | 注册新规则 + optimizeChildren 支持新节点 |
| **修改** | `sql/exec/PhysicalPlanner.java` | 新增 RelSemiJoin/RelAntiJoin → ExecNode 分派 |
| **修改** | `sql/optimize/cost/CostModel.java` | 新增 semi/anti join 基数估计 |
| **修改** | `sql/optimize/cost/CostOptimizer.java` | 新增 explain 支持 |
| **新建** | `test/.../SemiAntiJoinTest.java` | 端到端测试 |

---

## 步骤 1: 新建 `RelSemiJoin` 和 `RelAntiJoin`

继承 `RelNode`，结构与 `RelJoin` 类似但语义不同：

- Semi-Join：只输出左侧行，不合并右侧列
- Anti-Join：只输出在右侧无匹配的左侧行

```java
// RelSemiJoin.java
package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import java.util.List;

public class RelSemiJoin extends RelNode {
    private final RelNode left;
    private final RelNode right;
    private final SqlNode condition;  // equi 条件: left.col = right.col

    public RelSemiJoin(RelNode left, RelNode right, SqlNode condition) {
        this.left = left;
        this.right = right;
        this.condition = condition;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelSemiJoin(inputs.get(0), inputs.get(1), condition);
    }

    @Override
    public String explain() {
        return "RelSemiJoin(condition=" + condition + ")\n" +
               "  left: " + left.explain() + "\n" +
               "  right: " + right.explain();
    }

    public RelNode left() { return left; }
    public RelNode right() { return right; }
    public SqlNode condition() { return condition; }
}
```

`RelAntiJoin` 结构完全相同，仅类名和 explain 输出不同。

---

## 步骤 2: 新建 `SubqueryUnnestingRule`

这是核心优化规则，匹配 `RelFilter` 其 condition 包含 `SqlInSubquery` 或 `SqlExists`。

### 匹配条件

```java
@Override
public boolean matches(RelNode node) {
    return node instanceof RelFilter filter
        && containsSubquery(filter.condition());
}

private boolean containsSubquery(SqlNode node) {
    if (node instanceof SqlInSubquery || node instanceof SqlExists) return true;
    if (node instanceof SqlBinaryOp b) {
        return containsSubquery(b.left()) || containsSubquery(b.right());
    }
    return false;
}
```

### 转换逻辑

对 WHERE 条件的 AND 树进行拆分，将子查询部分转换为 Semi/Anti-Join，非子查询部分保留为 Filter。

**Case 1: `WHERE col IN (SELECT col2 FROM t2)`（非关联）**

```
Input:  RelFilter(RelScan("users"), SqlInSubquery(id, SELECT user_id FROM orders, false))
Output: RelSemiJoin(RelScan("users"), subqueryPlan, id = user_id)
```

转换步骤：
1. 取出 `SqlInSubquery.expr()` → 左侧 key（如 `id`）
2. 取出 `SqlInSubquery.select()` → 内层 SELECT
3. 从内层 SELECT 的 projection 取出第一列 → 右侧 key（如 `user_id`）
4. 将内层 SELECT 的 FROM 子句转为 RelNode 作为右侧输入
5. 如果内层有 WHERE，将其作为 RelFilter 包裹右侧输入
6. 构造 `equi 条件: SqlBinaryOp(EQ, leftKey, rightKey)`
7. `negated = false` → `RelSemiJoin`，`negated = true` → `RelAntiJoin`

**Case 2: `WHERE col IN (SELECT col2 FROM t2 WHERE t2.x = outer.y)`（关联子查询）**

关联子查询的 WHERE 中包含外层表引用。处理策略：
1. 遍历内层 WHERE，分离出**关联条件**（引用外层表的等值条件）和**本地条件**（仅引用内层表）
2. 本地条件留在右侧 RelFilter 中
3. 关联条件提升为 Semi-Join 的 join condition（与 IN 的 equi 条件 AND 合并）

**注意**：对于无法拆分的复杂关联条件（如 OR 连接、非等值关联），放弃转换，保持原 Filter + 迭代执行。

**Case 3: `WHERE EXISTS (SELECT * FROM t2 WHERE t2.id = outer.id)`**

1. 取出 `SqlExists.select()` → 内层 SELECT
2. 从内层 WHERE 分离关联条件 → 作为 join condition
3. 本地条件留在右侧
4. `negated = false` → `RelSemiJoin`，`negated = true` → `RelAntiJoin`

**Case 4: `WHERE x > 5 AND id IN (SELECT ...)`（混合条件）**

1. 展平 AND 树为条件列表
2. 分别处理：子查询条件 → Semi/Anti-Join，普通条件 → 保留
3. 结果：`RelFilter(RelSemiJoin(...), x > 5)` 或 `RelSemiJoin(RelFilter(input, x > 5), ...)`
4. 后续 `FilterJoinPushdownRule` 等规则可进一步优化

### 实现代码骨架

```java
package cn.zhangyis.minidb.sql.optimize;

public class SubqueryUnnestingRule extends RelOptRule {
    public static final SubqueryUnnestingRule INSTANCE = new SubqueryUnnestingRule();

    @Override
    public boolean matches(RelNode node) {
        return node instanceof RelFilter filter
            && containsSubquery(filter.condition());
    }

    @Override
    public RelNode apply(RelNode node) {
        RelFilter filter = (RelFilter) node;
        List<SqlNode> conditions = flattenAnd(filter.condition());

        RelNode current = filter.input();
        List<SqlNode> remaining = new ArrayList<>();

        for (SqlNode cond : conditions) {
            if (cond instanceof SqlInSubquery inSub) {
                current = convertInSubquery(current, inSub);
            } else if (cond instanceof SqlExists exists) {
                current = convertExists(current, exists);
            } else {
                remaining.add(cond);
            }
        }

        // 剩余非子查询条件包为 Filter
        if (!remaining.isEmpty()) {
            current = new RelFilter(current, buildAnd(remaining));
        }
        return current;
    }

    private RelNode convertInSubquery(RelNode left, SqlInSubquery inSub) {
        // 1. 左侧 key
        String leftKey = extractColumnName(inSub.expr());

        // 2. 右侧：将子查询的 FROM 转为 RelNode
        RelNode right = convertSubqueryToRel(inSub.select());

        // 3. 右侧 key：子查询 projection 的第一列
        String rightKey = extractFirstProjectionColumn(inSub.select());

        // 4. 构造 equi 条件
        SqlNode joinCond = new SqlBinaryOp(SqlKind.BINARY_EQ,
            new SqlIdentifier(leftKey), new SqlIdentifier(rightKey));

        // 5. 合并关联条件（如果有）
        CorrelationResult corr = extractCorrelation(inSub.select());
        if (corr.correlatedCondition != null) {
            joinCond = new SqlBinaryOp(SqlKind.AND, joinCond, corr.correlatedCondition);
            right = corr.localFilter != null ? new RelFilter(right, corr.localFilter) : right;
        }

        return inSub.negated()
            ? new RelAntiJoin(left, right, joinCond)
            : new RelSemiJoin(left, right, joinCond);
    }

    private RelNode convertExists(RelNode left, SqlExists exists) {
        RelNode right = convertSubqueryToRel(exists.select());
        CorrelationResult corr = extractCorrelation(exists.select());

        if (corr.correlatedCondition == null) {
            // 无关联 EXISTS → 退化为 constant true/false，不转换
            return new RelFilter(left, exists);
        }

        if (corr.localFilter != null) {
            right = new RelFilter(right, corr.localFilter);
        }

        return exists.negated()
            ? new RelAntiJoin(left, right, corr.correlatedCondition)
            : new RelSemiJoin(left, right, corr.correlatedCondition);
    }

    /** 将子查询的 FROM 子句转为 RelNode (复用 SqlToRelConverter 的逻辑) */
    private RelNode convertSubqueryToRel(SqlSelect select) {
        // 需要传入 CatalogSpi，通过规则的构造函数注入
        // 使用 SqlValidator + SqlToRelConverter 处理子查询的 FROM
        // ...
    }

    /** 分离关联条件和本地条件 */
    private CorrelationResult extractCorrelation(SqlSelect select) {
        // 遍历 WHERE AST，判断 SqlIdentifier 是否引用外层表
        // 关联条件(如 outer.id = inner.id) → correlatedCondition
        // 本地条件(如 inner.status = 'active') → localFilter
        // ...
    }

    record CorrelationResult(SqlNode correlatedCondition, SqlNode localFilter) {}
}
```

### 规则需要 CatalogSpi

为了将子查询的 FROM 子句转为 RelNode，规则需要访问 catalog 以解析表元数据。两种方案：

**方案 A（推荐）**：规则持有 CatalogSpi 引用，在 RuleOptimizer 构造时传入。

```java
public SubqueryUnnestingRule(CatalogSpi catalog) {
    this.catalog = catalog;
}
```

**方案 B**：规则只做 RelNode 重组，子查询的 RelNode 转换延迟到规则匹配时从已有 RelFilter 中提取。

选择方案 A，因为子查询转换需要表元数据。

---

## 步骤 3: 新建 `SemiHashJoinExec` 和 `AntiHashJoinExec`

### SemiHashJoinExec

基于 `HashJoinExec` 的实现模式，关键区别：
- **只输出左侧行**（不 merge 右侧列）
- **命中即输出**（不需要遍历所有匹配行）
- **去重语义**：左侧一行即使匹配右侧多行，也只输出一次

```java
package cn.zhangyis.minidb.sql.exec;

import java.util.*;

/**
 * Semi Hash Join：只返回左侧行，右侧仅用于存在性判断
 * Build: 物化右表按 join key 建 HashSet（非 HashMap，不需要值）
 * Probe: 扫描左表，key 命中即输出左行
 * 时间复杂度: O(M + N)，空间: O(N) — N 为右侧去重后的 key 集合大小
 */
public class SemiHashJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final List<String> leftKeys;
    private final List<String> rightKeys;

    private Set<List<Object>> rightKeySet;

    public SemiHashJoinExec(ExecNode left, ExecNode right,
                            String leftKey, String rightKey) {
        this(left, right, List.of(leftKey), List.of(rightKey));
    }

    public SemiHashJoinExec(ExecNode left, ExecNode right,
                            List<String> leftKeys, List<String> rightKeys) {
        this.left = left;
        this.right = right;
        this.leftKeys = leftKeys;
        this.rightKeys = rightKeys;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        // Build: 物化右表 key 集合（去重，只存 key 不存行）
        rightKeySet = new HashSet<>();
        Row row;
        while ((row = right.next()) != null) {
            List<Object> key = computeKey(row, rightKeys);
            if (key != null) rightKeySet.add(key);
        }
    }

    @Override
    public Row next() {
        Row leftRow;
        while ((leftRow = left.next()) != null) {
            List<Object> probeKey = computeKey(leftRow, leftKeys);
            if (probeKey != null && rightKeySet.contains(probeKey)) {
                return leftRow;  // 命中 → 输出左行（不 merge 右行）
            }
        }
        return null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        rightKeySet = null;
    }

    private static List<Object> computeKey(Row row, List<String> keys) {
        List<Object> composite = new ArrayList<>(keys.size());
        for (String key : keys) {
            Object val = row.get(key);
            if (val == null) return null;
            composite.add(val);
        }
        return composite;
    }
}
```

### AntiHashJoinExec

与 SemiHashJoinExec 几乎相同，唯一区别：**key 未命中时才输出左行**。

```java
@Override
public Row next() {
    Row leftRow;
    while ((leftRow = left.next()) != null) {
        List<Object> probeKey = computeKey(leftRow, leftKeys);
        // NULL key 的行: SQL 三值逻辑下 NULL NOT IN (...) → UNKNOWN → 不输出
        if (probeKey == null) continue;
        if (!rightKeySet.contains(probeKey)) {
            return leftRow;  // 未命中 → 输出左行
        }
    }
    return null;
}
```

> **NOT IN NULL 语义说明**：
> 标准 SQL 中 `x NOT IN (1, 2, NULL)` 永远不返回 TRUE（结果为 FALSE 或 UNKNOWN）。
> 当前实现采用简化处理：右侧 NULL key 不入 HashSet，左侧 NULL key 直接跳过。
> 这在右侧结果集**不含 NULL** 时语义正确。
> 完整的 NULL-aware Anti-Join 作为后续增强项，需要额外追踪 `rightHasNull` 标志。

---

## 步骤 4: 修改 `PhysicalPlanner`

在 `planInternal()` 中新增 `RelSemiJoin` 和 `RelAntiJoin` 分派。

```java
// PhysicalPlanner.planInternal() 中新增：
if (relNode instanceof RelSemiJoin semi) {
    return planSemiJoin(semi, overrideAlgo);
}
if (relNode instanceof RelAntiJoin anti) {
    return planAntiJoin(anti, overrideAlgo);
}
```

```java
private ExecNode planSemiJoin(RelSemiJoin semi, JoinAlgorithm overrideAlgo) {
    ExecNode left = planInternal(semi.left(), overrideAlgo);
    ExecNode right = planInternal(semi.right(), overrideAlgo);
    JoinKeys keys = extractSemiJoinKeys(semi);
    if (keys != null) {
        ExecNode exec = new SemiHashJoinExec(left, right, keys.leftKeys(), keys.rightKeys());
        return wrapResidual(exec, keys.residual());
    }
    // 无法提取 equi-key → 退化为 NestedLoop Semi（逐行检查右侧是否有匹配）
    return new SemiNestedLoopExec(left, right, semi.condition());
}

private ExecNode planAntiJoin(RelAntiJoin anti, JoinAlgorithm overrideAlgo) {
    ExecNode left = planInternal(anti.left(), overrideAlgo);
    ExecNode right = planInternal(anti.right(), overrideAlgo);
    JoinKeys keys = extractSemiJoinKeys(anti);  // 复用 key 提取逻辑
    if (keys != null) {
        ExecNode exec = new AntiHashJoinExec(left, right, keys.leftKeys(), keys.rightKeys());
        return wrapResidual(exec, keys.residual());
    }
    return new AntiNestedLoopExec(left, right, anti.condition());
}
```

> **关于 NL 回退**：如果 Semi/Anti-Join 条件无法提取 equi-key（理论上不会发生，因为 SubqueryUnnestingRule 只在有 equi 条件时转换），可以实现简单的 `SemiNestedLoopExec` 作为安全回退，或直接抛异常。初始实现中，可以先只支持 Hash 路径，非 equi 情况保留为 FilterExec 迭代执行（不转换）。

---

## 步骤 5: 修改 `RuleOptimizer`

### 5.1 注册新规则

```java
public RuleOptimizer(boolean enableIndexedLookup, CatalogSpi catalog) {
    this.rules = List.of(
        new SubqueryUnnestingRule(catalog),  // 新增：必须在其他规则之前
        ConstantFoldingRule.INSTANCE,
        FilterProjectTransposeRule.INSTANCE,
        FilterJoinPushdownRule.INSTANCE,
        enableIndexedLookup ? new PushFilterIntoScanRule() : PushFilterIntoScanRule.INSTANCE,
        JoinCommuteRule.INSTANCE
    );
}
```

> **规则顺序**：`SubqueryUnnestingRule` 必须排在 `FilterJoinPushdownRule` 之前，因为：
> 1. 先将 Filter(SqlInSubquery) → SemiJoin
> 2. 然后 FilterJoinPushdownRule 可以将 SemiJoin 上方的剩余 Filter 下推

### 5.2 optimizeChildren 支持新节点

```java
private RelNode optimizeChildren(RelNode node) {
    // ... 现有 case ...
    if (node instanceof RelSemiJoin semi) {
        RelNode optL = optimize(semi.left());
        RelNode optR = optimize(semi.right());
        return (optL != semi.left() || optR != semi.right())
            ? semi.copy(List.of(optL, optR)) : node;
    }
    if (node instanceof RelAntiJoin anti) {
        RelNode optL = optimize(anti.left());
        RelNode optR = optimize(anti.right());
        return (optL != anti.left() || optR != anti.right())
            ? anti.copy(List.of(optL, optR)) : node;
    }
    return node;
}
```

---

## 步骤 6: 修改 `CostModel` 和 `CostOptimizer`

### CostModel.estimateRows()

```java
// Semi-Join 输出行数 ≈ 左表行数 × 选择率
if (node instanceof RelSemiJoin s) {
    int keys = countEquiKeys(s.condition());
    double sel = keys > 0 ? Math.min(0.5, Math.pow(0.3, keys)) : 0.3;
    return estimateRows(s.left()) * sel;
}
// Anti-Join 输出行数 ≈ 左表行数 × (1 - 选择率)
if (node instanceof RelAntiJoin a) {
    int keys = countEquiKeys(a.condition());
    double sel = keys > 0 ? Math.min(0.5, Math.pow(0.3, keys)) : 0.3;
    return estimateRows(a.left()) * (1 - sel);
}
```

### CostOptimizer.decidePhysicalPlan()

```java
if (plan instanceof RelSemiJoin semi) {
    return "SEMI_HASH_JOIN(condition=" + semi.condition() + ")\n" +
           "  left: " + decidePhysicalPlan(semi.left()) + "\n" +
           "  right: " + decidePhysicalPlan(semi.right());
}
if (plan instanceof RelAntiJoin anti) {
    return "ANTI_HASH_JOIN(condition=" + anti.condition() + ")\n" +
           "  left: " + decidePhysicalPlan(anti.left()) + "\n" +
           "  right: " + decidePhysicalPlan(anti.right());
}
```

---

## 步骤 7: 向后兼容

### 7.1 RuleOptimizer 构造器兼容

保留无参和单参构造器，使现有测试不受影响：

```java
public RuleOptimizer() {
    this(false, null);  // catalog=null → SubqueryUnnestingRule 被跳过
}

public RuleOptimizer(boolean enableIndexedLookup) {
    this(enableIndexedLookup, null);
}

public RuleOptimizer(boolean enableIndexedLookup, CatalogSpi catalog) {
    List<RelOptRule> ruleList = new ArrayList<>();
    if (catalog != null) {
        ruleList.add(new SubqueryUnnestingRule(catalog));
    }
    ruleList.add(ConstantFoldingRule.INSTANCE);
    // ... 其他规则 ...
    this.rules = List.copyOf(ruleList);
}
```

### 7.2 FilterExec 迭代执行保留

当 `SubqueryUnnestingRule` 无法转换（如 OR 条件中的子查询、无法提取 equi-key）时，`SqlInSubquery` 仍保留在 `RelFilter.condition` 中，由 `FilterExec` 的现有迭代执行路径处理。这保证了功能正确性。

---

## 已知限制

| 限制 | 说明 | 后续计划 |
|---|---|---|
| NOT IN NULL 语义 | 右侧含 NULL 时，Anti-Join 结果不完全正确 | 增加 NULL-aware Anti-Join |
| OR 中的子查询 | `WHERE x > 5 OR id IN (SELECT ...)` 不会被转换 | 复杂度高，暂不支持 |
| 非等值关联 | `WHERE EXISTS (SELECT ... WHERE inner.x > outer.y)` 不会被转换 | 需要 theta-join semi |
| 多层嵌套子查询 | `WHERE id IN (SELECT id FROM t WHERE id IN (SELECT ...))` 只转换最外层 | 递归优化 |

---

## 验证方案

### 新建测试文件

`mini-db/src/test/java/cn/zhangyis/minidb/sql/SemiAntiJoinTest.java`

参考 `HavingAggregateTest.java` 的测试模式（MockCatalog + 端到端执行）。

### 测试用例

使用 MockCatalog 已有表：`users`(id, name)、`orders`(order_id, user_id, amount)

| # | SQL | 预期 | 验证点 |
|---|-----|------|--------|
| 1 | `SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)` | 返回 orders 中有订单的 users | 基本 Semi-Join |
| 2 | `SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders)` | 返回无订单的 users | 基本 Anti-Join |
| 3 | `SELECT * FROM users WHERE id IN (SELECT user_id FROM orders) AND name = 'alice'` | 混合条件：Semi-Join + Filter | 条件拆分 |
| 4 | `SELECT * FROM users WHERE EXISTS (SELECT * FROM orders WHERE orders.user_id = users.id)` | 等同于 #1 | EXISTS → Semi-Join |
| 5 | `SELECT * FROM users WHERE NOT EXISTS (SELECT * FROM orders WHERE orders.user_id = users.id)` | 等同于 #2 | NOT EXISTS → Anti-Join |
| 6 | `SELECT user_id, COUNT(*) FROM orders GROUP BY user_id HAVING user_id IN (SELECT id FROM users)` | HAVING 中的 IN 子查询（保持迭代执行，不影响） | 回归 |
| 7 | `SELECT * FROM users` | 全表扫描不受影响 | 回归 |
| 8 | `SELECT COUNT(*) FROM users WHERE id IN (SELECT user_id FROM orders)` | 纯聚合 + Semi-Join | Semi-Join 后接聚合 |

### 逻辑计划结构验证

```java
@Test
void inSubquery_planContainsSemiJoin() {
    RelNode plan = buildPlan("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)");
    // 预期: Project → SemiJoin(left=Scan("users"), right=Scan("orders"))
    assertInstanceOf(RelProject.class, plan);
    RelSemiJoin semi = assertInstanceOf(RelSemiJoin.class, ((RelProject) plan).input());
    assertInstanceOf(RelScan.class, semi.left());
    assertInstanceOf(RelScan.class, semi.right());
}
```

### 执行验证

```bash
cd mini-db && mvn test -pl . -Dtest=SemiAntiJoinTest
```

同时确保现有测试不受影响：
```bash
cd mini-db && mvn test -pl . -Dtest=HavingAggregateTest
```

---

## 自查记录

### 第一轮自查

| # | 检查项 | 结果 | 修正 |
|---|--------|------|------|
| 1 | `SubqueryUnnestingRule` 需要 CatalogSpi 才能将子查询 FROM 转为 RelNode | 确认需要 | 规则构造函数接收 CatalogSpi；RuleOptimizer 无参构造器跳过此规则 |
| 2 | `extractSemiJoinKeys()` 复用 `extractJoinKeys()` | RelSemiJoin 不是 RelJoin，参数类型不匹配 | 在 PhysicalPlanner 中新增重载或提取通用工具方法 |
| 3 | SemiHashJoinExec 的 `computeKey()` 与 HashJoinExec 重复 | 可以但无需合并 | 保持各自独立，避免引入不必要的耦合 |
| 4 | `optimizeChildren()` 缺少 RelSemiJoin/RelAntiJoin 处理 | 确认 | 已在步骤 5.2 添加 |
| 5 | `CostOptimizer.decidePhysicalPlan()` 缺少新节点处理 | 确认 | 已在步骤 6 添加 |
| 6 | Anti-Join 的 NULL key 处理 | 简化处理有边界情况 | 在"已知限制"中记录，初始实现可接受 |
| 7 | EXISTS 无关联条件时的处理 | 无关联 EXISTS 退化为 constant，不应转为无条件 Semi-Join | 规则中对无关联 EXISTS 不做转换，保留原 Filter |

### 第二轮自查

| # | 检查项 | 结果 | 修正 |
|---|--------|------|------|
| 1 | 子查询 projection 可能有别名 `SELECT user_id AS uid FROM orders` | `extractFirstProjectionColumn()` 需要 unwrapAlias | 添加 unwrapAlias 处理 |
| 2 | 子查询有 GROUP BY / HAVING / ORDER BY / LIMIT | 不影响，整个子查询转为右侧 RelNode（含聚合/排序），equi-key 基于 projection 第一列 | 无需修正，但需确保 `convertSubqueryToRel` 完整转换 |
| 3 | `identifierBelongsTo()` 不识别 RelSemiJoin/RelAntiJoin | 后续 `FilterJoinPushdownRule` 可能需要透过 Semi/Anti-Join 判断 | Semi/Anti-Join 上方一般不会有需要下推的 Filter；如遇到，`determineSidePrecise` 会归为 BOTH，不会误下推 |
| 4 | 现有 `HavingAggregateTest` 中 `buildPlan` 使用 `new RuleOptimizer()` | 无参构造器不加载 SubqueryUnnestingRule，测试不受影响 | 确认向后兼容 |
| 5 | MockCatalog 中 orders 表无 `user_id` 的索引，Semi-Join 不需要索引 | Hash Semi-Join 不依赖索引 | 无需修正 |
| 6 | `SqlToRelConverter` 不需要改动 | 子查询仍保留在 RelFilter.condition 中，由优化器转换 | 确认，设计正确 |
| 7 | 右侧子查询有 DISTINCT 时 | HashSet 自然去重，不影响 Semi-Join 正确性 | 无需修正 |
| 8 | 多个 IN 子查询 `WHERE a IN (...) AND b IN (...)` | `flattenAnd` + 循环处理，产生嵌套 Semi-Join：`SemiJoin(SemiJoin(input, right1), right2)` | 确认正确，每层 Semi-Join 独立处理 |
