# LEFT/RIGHT/FULL OUTER JOIN 实施计划

## Context

当前 mini-db 的 JOIN 系统仅支持 INNER JOIN：
- `SqlJoin` record 无 JoinType 字段
- Parser 只识别 `JOIN` / `INNER JOIN` 关键字
- 所有 JOIN 执行器无 NULL 填充逻辑
- 优化规则（FilterJoinPushdownRule、JoinCommuteRule）不区分 JOIN 类型

OUTER JOIN 的核心难点：
1. **NULL 填充语义**：保留侧无匹配时输出 NULL 行
2. **谓词下推限制**：ON 条件不能下推到保留侧，WHERE 条件对非保留侧的过滤会退化为 INNER JOIN
3. **FULL JOIN 双向追踪**：需要同时追踪左右两侧的未匹配行

## 依赖关系

```
Phase 1 (JoinType 枚举 + AST/Rel) ← 基础设施，所有后续依赖
Phase 2 (Parser)                   ← 依赖 Phase 1
Phase 3 (Validator + Converter)    ← 依赖 Phase 1
Phase 4 (Executor 改造)            ← 依赖 Phase 1，核心工作量
Phase 5 (优化规则适配)              ← 依赖 Phase 1，安全关键
Phase 6 (CostModel 适配)           ← 依赖 Phase 1
```

Phase 2/3 相互独立可并行；Phase 4/5/6 相互独立可并行。

---

## Phase 1: JoinType 枚举 + AST/Rel 改造

### 目标
为 SqlJoin 和 RelJoin 引入 JoinType 字段。

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/ast/SqlJoin.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/rel/RelJoin.java`

### 实现

#### 1.1 新建 JoinType 枚举

在 SqlJoin.java 中定义（或独立文件）：

```java
public enum JoinType {
    INNER, LEFT, RIGHT, FULL, CROSS
}
```

#### 1.2 SqlJoin 增加 JoinType 字段

```java
public record SqlJoin(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition) implements SqlNode {
    // 向后兼容：默认 INNER
    public SqlJoin(SqlNode left, SqlNode right, SqlNode condition) {
        this(JoinType.INNER, left, right, condition);
    }
    @Override
    public SqlKind kind() { return SqlKind.JOIN; }
}
```

#### 1.3 RelJoin 增加 JoinType 字段

```java
public class RelJoin extends RelNode {
    private final RelNode left;
    private final RelNode right;
    private final SqlNode condition;
    private final SqlJoin.JoinType joinType;

    // 新构造器
    public RelJoin(RelNode left, RelNode right, SqlNode condition, SqlJoin.JoinType joinType) { ... }

    // 向后兼容：默认 INNER
    public RelJoin(RelNode left, RelNode right, SqlNode condition) {
        this(left, right, condition, SqlJoin.JoinType.INNER);
    }

    // copy() 需要保留 joinType
    public RelNode copy(List<RelNode> inputs) {
        return new RelJoin(inputs.get(0), inputs.get(1), condition, joinType);
    }
}
```

#### 1.4 SqlNodeFactory.join() 适配

文件：`mini-db/src/main/java/cn/zhangyis/minidb/sql/ast/SqlNodeFactory.java`

现有方法保留不变（向后兼容），新增 JoinType 重载：
```java
// 现有（保留，内部改为委托新方法）
public SqlJoin join(SqlNode left, SqlNode right, SqlNode condition) {
    return new SqlJoin(left, right, condition); // 走 3-arg 构造器 → 默认 INNER
}

