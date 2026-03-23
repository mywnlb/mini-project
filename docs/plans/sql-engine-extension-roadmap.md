# SQL 引擎 7 个扩展方向详细实现计划

## Context

`sql-engine` skill 中列出了 7 个后续可扩展方向。经过深度代码审查发现：**其中 3 个已经实现**（窗口函数、并行聚合、EXPLAIN），剩余 4 个需要新建或修复。本计划给出每个方向的精确现状和详细实施方案。

---

## 状态总览

| # | 方向 | 状态 | 优先级 | 工作量 |
|---|------|------|--------|--------|
| 1 | 两阶段聚合 (PartialAgg + FinalAgg) | **未实现** (RBO层) | P2 | 中 |
| 2 | Sort 下推 / MergeSort | **有严重bug** + 缺MergeSort | P1 | 中 |
| 3 | 并行 Hash Aggregate | **已完成** (`ParallelAggregateExec`) | P4增强 | 小 |
| 4 | 窗口函数执行器 | **已完成** (`WindowExec` + 5种函数) | P4增强 | 小 |
| 5 | 预编译语句 (Prepared Statement) | **完全未实现** | P1 | 大 |
| 6 | 更多标量函数 | 仅3个(UPPER/LOWER/COALESCE) | P1 | 中 |
| 7 | EXPLAIN 命令 | **已完成** (`ExplainExec`) | P3增强 | 中 |

## 建议实施顺序

```
第一批 (无依赖，可并行):  方向6 → 方向2
第二批 (中等复杂):        方向1 → 方向7增强
第三批 (大工程):          方向5
可选增强:                方向3/4增强
```

### 依赖关系

```
方向6 (标量函数) ──── 无依赖，可独立实施
方向2 (Sort修复)  ──── 无依赖，可独立实施
方向1 (两阶段聚合) ── 无依赖，可独立实施
方向7 (EXPLAIN增强) ─ 依赖方向1、2完成后效果更好 (能看到新算子)
方向5 (PreparedStmt) ─ 依赖方向6 (函数扩展后 Parser 改动需协调)
方向3 (并行Agg增强) ─ 可选依赖方向1 (两阶段聚合可作为并行化的基础)
方向4 (窗口增强)   ── 无依赖，可独立实施
```

---

## 方向 1: 两阶段聚合 (PartialAgg + FinalAgg)

### 现状
- `AggregateExec`: 单阶段全物化 hash 聚合，`open()` 中全量读取 → 分组 → 逐组计算
- `ParallelAggregateExec`: 执行层并行化（ConcurrentHashMap + CountDownLatch），非逻辑计划级拆分
- **缺失**: RBO 规则级 `RelAggregate → RelFinalAgg(RelPartialAgg(input))` 的拆分

### 核心难点
AVG 拆分: `AVG(x)` → Partial: `SUM(x)` + `COUNT(x)`, Final: `SUM(partial_sum)/SUM(partial_count)`

### 聚合函数拆分映射

| 原始函数 | Partial 阶段 | Final 阶段 |
|---------|-------------|-----------|
| `COUNT(x)` | `COUNT(x) as _partial_count_N` | `SUM(_partial_count_N)` |
| `SUM(x)` | `SUM(x) as _partial_sum_N` | `SUM(_partial_sum_N)` |
| `AVG(x)` | `SUM(x) as _partial_sum_N` + `COUNT(x) as _partial_count_N` | `SUM(_partial_sum_N) / SUM(_partial_count_N)` |
| `MAX(x)` | `MAX(x) as _partial_max_N` | `MAX(_partial_max_N)` |
| `MIN(x)` | `MIN(x) as _partial_min_N` | `MIN(_partial_min_N)` |

### 实施步骤

**Phase 1: 新建 Rel 节点**
- 新文件: `rel/RelPartialAggregate.java` — 部分聚合逻辑节点
- 新文件: `rel/RelFinalAggregate.java` — 最终聚合逻辑节点
- 新文件: `ast/PartialAggCall.java` — 描述 partial 阶段的函数调用

