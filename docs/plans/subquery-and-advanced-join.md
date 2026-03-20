# 子查询与高级 JOIN 实现计划

## Context

当前 mini-db 的子查询和 JOIN 系统存在两个核心问题：
1. **相关子查询装饰关联（decorrelation）硬编码** — `PhysicalPlanner.createSubqueryExecutor()` 中将外层引用替换为字面量的逻辑仅处理 `"USERS"` 表和 `"ID"` 列，无法支持任意表/列的相关子查询
2. **JOIN 选择不够智能** — `extractJoinKeys()` 只提取单个 equi-key；`CostModel.isEquiJoinCondition()` 不识别 AND 复合条件；HashJoin 无大表保护机制

## 依赖关系

```
Phase 1 (decorrelation) ← 独立
Phase 2 (UPDATE 子查询) ← 独立（不依赖 Phase 1）
Phase 3 (多 equi-key)   ← 独立
Phase 4 (fallback+CBO)  ← 依赖 Phase 3
Phase 5 (INSERT SELECT) ← 独立，建议单独实施
```

---

## Phase 1: 通用相关子查询 Decorrelation

### 状态: 待实施

### 目标
将 `PhysicalPlanner.createSubqueryExecutor()` 中硬编码的 `"USERS"`/`"ID"` 逻辑替换为通用的 AST 遍历替换机制。

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`

### 实现

#### 1.1 新增 `decorrelateWhere(SqlNode, Row)` 方法

递归遍历 WHERE 子句的 AST 树，将匹配 `outerRow` 列名的 `SqlIdentifier` 替换为对应值的 `SqlLiteral`：

```java
private SqlNode decorrelateWhere(SqlNode node, Row outerRow) {
    if (node == null || outerRow == null) return node;

    if (node instanceof SqlIdentifier id) {
        String name = id.name();
        if (outerRowContains(outerRow, name)) {
            Object val = outerRow.get(name);
            return valueToLiteral(val);
        }
        return node;
    }

    if (node instanceof SqlBinaryOp binOp) {
        SqlNode newLeft = decorrelateWhere(binOp.left(), outerRow);
        SqlNode newRight = decorrelateWhere(binOp.right(), outerRow);
        if (newLeft == binOp.left() && newRight == binOp.right()) return node;
        return new SqlBinaryOp(binOp.kind(), newLeft, newRight);
    }

    if (node instanceof SqlBetween between) {
        SqlNode newExpr = decorrelateWhere(between.expr(), outerRow);
        SqlNode newLow = decorrelateWhere(between.low(), outerRow);
        SqlNode newHigh = decorrelateWhere(between.high(), outerRow);
        if (newExpr == between.expr() && newLow == between.low() && newHigh == between.high()) return node;
        return new SqlBetween(newExpr, newLow, newHigh);
    }

    // SqlLiteral, SqlAggCall, SqlStar 等叶子节点 → 原样返回
    return node;
}
```

#### 1.2 新增辅助方法

- `outerRowContains(Row outerRow, String name)` — 遍历 `outerRow.columns().keySet()` 判断列名是否属于外层行（支持 qualified 和 bare 两种格式匹配，与 `Row.get()` 解析逻辑一致）
- `valueToLiteral(Object value)` — 将 Java 值转为 `SqlLiteral`，支持 Integer→INT32, Long→BIGINT, Double→DECIMAL, String→VARCHAR, null→NULL_LITERAL

#### 1.3 替换 `createSubqueryExecutor()` 中的硬编码块

删除行 361-376（硬编码的 `"USERS"`/`"ID"` 检查），替换为：
```java
if (outerRow != null && select.where() != null) {
    SqlNode newWhere = decorrelateWhere(select.where(), outerRow);
    if (newWhere != select.where()) {
        select = new SqlSelect(select.projection(), select.from(), newWhere,
            select.distinct(), select.groupBy(), select.having(),
            select.orderBy(), select.limit(), select.offset());
    }
}
```

#### 1.4 注意事项
- 初版只覆盖 `SqlBinaryOp` + `SqlBetween`，后续按需扩展 `SqlFunctionCall`/`SqlInList`/`SqlCase`
- 自引用/自关联场景：SQL 使用不同别名时 Row 列名会带别名前缀，不会冲突

#### 1.5 验证
- 现有 `ExistsInSubqueryTest` 全部通过（回归）
- 可使用任意表名的相关子查询（不再限于 USERS）

---

## Phase 2: UPDATE SET col = (SELECT ...) 支持

### 状态: 待实施

### 限制
仅支持非相关 SET 子查询。相关子查询（如 `SET col = (SELECT ... WHERE t2.id = t.id)`）需扩展 `SubqueryEvaluator` 接口，不在本 Phase 范围内。

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/UpdateExec.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`

### 实现

#### 2.1 UpdateExec 添加 SubqueryEvaluator 字段