// 新增
public SqlJoin join(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition) {
    return new SqlJoin(joinType, left, right, condition);
}
```

Parser 中的 `factory.join(left, right, condition)` 调用改为 `factory.join(joinType, left, right, condition)`。

#### 1.5 RelFactories.join() 适配

```java
RelJoin join(RelNode left, RelNode right, SqlNode condition);
RelJoin join(RelNode left, RelNode right, SqlNode condition, SqlJoin.JoinType joinType);
```

#### 1.6 影响范围
所有使用 `new SqlJoin(left, right, cond)` 和 `new RelJoin(left, right, cond)` 的地方
保持向后兼容构造器，不需要修改现有调用方。

---

## Phase 2: Parser 识别 OUTER JOIN 语法

### 目标
支持 `LEFT [OUTER] JOIN`、`RIGHT [OUTER] JOIN`、`FULL [OUTER] JOIN`、`CROSS JOIN` 语法。

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/lexer/TokenType.java` — 新增 LEFT, RIGHT, FULL, OUTER, CROSS, INNER 关键字
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/lexer/SqlLexer.java` — 注册新关键字
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/parser/SqlParser.java` — parseFrom() 改造

### 实现

#### 2.1 TokenType 新增关键字

```java
LEFT, RIGHT, FULL, OUTER, CROSS, INNER
```

需要确认哪些已存在（INNER 可能已有）。

#### 2.2 SqlLexer 注册关键字

在关键字 map 中添加 `"LEFT"->LEFT`, `"RIGHT"->RIGHT`, `"FULL"->FULL`, `"OUTER"->OUTER`, `"CROSS"->CROSS`, `"INNER"->INNER`。

#### 2.3 parseFrom() 改造

当前代码（第 99-109 行）：
```java
while (tokens.current().type() == TokenType.JOIN) {
    tokens.next();
    ...
}
```

改为：
```java
while (isJoinStart(tokens.current().type())) {
    JoinType joinType = parseJoinType();
    SqlNode right = parseFromItem();
    SqlNode condition = null;
    if (joinType != JoinType.CROSS) {
        tokens.expect(TokenType.ON);
        condition = parseExpression();
    }
    left = new SqlJoin(joinType, left, right, condition);
}
```

```java
private boolean isJoinStart(TokenType type) {
    return type == TokenType.JOIN || type == TokenType.LEFT
        || type == TokenType.RIGHT || type == TokenType.FULL
        || type == TokenType.CROSS || type == TokenType.INNER;
}

private JoinType parseJoinType() {
    TokenType type = tokens.current().type();
    if (type == TokenType.LEFT) {
        tokens.next();
        tokens.match(TokenType.OUTER); // 可选 OUTER
        tokens.expect(TokenType.JOIN);
        return JoinType.LEFT;
    }
    if (type == TokenType.RIGHT) {
        tokens.next();
        tokens.match(TokenType.OUTER);
        tokens.expect(TokenType.JOIN);
        return JoinType.RIGHT;
    }
    if (type == TokenType.FULL) {
        tokens.next();
        tokens.match(TokenType.OUTER);
        tokens.expect(TokenType.JOIN);
        return JoinType.FULL;
    }
    if (type == TokenType.CROSS) {
        tokens.next();
        tokens.expect(TokenType.JOIN);
        return JoinType.CROSS;
    }
    if (type == TokenType.INNER) {
        tokens.next();
        tokens.expect(TokenType.JOIN);
        return JoinType.INNER;
    }
    // 纯 JOIN
    tokens.expect(TokenType.JOIN);
    return JoinType.INNER;
}
```

---

## Phase 3: Validator + SqlToRelConverter 适配

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/validation/SqlValidator.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/planner/SqlToRelConverter.java`

### 实现

#### 3.1 Validator: CROSS JOIN 允许无 ON 条件

当前验证 `join.condition() != null` 才验证条件。CROSS JOIN 的 condition 为 null，这已经兼容。
无需大改，但可增加校验：非 CROSS JOIN 必须有 ON 条件。

```java
if (from instanceof SqlJoin join) {
    validateFrom(join.left(), tables);
    validateFrom(join.right(), tables);
    if (join.joinType() != SqlJoin.JoinType.CROSS && join.condition() == null) {
        throw new ValidationException("Non-CROSS JOIN must have ON condition");
    }
    if (join.condition() != null) {
        validateCondition(join.condition(), tableScopes(tables));
    }
    return;
}
```

#### 3.2 SqlToRelConverter: 传递 JoinType

```java
if (from instanceof SqlJoin join) {
    RelNode left = convertFrom(join.left(), validated);
    RelNode right = convertFrom(join.right(), validated);
    return new RelJoin(left, right, join.condition(), join.joinType());
}
```

需要同步修改 RelFactories 接口和默认实现。

---

## Phase 4: Executor 改造（核心）

### 目标
为 NestedLoopJoinExec、HashJoinExec、SortMergeJoinExec 增加 OUTER JOIN 支持。

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/NestedLoopJoinExec.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/HashJoinExec.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/SortMergeJoinExec.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`