**Phase 2: 新建 RBO 规则**
- 新文件: `optimize/AggregateSplitRule.java`
  - `matches`: node instanceof RelAggregate && input not already a partial agg
  - `apply`: 遍历 aggCalls → 生成 PartialAggCall → 构建 RelPartialAggregate + RelFinalAggregate
- 修改: `RuleOptimizer.java` — 注册规则（默认可选，构造参数控制是否启用）

**Phase 3: 新建执行器**
- 新文件: `exec/PartialAggregateExec.java` — 输出中间结果列 (`_partial_sum_0`, `_partial_count_0`)
- 新文件: `exec/FinalAggregateExec.java` — 合并同 group key 的 partial states
- 修改: `PhysicalPlanner.java` — 添加 RelPartialAggregate/RelFinalAggregate 的 plan 分支
- 修改: `ExplainExec.java` — 添加新节点的 nodeName/details

**Phase 4: 测试**
- 新文件: `AggregateSplitRuleTest.java`
- 用例:
  1. COUNT/SUM 两阶段拆分后结果等价
  2. AVG 拆分为 SUM+COUNT 后结果等价（特别注意浮点精度）
  3. GROUP BY 多列 + 多个 agg 的场景
  4. 无 GROUP BY（全局聚合）的场景
  5. HAVING 子句与两阶段聚合的配合

### 关键设计决策
- **默认关闭**: 作为可选规则，通过构造参数控制是否注入，用于分布式或 pushdown 场景时开启
- **与 ParallelAggregateExec 正交**: 两者可独立工作或组合使用

### 关键文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/rel/RelAggregate.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/AggregateExec.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/RuleOptimizer.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`

---

## 方向 2: Sort 下推 / MergeSort

### 现状
- `SortExec`: MemSort + TopN，功能正常
  - TopN: `PriorityQueue` 维护 heapSize = limit+offset，时间 O(N·logK)，空间 O(K)
  - MemSort: 全量物化 + `List.sort()`，时间 O(N·logN)，空间 O(N)
- `ParallelSortExec`: **严重bug**
  - `compareRow()` (第92-96行) 仅取第一列 hashCode 作为比较值
  - 不接受 ORDER BY 表达式
  - 全量合并 `merged.addAll(pq)` 非流式
- `LimitPushdownRule`: 仅支持无 ORDER BY 的 LIMIT 下推
- 无 MergeSort 执行器

### 实施步骤

**Phase 1: 修复 ParallelSortExec（紧急）**
- 修改: `exec/ParallelSortExec.java` — 全面重写
  - 构造函数接收 `SqlNodeList orderBy`
  - 复用 `SortExec.buildComparator()` 的逻辑构建 `Comparator<Row>`
  - 修复分区策略: 先物化所有行到 `List<Row>` → 按 hash 分配到各 partition → 各 partition 独立排序
  - 修复归并: 使用 `PriorityQueue<IndexedRow>` 做流式 K 路归并
- 修改: `PhysicalPlanner.java` — 构造调用补上 `sort.orderBy()` 参数

**Phase 2: 实现 MergeSortExec**
- 新文件: `exec/MergeSortExec.java` — K 路归并执行器
  - 接收 `List<ExecNode> sortedInputs` + `Comparator<Row>`
  - 使用 `PriorityQueue<IndexedRow>` 做流式归并
  - `IndexedRow` 包含 (inputIndex, currentRow)，每弹出一行从对应 input 补充
  - 流式输出，空间 O(K) 而非 O(N)

**Phase 3: Sort 下推规则**
- 新文件: `optimize/SortPushdownRule.java`
  - 将 ORDER BY 穿过 RelProject（如果投影不改变排序列的语义）
  - 对 RelUnion: 将 Sort 下推为各分支的 LocalSort + 上层 MergeSort
- 修改: `RuleOptimizer.java` — 注册
- 可选扩展: `DataSourceSpi` 添加 `Iterator<Row> scanOrdered(String tableName, List<OrderSpec> orderBy)` 接口