```java
private final FilterExec.SubqueryEvaluator subqueryEvaluator; // nullable
```

保留现有三个构造器不变（evaluator 传 null），新增四参构造器。

#### 2.2 UpdateExec.open() 中使用 evaluator

将 `FilterExec.resolveValue(assign.value(), row)` 改为：
```java
Object value = FilterExec.resolveValue(assign.value(), row, subqueryEvaluator);
```

#### 2.3 PhysicalPlanner 传入 evaluator

在 `planInternal()` 处理 `RelUpdate` 时：
```java
if (relNode instanceof RelUpdate update) {
    return new UpdateExec(update, dataSource, catalog, createSubqueryEvaluator());
}
```

#### 2.4 验证
- 新增测试：`UPDATE t SET col = (SELECT max(val) FROM t2) WHERE id = 1`

---

## Phase 3: 多 Equi-Key 提取（复合 ON 条件）

### 状态: 待实施

### 目标
支持 `ON a.id = b.id AND a.type = b.type` 这样的多 equi-key JOIN 条件。

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/HashJoinExec.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/SortMergeJoinExec.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/cost/CostModel.java`

### 实现

#### 3.1 JoinKeys 扩展为多 key

```java
private record JoinKeys(List<String> leftKeys, List<String> rightKeys) {
    String leftKey()  { return leftKeys.get(0); }
    String rightKey() { return rightKeys.get(0); }
}
```

#### 3.2 extractJoinKeys() 增强

新增 `collectEquiPairs(SqlNode)` 递归处理 AND 节点，收集 `SqlBinaryOp(EQ, SqlIdentifier, SqlIdentifier)` 对。每对独立用 `identifierBelongsTo()` 对齐 left/right。

#### 3.3 HashJoinExec 多 key 支持

- `Map<List<Object>, List<Row>>` 作为 hash 表
- `computeHashKey(Row, List<String>)` 计算组合 key
- NULL 处理：probe 时任一 key 为 null 则跳过
- 保留单 key 构造器兼容

#### 3.4 SortMergeJoinExec 多 key 支持

- 逐 key 比较，第一个非零结果决定顺序
- 保留单 key 构造器兼容

#### 3.5 CostModel.isEquiJoinCondition() 支持 AND 组合

递归检查 AND 链中所有子条件都是 equi 条件。

#### 3.6 IndexNL 降级

多 key 时（`keys.leftKeys().size() > 1`）跳过 IndexNL，因为需要复合索引支持。

#### 3.7 验证
- 新增测试：双 key JOIN `ON a.id = b.id AND a.type = b.type`
- 现有 `JoinCommuteRuleTest` 全部通过（回归）

---

## Phase 4: HashJoin Fallback 与智能 JOIN 选择

### 状态: 待实施

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/cost/CostModel.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/cost/CostOptimizer.java`

### 实现

#### 4.1 PhysicalPlanner 基数预判 fallback

HASH_JOIN 分支中，右表预估行数超过 `MAX_HASH_BUILD_ROWS`(100,000) 时降级到 SortMerge。

#### 4.2 CostModel 多 key selectivity

```java
private double equiJoinSelectivity(SqlNode condition) {
    int keyCount = countEquiKeys(condition);
    return Math.pow(0.1, keyCount);
}
```

#### 4.3 自动启用 IndexNL for StorageDataSource

`PhysicalPlanner(DataSourceSpi)` 构造器中 `dataSource instanceof StorageDataSource` 时设 `enableIndexLookup=true`。

#### 4.4 验证
- 大表 JOIN 自动降级到 SortMerge
- 现有测试全部通过

---

## Phase 5（可选）: INSERT INTO ... SELECT

### 状态: 待实施（建议独立实施）

### 修改文件（6+ 文件）
- `SqlInsertSelect.java` — 新建
- `SqlParser.java`
- `SqlValidator.java`
- `SqlToRelConverter.java`
- `RelInsertSelect.java` — 新建
- `InsertSelectExec.java` — 新建
- `PhysicalPlanner.java`

### 实现概要
1. **AST**: 新建 `SqlInsertSelect(table, columns, select)` record
2. **Parser**: `parseInsert()` 在 `VALUES` 之前检查 `SELECT` token
3. **Validator**: 验证列数匹配
4. **Converter**: 转为 `RelInsertSelect`
5. **Executor**: `InsertSelectExec` 执行 SELECT 得到行，逐行 `dataSource.insertRow()`
6. **PhysicalPlanner**: 新增 `RelInsertSelect` → `InsertSelectExec` 映射

---

## 验证

```bash
# 回归测试
mvn test -pl mini-db -Dtest=ExistsInSubqueryTest
mvn test -pl mini-db -Dtest=DerivedTableTest
mvn test -pl mini-db -Dtest=JoinCommuteRuleTest

# 全量测试
mvn test -pl mini-db
```
