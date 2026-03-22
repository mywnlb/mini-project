# mini-db 四大特性实现计划

## Context

当前 mini-db SQL 引擎已有基础的查询优化和执行管线，但以下四个特性尚不完整：
- **#10** JOIN 重排序只有贪心算法，无法搜索 bushy tree
- **#11** 统计信息框架已有骨架（ColumnStatistics/TableStatistics/AnalyzeTableExec），但 CostModel 仍大量使用硬编码选择率
- **#12** CTE 基本功能已实现，但缺少列名列表语法 `WITH cte(c1,c2) AS (...)`
- **#13** 并行执行仅有 ParallelScanExec，无并行 JOIN/聚合/排序

---

## Feature #10: 多 JOIN 重排序（DPsub 枚举）

### Fail-First Gate

#### 禁止设计
1. **全排列穷举** — N! 复杂度，N=10 时 360 万种排列，不可行；且只产出左深树，无法产出 bushy tree
2. **RuleOptimizer 循环中重复触发** — DP 已找到全局最优，再次 flatten+DP 会破坏最优子结构，导致死循环

#### 核心不变式
1. **语义等价**：DP 输出的 JOIN 树对任意数据集结果与原始树一致（所有 edge condition 不遗漏不重复）
2. **最优性**：`memo[全集].cost` <= 搜索空间内任何其他合法划分的代价
3. **连通性**：只有存在 JoinEdge 的子集对才能合并（无 edge 连接时退化为笛卡尔积兜底）
4. **幂等**：apply() 输出再经 matches() 应返回 false 或 apply 返回相同节点

### 实现步骤

**Step 1: 新建 `DPJoinEnumerator.java`**
- 路径：`src/main/java/.../sql/optimize/DPJoinEnumerator.java`
- 数据结构：`Map<BitSet, MemoEntry>` 其中 `MemoEntry(RelNode plan, double cost, double rows)`
- DPsub 算法：
  1. 初始化单表子集 `{i}` → `(relations[i], 0, estimateRows(relations[i]))`
  2. 按子集大小 2→N 枚举所有子集 S
  3. 枚举 S 的非空真子集 S1（S2 = S\S1），只枚举 `|S1| <= |S|/2` 的子集避免重复（当 |S1|==|S2| 时再用 BitSet 字典序去重）
  4. 连通性检查：S1 与 S2 之间存在至少一条 JoinEdge
  5. `joinCost = memo[S1].rows * memo[S2].rows * selectivity(equiKeys)`
  6. 若 `cost(S1) + cost(S2) + joinCost < bestCost(S)` 则更新 memo
  7. 返回 `memo[全集].plan`

**Step 2: 扩展 `JoinGraph.java`**
- 新增 `List<JoinEdge> edgesBetween(BitSet s1, BitSet s2)` — 返回连接两子集的所有 edge
- 新增 `boolean hasConnection(BitSet s1, BitSet s2)` — 连通性快速判断

**Step 3: 修改 `JoinReorderRule.java`**
- `apply()` 中：N <= 10 时调用 `DPJoinEnumerator.enumerate(graph, costModel)`，N > 10 保留贪心
- 幂等保护：`apply()` 比较 DP 输出代价与输入代价，若相同则 `return node`（原节点）。RuleOptimizer 的 `next != current` 判断会阻止 changed 置 true，终止循环

**Step 4: `CostModel.java`**
- 新增 `public double estimateJoinRows(double leftRows, double rightRows, int equiKeys)` 供 DP 使用

### 关键文件
| 文件 | 改动 |
|------|------|
| `sql/optimize/DPJoinEnumerator.java` | 新建 |
| `sql/optimize/JoinGraph.java` | +2 方法 |
| `sql/optimize/JoinReorderRule.java` | apply() 切换为 DP |
| `sql/optimize/cost/CostModel.java` | +1 公有方法 |

### 测试 (≥3)
| # | 场景 | 验证不变式 |
|---|------|-----------|
| 1 | 3 表 INNER JOIN，DP 结果行数 == 贪心结果行数 | 语义等价 |
| 2 | 4 表 star-schema，DP 计划代价 <= 贪心计划代价 | 最优性 |
| 3 | A-B, B-C 有 edge 但 A-C 无 edge，验证 A 与 C 不直接 join | 连通性 |
| 4 | 同一 SQL 连续 optimize 两次，第二次 JoinReorderRule 不触发 | 幂等 |
| 5 | 4 表全连接，explain 中出现 bushy 形状 `(A⋈B)⋈(C⋈D)` | 最优性+bushy |

---

## Feature #11: 统计信息收集

### Fail-First Gate

#### 禁止设计
1. **CostModel 中实时 scan() 采集统计** — 优化器单次查询可调用 estimateRows 上百次（特别是 DP 枚举），每次全表扫描会使优化时间从 ms 变为 s 级
2. **Histogram 可变桶列表** — StatisticsStore 使用 ConcurrentHashMap，若桶列表可变则多线程下有数据竞争。Histogram 必须不可变