**Phase 4: 测试**
- 新文件: `ParallelSortFixTest.java` — 验证修复后 ParallelSortExec 正确性
- 新文件: `MergeSortTest.java` — K 路归并正确性
- 用例:
  1. 多列 ASC/DESC 混合排序
  2. K 路归并（2/4/8 路）输出有序性
  3. NULL 值在排序中的处理
  4. Sort 下推规则验证（计划树变换断言）
  5. ORDER BY + LIMIT 组合场景

### 关键文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/ParallelSortExec.java` (紧急修复)
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/SortExec.java` (参考 buildComparator)
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/optimize/LimitPushdownRule.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/PhysicalPlanner.java`

---

## 方向 3: 并行 Hash Aggregate（已完成，可选增强）

### 现状: `ParallelAggregateExec` 已实现
- ConcurrentHashMap 做 partial state 聚合
- CountDownLatch 同步 Partial/Final 阶段
- BlockingQueue(1024) 传递结果
- AggregateState: `long[] counts` + `double[] sums` + `Comparable[] mins/maxs`
- 触发条件: `executionContext.parallelism() > 1`

### 可选增强（P4）
1. **线程安全修复**: 多线程共享 `input.next()` 不安全（ExecNode 接口非线程安全），改为先物化所有行再按 hash(groupKey) 分区到独立 partition
2. **背压控制**: resultQueue 大小从固定 1024 改为动态调整
3. **溢出保护**: 当分组数超阈值时，溢出到磁盘（外排聚合）

---

## 方向 4: 窗口函数执行器（已完成，可选增强）

### 现状: `WindowExec` 已实现
- 排名函数: ROW_NUMBER, RANK, DENSE_RANK
- 聚合窗口: Running SUM/COUNT/AVG/MIN/MAX
- PARTITION BY + ORDER BY 支持
- Parser 完整支持 `func() OVER (PARTITION BY ... ORDER BY ...)`
- 测试: `MediumPriorityFeaturesTest.java`

### 可选增强（P4）
1. **ROWS/RANGE 窗口帧**
   - 语法: `ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING`
   - 修改 Parser 识别 ROWS/RANGE/BETWEEN/PRECEDING/FOLLOWING/UNBOUNDED/CURRENT ROW
   - 修改 WindowExec 按帧范围计算而非整个分区
2. **LAG/LEAD 函数**
   - `LAG(col, offset, default)` / `LEAD(col, offset, default)`
   - Parser: `isWindowFunction()` 添加 LAG/LEAD
   - WindowExec: 添加新 case 分支
3. **NTILE/PERCENT_RANK/CUME_DIST**

---

## 方向 5: 预编译语句 (Prepared Statement)

### 现状: 完全未实现
- 无 `?` token 类型
- 无参数绑定机制
- 无计划缓存
- 所有 SQL 必须嵌入字面值

### 实施步骤

**Phase 1: Lexer — 识别 `?` 占位符**
- 修改: `TokenType.java` — 添加 `PARAMETER` 枚举值
- 修改: `SqlLexer.java` — `nextToken()` 的 switch 中添加 `case '?'` 分支
  ```java
  case '?': pos++; return new Token(TokenType.PARAMETER, "?", start, pos);
  ```

**Phase 2: AST — 参数节点**
- 新文件: `ast/SqlParameter.java`
  ```java
  public record SqlParameter(int index) implements SqlNode {
      @Override public SqlKind kind() { return SqlKind.PARAMETER; }
  }
  ```
- 修改: `SqlKind.java` — 添加 `PARAMETER` 枚举值

**Phase 3: Parser — 解析参数**
- 修改: `SqlParser.java`
  - `parsePrimary()` 中处理 `TokenType.PARAMETER`
  - Parser 维护 `int paramIndex` 计数器，每遇到 `?` 递增

**Phase 4: 参数绑定器**
- 新文件: `exec/ParameterBinder.java`
  - 递归遍历 AST 树，将 `SqlParameter(idx)` 替换为 `SqlLiteral(params.get(idx))`
  - 需处理: SqlSelect/SqlInsert/SqlUpdate/SqlDelete/SqlBinaryOp/SqlBetween/SqlInList 等所有含子表达式的节点