### NULL 行生成辅助方法

在 Row 类或工具类中新增：
```java
static Row nullRow(Row template) {
    Map<String, Object> nullMap = new LinkedHashMap<>();
    for (String key : template.columns().keySet()) {
        nullMap.put(key, null);
    }
    return new Row(nullMap);
}
```

对于 HashJoinExec/SortMergeJoinExec，右表在 build/物化阶段已知列名，可以在此时预生成 nullRow。

### 4.1 NestedLoopJoinExec 改造

新增字段：
```java
private final SqlJoin.JoinType joinType;
private boolean currentLeftMatched; // LEFT/FULL: 左行是否有匹配
private Set<Integer> matchedRightIndices; // RIGHT/FULL: 右行是否被匹配过
private int rightEmitIdx; // FULL/RIGHT: 最后阶段遍历右表 emit 未匹配行
private boolean rightEmitPhase; // 是否进入右表未匹配行发射阶段
private Row nullLeftRow; // 缓存左侧 NULL 行模板
private Row nullRightRow; // 缓存右侧 NULL 行模板
```

next() 逻辑改造：
```
1. 主循环（与 INNER 相同）：
   for each leftRow:
     for each rightRow:
       if matches:
         currentLeftMatched = true
         记录 rightIdx 到 matchedRightIndices（RIGHT/FULL 时）
         return merge(leftRow, rightRow)
     // 内层循环结束，检查 LEFT/FULL：
     if (LEFT || FULL) && !currentLeftMatched:
       return merge(leftRow, nullRightRow)
     重置 currentLeftMatched

2. 主循环结束后，RIGHT/FULL 需要发射右表未匹配行：
   for each rightRow not in matchedRightIndices:
     return merge(nullLeftRow, rightRow)
```

#### 保留现有构造器兼容（joinType 默认 INNER）。

### 4.2 HashJoinExec 改造

新增字段：
```java
private final SqlJoin.JoinType joinType;
private Set<List<Object>> matchedBuildKeys; // RIGHT/FULL: 按 key 追踪（probe 阶段匹配某 key 时会输出该 key 全部 build 行，因此按 key 粒度追踪即可）
private List<Row> buildRows; // RIGHT/FULL 需要保留原始行列表，用于最终遍历未匹配行
private Iterator<Row> unmatchedBuildIter; // RIGHT/FULL 最后阶段
private Row nullProbeRow; // 左侧 NULL 行
private Row nullBuildRow; // 右侧 NULL 行
```

Probe 阶段：
```
probe 每行左表：
  计算 probeKey
  if probeKey == null || 无匹配:
    if LEFT || FULL:
      return merge(leftRow, nullBuildRow)  // NULL 填充
    else:
      continue
  else:
    标记 buildKey 为已匹配
    return merge(leftRow, rightRow)

Probe 结束后（RIGHT/FULL）：
  遍历 build side 中未匹配的行：
    return merge(nullProbeRow, rightRow)
```

### 4.3 SortMergeJoinExec 改造

新增字段：
```java
private final SqlJoin.JoinType joinType;
private boolean currentLeftMatched;  // LEFT/FULL: 当前左行是否有匹配
private boolean currentRightMatched; // RIGHT/FULL: 当前右行是否有匹配
private Row nullLeftRow;
private Row nullRightRow;
private Row pendingEmit; // 缓存待发射的 NULL 填充行
```

