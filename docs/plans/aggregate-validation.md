# 聚合语义校验实现计划（修正版）

## Context

当前 `SqlValidator.validateGroupBy()` 存在核心缺陷：使用 `containsAggCall()` 作为二元判断——只要表达式树中**任意位置**包含聚合函数，整个表达式就被放行。这导致混合表达式（如 `COUNT(*) + price`）中非聚合列不被检查是否出现在 GROUP BY 中，产生**静默错误结果**。

此外还有多个相关的聚合语义漏洞未被捕获。

## 当前代码问题分析

**文件**: `mini-db/src/main/java/cn/zhangyis/minidb/sql/validation/SqlValidator.java`

### Bug 1: `validateGroupBy()` 对混合表达式过度放行 (行 326-346)

```java
// 当前逻辑 (有 bug)
if (isLiteral(expression) || containsAggCall(expression)) {
    continue;  // ← 只要包含 agg，整个表达式直接通过！
}
```

**错误案例**：
- `SELECT COUNT(*) + amount FROM orders GROUP BY user_id` → `amount` 不在 GROUP BY，但通过校验
- `SELECT SUM(amount) * user_id FROM orders GROUP BY order_id` → `user_id` 不在 GROUP BY，但通过校验

### Bug 2: 隐式聚合无校验 (行 142-148)

```java
// validateCommon() 只在 groupBy != null 时调用 validateGroupBy
if (select.groupBy() != null) validateGroupBy(select, tables);
```

**错误案例**：
- `SELECT name, COUNT(*) FROM users` → 无 GROUP BY，`name` 不在任何分组中，产生随机结果

### Bug 3: WHERE 中允许聚合函数 (行 145)

```java
if (select.where() != null) validateCondition(select.where(), tables);
// validateCondition → validateExpression → 直接通过 SqlAggCall
```

**错误案例**：`SELECT * FROM users WHERE COUNT(*) > 5`

### Bug 4: HAVING 中非聚合列不受 GROUP BY 约束 (行 147)

```java
if (select.having() != null) validateCondition(select.having(), tables);
// 仅检查列存在性，不检查是否在 GROUP BY 中
```

**错误案例**：`SELECT user_id FROM orders GROUP BY user_id HAVING amount = 100` → `amount` 不在 GROUP BY

### Bug 5: ORDER BY 在聚合上下文中不受 GROUP BY 约束 (行 148)

**错误案例**：`SELECT user_id, COUNT(*) FROM orders GROUP BY user_id ORDER BY amount`

### Bug 6: 嵌套聚合函数未拒绝 (行 321-324)

```java
private void validateAggCall(SqlAggCall agg, List<TableScope> tables) {
    if (agg.arg().kind() == SqlKind.STAR) return;
    validateExpression(agg.arg(), tables);  // ← 不检查参数中是否有嵌套 agg
}
```

**错误案例**：`SELECT COUNT(SUM(amount)) FROM orders GROUP BY user_id`

---

## 修改文件

只修改一个文件：`mini-db/src/main/java/cn/zhangyis/minidb/sql/validation/SqlValidator.java`

---

## 步骤 1: 新增 `collectNonAggColumns()` 辅助方法

递归遍历表达式树，收集所有不在 `SqlAggCall` 内部的 `SqlIdentifier` 节点。
遇到 `SqlAggCall` 时**停止下降**（其参数属于聚合内部，不需要出现在 GROUP BY 中）。
遇到 `SqlSubquery` 也停止（子查询内部的列属于内层作用域）。