**Phase 5: 计划缓存**
- 新文件: `exec/PlanCache.java`
  - LRU 缓存: SQL 文本模板 → `CachedPlan(parsedAst, paramCount)`
  - 先只缓存 AST（不缓存逻辑计划，避免统计信息变化导致的计划失效）
  - 可配置 maxSize

**Phase 6: PreparedStatement API**
- 新文件: `exec/PreparedStatement.java`
  - `setInt(int index, int value)` / `setString(int index, String value)` / `setObject(int index, Object value)`
  - `execute()`: bind → validate → convert → optimize → plan → exec
  - `reset()`: 清空参数，复用缓存的 AST

**Phase 7: 测试**
- 新文件: `PreparedStatementTest.java`
- 用例:
  1. `SELECT * FROM users WHERE id = ?` 参数绑定
  2. `INSERT INTO t VALUES (?, ?, ?)` 多参数
  3. 同一 prepared statement 不同参数多次执行
  4. 参数类型不匹配错误检测
  5. 参数数量不匹配错误检测
  6. PlanCache 命中/淘汰测试
  7. NULL 参数绑定

### 关键设计决策
- **按位置索引 (`?`)**: 与 JDBC 标准一致，不支持命名参数 (`:name`)
- **缓存粒度**: 先只缓存 AST，避免统计信息失效问题
- **绑定后跳过 Validator**: prepare 时已验证结构，参数绑定后无需重复验证

### 关键文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/lexer/SqlLexer.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/lexer/TokenType.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/parser/SqlParser.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/ast/SqlKind.java`

---

## 方向 6: 更多标量函数

### 现状
- 仅 UPPER, LOWER, COALESCE 三个标量函数
- `FunctionRegistry`: 静态 HashMap 注册 + `ScalarFunction` 接口
- **Parser 硬编码**: `SqlParser.java:888` 行 `funcName.equals("UPPER") || funcName.equals("LOWER") || funcName.equals("COALESCE")`
- `FilterExec.resolveValue()` 和 `ProjectExec` 调用 `FunctionRegistry.get()` 执行函数

### 实施步骤

**Phase 1: 修复 Parser 函数识别机制（前置条件）**
- 修改: `FunctionRegistry.java` — 添加 `public static boolean contains(String name)` 方法
- 修改: `SqlParser.java` — 将第 888 行硬编码改为动态判断
  ```java
  // 原: if (funcName.equals("UPPER") || funcName.equals("LOWER") || funcName.equals("COALESCE"))
  // 改: if (FunctionRegistry.contains(funcName))
  ```

**Phase 2: 字符串函数**（每个函数一个类，遵循现有模式）

| 函数 | 文件 | 语义 | 注意事项 |
|------|------|------|---------|
| `CONCAT(a,b,...)` | `ConcatFunction.java` | 字符串拼接 | 含 NULL 返回 NULL (SQL 标准) |
| `SUBSTRING(str, start, len)` | `SubstringFunction.java` | 子串提取 | SQL 下标从 1 开始 |
| `TRIM(str)` | `TrimFunction.java` | 去首尾空白 | |
| `LENGTH(str)` | `LengthFunction.java` | 字符串长度 | |
| `REPLACE(str, from, to)` | `ReplaceFunction.java` | 子串替换 | |

**Phase 3: 数学函数**

| 函数 | 文件 | 语义 |
|------|------|------|
| `ABS(x)` | `AbsFunction.java` | 绝对值 |
| `CEIL(x)` | `CeilFunction.java` | 向上取整 |
| `FLOOR(x)` | `FloorFunction.java` | 向下取整 |
| `ROUND(x, decimals)` | `RoundFunction.java` | 四舍五入 |
| `MOD(x, y)` | `ModFunction.java` | 取模 |

**Phase 4: 日期函数**

| 函数 | 文件 | 语义 |
|------|------|------|
| `NOW()` | `NowFunction.java` | 当前时间（无参） |
| `DATE_FORMAT(date, fmt)` | `DateFormatFunction.java` | 日期格式化 |
| `DATEDIFF(date1, date2)` | `DateDiffFunction.java` | 日期差（天数） |