#### 核心不变式
1. **快照一致性**：TableStatistics 不可变，同次 ANALYZE 的所有列统计基于同一快照（相同 rowCount）
2. **回退安全**：statsStore 为 null 或无统计时回退到硬编码默认值，绝不抛异常
3. **NDV 上界**：`ndv <= totalRows`，违反则采集逻辑有 bug
4. **Histogram 单调性**：桶边界严格递增，selectivity 结果 ∈ [0.0, 1.0]

### 实现步骤

**Step 1: 新建 `Histogram.java`**
- 路径：`src/main/java/.../sql/catalog/Histogram.java`
- `record Histogram(List<Bucket> buckets)`
- `record Bucket(Comparable<?> lowerBound, Comparable<?> upperBound, long rowCount, long ndv)`
- 方法：`selectivityRange(value, isLessThan)`, `selectivityEq(value)`

**Step 2: 扩展 `ColumnStatistics.java`**
- 新增第 7 个字段 `Histogram histogram`（可为 null 表示无直方图）
- `selectivityEq()` / `selectivityRange()` 有 Histogram 时优先使用

**Step 3: 修改 `AnalyzeTableExec.java`**
- open() 中全表扫描后，对数值列构建等高直方图
- 默认桶数：`min(256, ndv)`
- 对值排序后按行数均分桶
- 传入 `ColumnStatistics` 构造器

**Step 4: 增强 `CostModel.java`**
- `estimateRows(RelScan)` 优先从 statsStore 获取真实 rowCount
- `resolveColumnStats()` 支持 bare column name 查找（当前只支持 qualified `table.column`）
- `selectivity()` 有 Histogram 时使用精确估计

### 关键文件
| 文件 | 改动 |
|------|------|
| `sql/catalog/Histogram.java` | 新建 |
| `sql/catalog/ColumnStatistics.java` | +histogram 字段 |
| `sql/exec/AnalyzeTableExec.java` | +直方图构建 |
| `sql/optimize/cost/CostModel.java` | 增强统计使用 |

### 测试 (≥3)
| # | 场景 | 验证不变式 |
|---|------|-----------|
| 1 | ANALYZE 后 selectivityEq 返回 1/NDV 而非 0.1 | 快照一致性 |
| 2 | 不执行 ANALYZE，CostModel 正常工作不抛异常 | 回退安全 |
| 3 | ANALYZE 后检查 ndv <= rowCount | NDV 上界 |
| 4 | 数值列 Histogram selectivityRange 结果 ∈ [0,1] | Histogram 单调性 |
| 5 | 两次 ANALYZE 同一表，统计信息被覆盖（时间戳更新） | 快照一致性 |

---

## Feature #12: CTE（WITH 子句）补全

### Fail-First Gate

#### 禁止设计
1. **CTE 始终 inline 不物化** — 同一 CTE 被引用 N 次则执行 N 遍，含复杂 JOIN/聚合时性能灾难
2. **CTE 注册到 CatalogSpi 全局表** — 并发会话命名冲突、异常时孤表泄漏、污染全局 catalog

#### 核心不变式
1. **作用域隔离**：CTE 仅在其 WITH 语句内可见，语句结束后 cteCache/cteSchemaMap 清空
2. **引用顺序**：CTE 只能引用前面定义的 CTE，不能自引用（递归检测已实现）
3. **列名一致性**：`WITH cte(c1,c2) AS (SELECT ...)` 中列名数量必须等于内层 projection 列数
4. **物化语义**：CTE 多次引用时每次看到相同数据

### 实现步骤

**Step 1: 扩展 `SqlCte.java`**
- 新增 `List<String> columnNames` 字段（可为 null 或空列表）
- `record SqlCte(String name, List<String> columnNames, SqlSelect query)`

**Step 2: 修改 `SqlParser.java` parseWith()**
- 解析 `WITH cte(col1, col2) AS (...)` 语法：CTE 名后如遇 `LPAREN` 且下一 token 不是 `SELECT`，则解析为列名列表

**Step 3: 修改 `SqlValidator.java` deriveCteSchema()**
- 若 `cte.columnNames()` 非空，用指定列名替换推导列名，并验证数量匹配（不匹配则抛 ValidationException）

**Step 4: CTE 物化 vs 内联决策（可选优化）**
- `convertWithSelect()` 中统计每个 CTE 被引用次数
- 引用 1 次 → inline（直接嵌入 RelNode 子树）
- 引用 >= 2 次 → 保持物化（RelDerivedScan + SubqueryExec）

### 关键文件
| 文件 | 改动 |
|------|------|
| `sql/ast/SqlCte.java` | +columnNames 字段 |
| `sql/parser/SqlParser.java` | parseWith() 解析列名列表 |
| `sql/validation/SqlValidator.java` | deriveCteSchema() 列名替换+校验 |
| `sql/planner/SqlToRelConverter.java` | 可选：inline vs 物化决策 |