双指针归并逻辑改造（pull 模型状态机）：
```
next() 每次调用：
  0. 如果 pendingEmit != null → 返回并清空

  1. 比较 leftKey vs rightKey：

     case leftKey < rightKey:
       → 左行无匹配（右侧没有等值行）
       if (LEFT || FULL): emit left + nullRight
       左指针前进，重置 currentLeftMatched

     case rightKey < leftKey:
       → 右行无匹配（左侧没有等值行）
       if (RIGHT || FULL): emit nullLeft + right
       右指针前进，重置 currentRightMatched

     case leftKey == rightKey:
       → 正常匹配，标记双方已匹配
       currentLeftMatched = true
       currentRightMatched = true
       return merge(leftRow, rightRow)
       （多对多匹配：复用现有 rightMatchStart 回扫逻辑）

  2. 左行切换时（key 变化）：
     if (LEFT || FULL) && !currentLeftMatched:
       pendingEmit = merge(oldLeftRow, nullRight)
     重置 currentLeftMatched

  3. 右行切换时（key 变化）：
     if (RIGHT || FULL) && !currentRightMatched:
       pendingEmit = merge(nullLeft, oldRightRow)
     重置 currentRightMatched

  4. 归并结束后，剩余行处理：
     左侧剩余行（LEFT/FULL）→ 逐行 emit left + nullRight
     右侧剩余行（RIGHT/FULL）→ 逐行 emit nullLeft + right
```

### 4.4 PhysicalPlanner.planJoin() 适配

```java
private ExecNode planJoin(RelJoin join, JoinAlgorithm overrideAlgo) {
    ...
    SqlJoin.JoinType joinType = join.joinType();

    return switch (algo) {
        case NESTED_LOOP -> new NestedLoopJoinExec(left, right, join.condition(), joinType);
        case HASH_JOIN -> {
            if (keys != null) {
                ...
                ExecNode joinExec = new HashJoinExec(left, right, keys.leftKeys(), keys.rightKeys(), joinType);
                yield wrapResidual(joinExec, keys.residual());
            }
            yield new NestedLoopJoinExec(left, right, join.condition(), joinType);
        }
        case SORT_MERGE -> { ... 同理 ... }
        case INDEX_NESTED_LOOP -> {
            // IndexNL 仅支持 INNER JOIN（OUTER 需要追踪未匹配行，IndexNL 无法做到）
            if (joinType != SqlJoin.JoinType.INNER) {
                // fallback: 有 equi-key → HASH_JOIN，无 equi-key → NESTED_LOOP
                if (keys != null) {
                    ExecNode joinExec = new HashJoinExec(left, right, keys.leftKeys(), keys.rightKeys(), joinType);
                    yield wrapResidual(joinExec, keys.residual());
                }
                yield new NestedLoopJoinExec(left, right, join.condition(), joinType);
            }
            ...
        }
    };
}
```

---

## Phase 5: 优化规则适配（安全关键）

### 目标
防止 OUTER JOIN 的谓词下推导致语义错误。

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/rule/FilterJoinPushdownRule.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/rule/JoinCommuteRule.java`

### 5.1 FilterJoinPushdownRule 限制

OUTER JOIN 的谓词下推规则：

| JOIN 类型 | ON 中的条件 | WHERE 下推到保留侧 | WHERE 下推到非保留侧 |
|-----------|-----------|-------------------|-------------------|
| LEFT      | 不下推到左表（保留侧） | 允许 | 禁止（会退化为 INNER）|
| RIGHT     | 不下推到右表（保留侧） | 允许 | 禁止（会退化为 INNER）|
| FULL      | 不下推 | 禁止 | 禁止 |
| INNER     | 自由下推 | 自由下推 | 自由下推 |

> **语义说明**：保留侧 = 保证输出全部行的一侧（LEFT JOIN 的左表、RIGHT JOIN 的右表）。
> 非保留侧 = 可能被 NULL 填充的一侧。将 WHERE 条件下推到非保留侧会导致 NULL 填充行无法产生，
> 等价于退化为 INNER JOIN。

实现：
```java
// matches() 中不排除 OUTER JOIN，仍然尝试优化
// apply() 中根据 joinType 限制下推方向