```java
private List<SqlIdentifier> collectNonAggColumns(SqlNode node) {
    List<SqlIdentifier> result = new ArrayList<>();
    collectNonAggColumnsImpl(unwrapAlias(node), result);
    return result;
}

private void collectNonAggColumnsImpl(SqlNode node, List<SqlIdentifier> result) {
    if (node == null || node instanceof SqlLiteral || node.kind() == SqlKind.NULL_LITERAL
        || node.kind() == SqlKind.STAR) return;
    if (node instanceof SqlAggCall) return;         // 停止：agg 内部的列不检查
    if (node instanceof SqlSubquery) return;         // 停止：子查询内层作用域
    if (node instanceof SqlIdentifier id) { result.add(id); return; }
    if (node instanceof SqlBinaryOp b) {
        collectNonAggColumnsImpl(b.left(), result);
        collectNonAggColumnsImpl(b.right(), result);
        return;
    }
    if (node instanceof SqlBetween b) {
        collectNonAggColumnsImpl(b.expr(), result);
        collectNonAggColumnsImpl(b.low(), result);
        collectNonAggColumnsImpl(b.high(), result);
        return;
    }
    if (node instanceof SqlInList in) {
        collectNonAggColumnsImpl(in.expr(), result);
        for (SqlNode v : in.values().nodes()) collectNonAggColumnsImpl(v, result);
        return;
    }
    if (node instanceof SqlFunctionCall fc) {
        for (SqlNode arg : fc.arguments().nodes()) collectNonAggColumnsImpl(arg, result);
        return;
    }
    if (node instanceof SqlCase c) {
        for (SqlCase.WhenThen wt : c.whenThens()) {
            collectNonAggColumnsImpl(wt.condition(), result);  // ⚠ 修正：原计划误用 wt.when()
            collectNonAggColumnsImpl(wt.result(), result);     // ⚠ 修正：原计划误用 wt.then()
        }
        if (c.elseExpr() != null) collectNonAggColumnsImpl(c.elseExpr(), result);
        return;
    }
    if (node instanceof SqlOrderByItem item) {
        collectNonAggColumnsImpl(item.column(), result);
        return;
    }
}
```

---

## 步骤 2: 新增 `containsNestedAgg()` 方法

检查聚合函数参数中是否嵌套了另一个聚合函数。**完整覆盖所有表达式类型**。

> ⚠ 修正：原计划只展示了 `SqlBinaryOp`，实际需覆盖全部节点类型。

```java
private boolean containsNestedAgg(SqlNode node) {
    if (node == null || node instanceof SqlLiteral || node.kind() == SqlKind.NULL_LITERAL
        || node.kind() == SqlKind.STAR) return false;
    if (node instanceof SqlAggCall) return true;
    if (node instanceof SqlSubquery) return false;
    if (node instanceof SqlIdentifier) return false;
    if (node instanceof SqlBinaryOp b) {
        return containsNestedAgg(b.left()) || containsNestedAgg(b.right());
    }
    if (node instanceof SqlBetween b) {
        return containsNestedAgg(b.expr()) || containsNestedAgg(b.low()) || containsNestedAgg(b.high());
    }
    if (node instanceof SqlInList in) {
        if (containsNestedAgg(in.expr())) return true;
        for (SqlNode v : in.values().nodes()) {
            if (containsNestedAgg(v)) return true;
        }
        return false;
    }
    if (node instanceof SqlFunctionCall fc) {
        for (SqlNode arg : fc.arguments().nodes()) {
            if (containsNestedAgg(arg)) return true;
        }
        return false;
    }
    if (node instanceof SqlCase c) {
        for (SqlCase.WhenThen wt : c.whenThens()) {
            if (containsNestedAgg(wt.condition()) || containsNestedAgg(wt.result())) return true;
        }
        return c.elseExpr() != null && containsNestedAgg(c.elseExpr());
    }
    return false;
}
```

---

## 步骤 3: 修改 `validateGroupBy()` — 修复混合表达式检查

**替换**当前的 `containsAggCall` 跳过逻辑为精确的列级检查：