**Phase 5: 注册所有新函数**
- 修改: `FunctionRegistry.java` — static block 中注册全部 13 个新函数

**Phase 6: 测试**
- 新文件: `ScalarFunctionTest.java`
- 用例:
  1. 每个函数基本功能验证
  2. NULL 参数处理（各函数）
  3. 函数嵌套: `UPPER(CONCAT('a', 'b'))` = `"AB"`
  4. SELECT 投影中使用函数
  5. WHERE 条件中使用函数
  6. 类型边界: `ROUND(3.456, 2)` = `3.46`, `MOD(10, 3)` = `1`

### 注意事项
- **LEFT/RIGHT 关键字冲突**: LEFT/RIGHT 是 JOIN 关键字，不实现同名函数，用 SUBSTRING 替代
- **NOW() 无参**: Parser 已支持空参数列表（`RPAREN` 检查在 args 解析之前）
- **SUBSTRING 关键字冲突风险**: SUBSTRING 可能在某些 SQL 方言中是保留字，需确认 `TokenType` 中是否已定义

### 关键文件
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/parser/SqlParser.java` (第 884-900 行硬编码修复)
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/functions/FunctionRegistry.java`
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/functions/` (现有: UpperFunction, LowerFunction, CoalesceFunction)
- `mini-db/src/main/java/cn/zhangyis/minidb/sql/exec/FilterExec.java` (resolveValue 中的函数调用逻辑)

---

## 方向 7: EXPLAIN 命令（已完成，可选增强 → EXPLAIN ANALYZE）

### 现状
- `SqlExplain` AST 节点: `record SqlExplain(SqlNode query)`
- `ExplainExec`: 输出 ID / OPERATOR / EST_ROWS / DETAILS 四列
- `CostModel.estimateRows()` 做行数估计
- 测试: `ExplainFeatureTest.java`

### 增强: EXPLAIN ANALYZE

**Phase 1: 语法扩展**
- 修改: `SqlExplain.java` — 添加 `boolean analyze` 字段
- 修改: `SqlParser.java` — `parseExplain()` 在 EXPLAIN 之后检查 ANALYZE 关键字

**Phase 2: 运行时统计收集**
- 新文件: `exec/InstrumentedExecNode.java` — Decorator 模式包装 ExecNode
  ```java
  public class InstrumentedExecNode implements ExecNode {
      private final ExecNode delegate;
      private long rowCount;
      private long openTimeNanos;
      private long totalNextTimeNanos;
      // open/next/close 中收集时间和行数
  }
  ```

**Phase 3: ExplainAnalyzeExec**
- 新文件: `exec/ExplainAnalyzeExec.java`
  - 用 InstrumentedExecNode 递归包装整棵执行树
  - 执行查询到完成（drain all rows）
  - 收集每个算子的实际行数和时间
  - 输出: ID, OPERATOR, EST_ROWS, **ACT_ROWS**, **TIME_MS**

**Phase 4: 集成**
- 修改: `SqlSession.java` — `executeExplain()` 根据 `analyze` 标志选择 ExplainExec 或 ExplainAnalyzeExec

**Phase 5: 测试**
- 新文件: `ExplainAnalyzeTest.java`
- 用例:
  1. `EXPLAIN ANALYZE SELECT ...` 输出包含 ACT_ROWS 列
  2. ACT_ROWS 与实际 SELECT 行数一致
  3. TIME_MS > 0
  4. 多算子查询（JOIN + Filter + Sort）各算子统计独立

### 可选扩展: EXPLAIN FORMAT
- `EXPLAIN FORMAT=JSON SELECT ...` — JSON 格式输出
- `EXPLAIN FORMAT=TREE SELECT ...` — 树形格式输出
- 修改: `SqlExplain` 添加 format 字段，`ExplainExec` 根据 format 切换输出格式

---

## 验证方式

每个方向完成后:
1. 运行对应的单元测试
2. 通过 `TestMain.java` 或 `SqlSession` 端到端验证 SQL 语句
3. `mvn test -pl mini-db` 确保无回归