### 测试 (≥3)
| # | 场景 | 验证不变式 |
|---|------|-----------|
| 1 | `WITH cte(a,b) AS (SELECT id,name FROM users) SELECT cte.a FROM cte` | 列名一致性 |
| 2 | `WITH cte(a) AS (SELECT id,name FROM users)` 列数不匹配报 ValidationException | 列名一致性 |
| 3 | CTE 引用 2 次，两次结果数据一致 | 物化语义 |
| 4 | 嵌套 WITH 内层 CTE 不泄漏到外层 | 作用域隔离 |

---

## Feature #13: 并行执行

### Fail-First Gate

#### 禁止设计
1. **每个算子独立创建线程池** — 复杂查询含 scan+join+agg+sort 时线程数爆炸（4×8=32 线程），OS 切换开销反降性能。必须查询级共享线程池
2. **Partitioned Hash Join 不做重分区** — 若采用分区各自建表模式（Partitioned），相同 key 必须在同一分区 hash table 中，不按 join key hash 分区会漏行。本计划采用 Shared Hash Table 模式规避此问题

#### 核心不变式
1. **结果等价**：任何并行度下结果（忽略行序）与 parallelism=1 完全相同
2. **线程安全**：并行算子间数据传递用 BlockingQueue，共享 hash table 有 build→probe barrier
3. **资源回收**：close() 后所有工作线程在有限时间内终止，无泄漏
4. **背压**：BlockingQueue 有界（1024），生产者满时阻塞不 OOM

### 实现步骤

**Step 1: 新建 `QueryThreadPool.java`**
- 路径：`src/main/java/.../sql/exec/QueryThreadPool.java`
- 查询级共享线程池，`ExecutionContext` 新增 `queryThreadPool` 字段
- 修改 `ParallelScanExec` 使用外部线程池而非内部创建

**Step 2: 新建 `ParallelHashJoinExec.java`**
- 路径：`src/main/java/.../sql/exec/ParallelHashJoinExec.java`
- Shared Hash Table 模式：
  1. 并行 build：多线程扫描 build 端 → `ConcurrentHashMap<joinKey, List<Row>>`
  2. CountDownLatch barrier：等待 build 完成
  3. 并行 probe：多线程扫描 probe 端 → lookup hash table → 结果入 BlockingQueue
- 仅支持 INNER JOIN 并行，OUTER JOIN 走串行

**Step 3: 新建 `ParallelAggregateExec.java`**
- 路径：`src/main/java/.../sql/exec/ParallelAggregateExec.java`
- 两阶段聚合：
  - Partial：每分区各自局部聚合（COUNT→count, SUM→sum, AVG→sum+count）
  - Final：汇总局部结果做最终聚合

**Step 4: 新建 `ParallelSortExec.java`**
- 路径：`src/main/java/.../sql/exec/ParallelSortExec.java`
- 每线程对各自分区排序 → PriorityQueue K 路归并

**Step 5: 修改 `PhysicalPlanner.java`**
- `planInternal()` 中 parallelism > 1 时：
  - `RelJoin` (INNER + equi) → `ParallelHashJoinExec`
  - `RelAggregate` → `ParallelAggregateExec`
  - `RelSort` → `ParallelSortExec`

### 关键文件
| 文件 | 改动 |
|------|------|
| `sql/exec/QueryThreadPool.java` | 新建 |
| `sql/exec/ParallelHashJoinExec.java` | 新建 |
| `sql/exec/ParallelAggregateExec.java` | 新建 |
| `sql/exec/ParallelSortExec.java` | 新建 |
| `sql/exec/ExecutionContext.java` | +queryThreadPool 字段 |
| `sql/exec/ParallelScanExec.java` | 改用外部线程池 |
| `sql/exec/PhysicalPlanner.java` | planInternal() 生成并行算子 |

### 测试 (≥3)
| # | 场景 | 验证不变式 |
|---|------|-----------|
| 1 | 并行 Hash Join (parallelism=4) 结果行集 == 串行 Hash Join | 结果等价 |
| 2 | 并行聚合 COUNT/SUM/AVG 结果与串行一致 | 结果等价 |
| 3 | 查询执行完毕后 QueryThreadPool 线程全部终止 | 资源回收 |
| 4 | 并行 Sort 结果有序且与串行一致 | 结果等价 |
| 5 | >1024 行并行 scan+join 不 OOM | 背压 |

---

## 实施顺序建议

1. **#11 统计信息** → 为 #10 DP 枚举提供更精确的代价估计
2. **#10 JOIN 重排序** → 依赖 #11 的 CostModel 增强
3. **#12 CTE 补全** → 独立，改动最小
4. **#13 并行执行** → 独立，改动最大

## 验证方式

每个特性完成后：
1. `gradle test --tests LowPriorityFeaturesTest` 运行已有测试
2. 新增测试用例放入 `LowPriorityFeaturesTest.java` 对应的 `@Nested` 类中
3. 用户自行编译测试（遵照 CLAUDE.md 要求）