```java
private void validateGroupBy(SqlSelect select, List<TableScope> tables) {
    Set<String> groupSignatures = new LinkedHashSet<>();
    for (SqlNode node : select.groupBy().nodes()) {
        validateExpression(node, tables);
        groupSignatures.add(expressionSignature(node));
    }

    // 检查 SELECT 列表
    for (SqlNode node : select.projection().nodes()) {
        SqlNode expression = unwrapAlias(node);
        if (expression.kind() == SqlKind.STAR) {
            throw new ValidationException("SELECT * not allowed with GROUP BY");
        }
        if (isLiteral(expression)) continue;
        // 完整签名匹配 GROUP BY（如 SELECT a+b ... GROUP BY a+b）
        if (groupSignatures.contains(expressionSignature(expression))) continue;
        // 提取非聚合列引用，逐个检查是否在 GROUP BY 中
        validateColumnsInGroupBy(expression, groupSignatures);
    }

    // 检查 HAVING
    if (select.having() != null) {
        validateColumnsInGroupBy(select.having(), groupSignatures);
    }

    // 检查 ORDER BY
    if (select.orderBy() != null) {
        Set<String> projectionAliases = projectionAliases(select.projection());
        for (SqlNode node : select.orderBy().nodes()) {
            SqlNode expr = orderByExpression(node);
            if (expr instanceof SqlIdentifier id
                && projectionAliases.contains(id.name().toUpperCase())) continue;
            if (isLiteral(expr)) continue;
            if (groupSignatures.contains(expressionSignature(expr))) continue;
            validateColumnsInGroupBy(expr, groupSignatures);
        }
    }
}

private void validateColumnsInGroupBy(SqlNode expr, Set<String> groupSignatures) {
    List<SqlIdentifier> nonAggCols = collectNonAggColumns(expr);
    for (SqlIdentifier col : nonAggCols) {
        if (!groupSignatures.contains(expressionSignature(col))) {
            throw new ValidationException(
                "Column '" + col.name() + "' must appear in GROUP BY or be aggregated");
        }
    }
}
```

---

## 步骤 4: 修改 `validateCommon()` — 增加隐式聚合检查和 WHERE 聚合禁止

> ⚠ 修正：`containsAggCall(select.projection())` 不能直接用——`SqlNodeList` 实现了 `SqlNode` 但 `containsAggCall` 不处理它。改为遍历 list 的辅助方法。

```java
private void validateCommon(SqlSelect select, List<TableScope> tables) {
    Set<String> projectionAliases = projectionAliases(select.projection());
    validateProjection(select.projection(), tables);

    // 新增: 禁止 WHERE 中使用聚合函数
    if (select.where() != null) {
        rejectAggInWhere(select.where());
        validateCondition(select.where(), tables);
    }

    boolean hasGroupBy = select.groupBy() != null;
    boolean hasAgg = containsAggCallInList(select.projection())
                  || (select.having() != null && containsAggCall(select.having()));

    if (hasGroupBy) {
        validateGroupBy(select, tables);
    } else if (hasAgg) {
        // 新增: 隐式聚合 — SELECT 有 agg 但无 GROUP BY
        validateImplicitAggregation(select, tables);
    }

    if (select.having() != null) validateCondition(select.having(), tables);
    if (select.orderBy() != null) validateOrderBy(select.orderBy(), tables, projectionAliases);
}

/** 遍历 SqlNodeList 中每个元素检查是否包含聚合 */
private boolean containsAggCallInList(SqlNodeList list) {
    for (SqlNode node : list.nodes()) {
        if (containsAggCall(node)) return true;
    }
    return false;
}
```

---

## 步骤 5: 新增 `validateImplicitAggregation()` 方法

```java
private void validateImplicitAggregation(SqlSelect select, List<TableScope> tables) {
    for (SqlNode node : select.projection().nodes()) {
        SqlNode expression = unwrapAlias(node);
        if (expression.kind() == SqlKind.STAR) {
            throw new ValidationException("SELECT * not allowed with aggregate functions");
        }
        if (isLiteral(expression)) continue;
        // collectNonAggColumns 返回空 = 纯聚合表达式，合法
        List<SqlIdentifier> nonAggCols = collectNonAggColumns(expression);
        if (!nonAggCols.isEmpty()) {
            throw new ValidationException(
                "Column '" + nonAggCols.get(0).name()
                + "' must appear in GROUP BY or be aggregated");
        }
    }
}
```

---

## 步骤 6: 新增 `rejectAggInWhere()` 方法

> ⚠ 修正：原计划只展示了 `SqlAggCall` 和 `SqlSubquery`，实际需完整覆盖所有表达式类型的递归。