if (joinType == JoinType.LEFT) {
    // 只能下推到左侧（保留侧的 WHERE）
    // 不能把 ON 条件下推到左侧
    rightConditions.clear(); // 禁止下推到右侧
} else if (joinType == JoinType.RIGHT) {
    leftConditions.clear(); // 禁止下推到左侧
} else if (joinType == JoinType.FULL) {
    leftConditions.clear();
    rightConditions.clear(); // 两侧都不下推
}
```

**注意**：更精确的实现可以检测 `WHERE right_col IS NOT NULL` 这种条件，自动做 Outer-to-Inner 转换。但初版不做此优化，保守处理。

### 5.2 JoinCommuteRule 适配

```java
// LEFT JOIN 交换后变成 RIGHT JOIN，反之亦然
JoinType newType = switch (joinType) {
    case LEFT -> JoinType.RIGHT;
    case RIGHT -> JoinType.LEFT;
    case INNER -> JoinType.INNER;
    case FULL -> JoinType.FULL; // 对称，不变
    case CROSS -> JoinType.CROSS;
};

// matches() 中：FULL JOIN 不做交换（已对称）
// CROSS JOIN 不做交换（无条件）
if (joinType == JoinType.FULL || joinType == JoinType.CROSS) return false;
```

---

## Phase 6: CostModel 适配

### 修改文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/cost/CostModel.java`

### 实现

#### 6.1 基数估计

```java
if (node instanceof RelJoin j) {
    double innerRows = estimateRows(j.left()) * estimateRows(j.right()) * equiSelectivity;
    return switch (j.joinType()) {
        case INNER -> innerRows;
        case LEFT -> Math.max(estimateRows(j.left()), innerRows);
        case RIGHT -> Math.max(estimateRows(j.right()), innerRows);
        case FULL -> Math.max(estimateRows(j.left()) + estimateRows(j.right()), innerRows);
        case CROSS -> estimateRows(j.left()) * estimateRows(j.right());
    };
}
```

#### 6.2 JOIN 算法选择

- OUTER JOIN 时禁用 INDEX_NESTED_LOOP（无法追踪未匹配行）
- CROSS JOIN 强制 NESTED_LOOP（无 equi-key）

```java
if (joinType != JoinType.INNER) {
    indexLookupCost = Double.MAX_VALUE; // 禁用 IndexNL
}
if (joinType == JoinType.CROSS) {
    return JoinAlgorithm.NESTED_LOOP;
}
```

---

## 验证

### 回归测试
```bash
ExistsInSubqueryTest     # 子查询不受影响
DerivedTableTest         # 派生表不受影响
JoinCommuteRuleTest      # JOIN 交换规则
```

### 新增测试场景

1. **LEFT JOIN 基本语义**：左表全保留，右表无匹配时填 NULL
2. **RIGHT JOIN 基本语义**：右表全保留
3. **FULL JOIN 基本语义**：两侧全保留
4. **CROSS JOIN**：笛卡尔积
5. **LEFT JOIN + WHERE 过滤**：验证 WHERE 在 NULL 填充后执行
6. **LEFT JOIN + 多 equi-key**：验证 Phase 3 的多 key 与 OUTER JOIN 兼容
7. **JoinCommuteRule + LEFT**：验证交换后变 RIGHT，语义不变
8. **FilterJoinPushdownRule + LEFT**：验证不会错误下推到右侧

### 验证命令
```bash
# 全量测试
mvn test -pl mini-db  (或 gradle :mini-db:test)
```