```java
private void rejectAggInWhere(SqlNode node) {
    if (node == null || node instanceof SqlLiteral || node.kind() == SqlKind.NULL_LITERAL
        || node.kind() == SqlKind.STAR || node instanceof SqlIdentifier) return;
    if (node instanceof SqlSubquery) return;       // 子查询内部允许 agg
    if (node instanceof SqlExists) return;         // EXISTS 子查询内部允许 agg
    if (node instanceof SqlInSubquery inSub) {
        rejectAggInWhere(inSub.expr());            // 只检查外层表达式
        return;
    }
    if (node instanceof SqlAggCall) {
        throw new ValidationException("Aggregate functions not allowed in WHERE");
    }
    if (node instanceof SqlBinaryOp b) {
        rejectAggInWhere(b.left());
        rejectAggInWhere(b.right());
        return;
    }
    if (node instanceof SqlBetween b) {
        rejectAggInWhere(b.expr());
        rejectAggInWhere(b.low());
        rejectAggInWhere(b.high());
        return;
    }
    if (node instanceof SqlInList in) {
        rejectAggInWhere(in.expr());
        for (SqlNode v : in.values().nodes()) rejectAggInWhere(v);
        return;
    }
    if (node instanceof SqlFunctionCall fc) {
        for (SqlNode arg : fc.arguments().nodes()) rejectAggInWhere(arg);
        return;
    }
    if (node instanceof SqlCase c) {
        for (SqlCase.WhenThen wt : c.whenThens()) {
            rejectAggInWhere(wt.condition());
            rejectAggInWhere(wt.result());
        }
        if (c.elseExpr() != null) rejectAggInWhere(c.elseExpr());
        return;
    }
}
```

---

## 步骤 7: 修改 `validateAggCall()` — 检查嵌套聚合

```java
private void validateAggCall(SqlAggCall agg, List<TableScope> tables) {
    if (agg.arg().kind() == SqlKind.STAR) return;
    // 新增: 拒绝嵌套聚合
    if (containsNestedAgg(agg.arg())) {
        throw new ValidationException("Aggregate functions cannot be nested");
    }
    validateExpression(agg.arg(), tables);
}
```

---

## 验证方案

### 新建测试文件

`mini-db/src/test/java/cn/zhangyis/minidb/sql/AggregateValidationTest.java`

参考 `HavingAggregateTest.java` 的测试模式（MockCatalog + SqlParser + SqlValidator）。

### 测试用例

> ⚠ 修正：原计划引用了 MockCatalog 不存在的表/列（如 `t.dept`, `t.price`, `t.qty`）。
> 所有用例改为使用已有的 `users`(id, name) 和 `orders`(order_id, user_id, amount)。

| # | SQL | 预期 | 验证的校验规则 |
|---|-----|------|---------------|
| 1 | `SELECT name, COUNT(*) FROM users` | 抛出 ValidationException | 隐式聚合检查 |
| 2 | `SELECT COUNT(*) + amount FROM orders GROUP BY user_id` | 抛出 ValidationException | 混合表达式中非聚合列检查 |
| 3 | `SELECT user_id, COUNT(*) FROM orders GROUP BY user_id` | 通过 | 正常 GROUP BY |
| 4 | `SELECT COUNT(*) FROM users` | 通过 | 纯聚合无 GROUP BY |
| 5 | `SELECT * FROM users WHERE COUNT(*) > 5` | 抛出 ValidationException | WHERE 中禁止聚合 |
| 6 | `SELECT user_id, COUNT(*) FROM orders GROUP BY user_id HAVING amount = 100` | 抛出 ValidationException | HAVING 非聚合列检查 |
| 7 | `SELECT user_id, COUNT(*) FROM orders GROUP BY user_id ORDER BY amount` | 抛出 ValidationException | ORDER BY 非聚合列检查 |
| 8 | `SELECT SUM(amount), COUNT(*) FROM orders` | 通过 | 多个聚合无 GROUP BY |
| 9 | `SELECT user_id, SUM(amount) * 2 FROM orders GROUP BY user_id` | 通过 | 聚合表达式运算 |
| 10 | `SELECT 1, COUNT(*) FROM users` | 通过 | 字面量 + 聚合 |

### 执行验证

```bash
cd mini-db && mvn test -pl . -Dtest=AggregateValidationTest
```

同时确保现有测试不受影响：
```bash
cd mini-db && mvn test -pl . -Dtest=HavingAggregateTest
```
