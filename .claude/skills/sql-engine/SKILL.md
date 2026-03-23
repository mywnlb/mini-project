---
name: sql-engine
description: mini-db SQL 引擎完整架构：词法/语法分析、AST、语义校验、逻辑计划、规则优化(RBO)、代价优化(CBO)、物理计划、Volcano 执行器、SPI 存储集成。涵盖主要技术实现、使用注意事项和架构图。
---

# mini-db SQL 引擎 Skill

> 当用户问 SQL 引擎的架构设计、执行流程、各模块职责、或需要扩展 SQL 功能时，按本文结构回答。

---

## 1) 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                          SQL 文本输入                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │    LEXER (词法分析)   │
                    │  SqlLexer → Token[]  │
                    │  95 个 SQL 关键字     │
                    └──────────┬──────────┘
                               │ TokenStream
                    ┌──────────▼──────────┐
                    │   PARSER (语法分析)   │
                    │  递归下降解析器        │
                    │  SqlParser → SqlNode │
                    └──────────┬──────────┘
                               │ AST (SqlNode 树)
                    ┌──────────▼──────────┐
                    │  VALIDATOR (语义校验)  │
                    │  类型检查 / 列解析     │
                    │  CTE 作用域管理       │
                    │  → ValidatedSqlNode  │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼───────────────┐
                    │  SQL-TO-REL CONVERTER     │
                    │  SqlToRelConverter         │
                    │  ValidatedNode → RelNode  │
                    └──────────┬───────────────┘
                               │ 逻辑计划 (RelNode 树)
                    ┌──────────▼──────────┐
                    │  RULE OPTIMIZER (RBO) │
                    │  13 条规则 × 20 轮    │
                    │  Bottom-Up 固定点迭代  │
                    └──────────┬──────────┘
                               │ 优化后逻辑计划
                    ┌──────────▼──────────┐
                    │  COST OPTIMIZER (CBO) │
                    │  JOIN 算法选择        │
                    │  基数估计 + 代价模型   │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────────┐
                    │  PHYSICAL PLANNER       │
                    │  RelNode → ExecNode     │
                    │  CBO/手动 两种模式       │
                    └──────────┬──────────────┘
                               │ 物理执行计划 (ExecNode 树)
                    ┌──────────▼──────────────┐
                    │  VOLCANO EXECUTOR        │
                    │  open() → next() → close │
                    │  流式 Pull 模型           │
                    └──────────┬──────────────┘
                               │
                    ┌──────────▼──────────────┐
                    │  DATA LAYER (SPI 接口)   │
                    │     DataSourceSpi        │
                    │    ↙            ↘        │
                    │ MockData    StorageDB    │
                    └─────────────────────────┘
```

---

## 2) 包结构与文件分布

```
cn.zhangyis.minidb.sql/
├── lexer/          (4 files)   词法分析: SqlLexer, Token, TokenStream, SqlKeyword
├── parser/         (1 file)    语法分析: SqlParser（递归下降）
├── ast/            (47 files)  AST 节点: SqlSelect, SqlInsert, SqlJoin, SqlBinaryOp ...
├── validation/     (4 files)   语义校验: SqlValidator, ValidatedSqlSelect ...
├── planner/        (1 file)    逻辑计划转换: SqlToRelConverter
├── rel/            (24 files)  关系代数算子: RelScan, RelFilter, RelJoin, RelAggregate ...
├── optimize/       (13 files)  优化器: RuleOptimizer, CostOptimizer, 13 条优化规则
│   └── cost/                   代价模型: CostModel, CostOptimizer
├── exec/           (35 files)  执行器: HashJoinExec, SortExec, AggregateExec ...
├── catalog/        (11 files)  元数据: CatalogSpi, TableMeta, StatisticsStore ...
├── types/          (3 files)   类型系统: SqlType, TypeCoercion
├── functions/      (5 files)   函数注册: FunctionRegistry, UPPER/LOWER/COALESCE
└── TestMain.java               集成测试入口
```

**总计**: 主代码 157 文件, 测试 22 文件

---

## 3) 各阶段核心技术实现

### 3.1 词法分析 (Lexer)

- **SqlLexer**: 将 SQL 文本切分为 Token 流
- 支持 95 个 SQL 关键字（SELECT, FROM, WHERE, JOIN, GROUP BY ...）
- **TokenStream**: Token 流封装，提供 peek/consume/expect 接口

### 3.2 语法分析 (Parser)

- **SqlParser**: 递归下降解析器，将 Token 流解析为 AST
- **支持的 SQL 语句**:
  - DDL: `CREATE TABLE`, `DROP TABLE`, `ALTER TABLE`, `CREATE INDEX`, `DROP INDEX`
  - DML: `SELECT`, `INSERT`, `INSERT...SELECT`, `UPDATE`, `DELETE`
  - 事务: `BEGIN`, `COMMIT`, `ROLLBACK`
  - 集合: `UNION`, `EXCEPT`, `INTERSECT`
  - 高级: 子查询, CTE (`WITH`), 窗口函数, `DISTINCT`, `GROUP BY/HAVING`, `ORDER BY`, `LIMIT/OFFSET`

### 3.3 AST (抽象语法树)

- **核心接口**: `SqlNode` + `SqlKind` 枚举
- **使用 Java Record** 实现不可变 AST 节点（支持模式匹配）
- **主要节点类**:

| 类别 | 节点类 |
|------|--------|
| DML | SqlSelect, SqlInsert, SqlInsertSelect, SqlUpdate, SqlDelete |
| DDL | SqlCreateTable, SqlDropTable, SqlAlterTable, SqlCreateIndex |
| 表达式 | SqlBinaryOp, SqlIdentifier, SqlLiteral, SqlFunctionCall, SqlCase, SqlCast |
| 谓词 | SqlBetween, SqlInList, SqlInSubquery, SqlExists |
| 聚合 | SqlAggCall (COUNT/SUM/AVG/MAX/MIN) |
| JOIN | SqlJoin (含 JoinType: INNER/LEFT/RIGHT/FULL/CROSS) |
| 子查询 | SqlSubquery, SqlDerivedTable |

### 3.4 语义校验 (Validator)

- **SqlValidator**: 通过 `CatalogSpi` 解析表名/列名
- 功能:
  - 类型检查与类型不匹配检测
  - 聚合表达式与 `GROUP BY` 合法性校验
  - CTE 作用域管理（递归解析）
  - 输出 `ValidatedSqlNode`（携带元数据）

### 3.5 逻辑计划 (RelNode)

- **SqlToRelConverter**: 将 ValidatedSqlNode 转为 RelNode 树
- **RelNode 抽象基类**:
  - `copy(List<RelNode> inputs)`: 不可变变换
  - `explain()`: 计划可视化
  - `traitSet()`: 分布/排序物理属性

| 算子 | 类名 | 输入数 |
|------|------|--------|
| 表扫描 | RelScan | 0 |
| 索引扫描 | RelIndexedScan | 0 |
| 过滤 | RelFilter | 1 |
| 投影 | RelProject | 1 |
| 连接 | RelJoin | 2 |
| Semi 连接 | RelSemiJoin | 2 |
| Anti 连接 | RelAntiJoin | 2 |
| 聚合 | RelAggregate | 1 |
| 排序 | RelSort | 1 |
| 去重 | RelDistinct | 1 |
| 并集 | RelUnion | 2 |
| 派生表扫描 | RelDerivedScan | 1 |

### 3.6 规则优化器 (RBO)

- **RuleOptimizer**: 固定点迭代，Bottom-Up 应用规则
- **算法**: 每轮遍历 13 条规则，命中则变换 + 递归优化子节点，最多 20 轮

**13 条优化规则（按执行顺序）**:

| # | 规则 | 作用 |
|---|------|------|
| 1 | SubqueryUnnestingRule | 子查询去关联化 → SemiJoin/AntiJoin |
| 2 | ConstantFoldingRule | 常量折叠（编译期求值） |
| 3 | FilterProjectTransposeRule | Filter 与 Project 交换（Filter 下推） |
| 4 | FilterJoinPushdownRule | Filter 下推到 JOIN 内部 |
| 5 | PushFilterIntoScanRule | Filter 下推到 Scan（索引感知） |
| 6 | JoinReorderRule | DP 多表 JOIN 重排序 |
| 7 | JoinCommuteRule | JOIN 交换律 |
| 8 | ProjectionPruningRule | 裁剪未使用的列投影 |
| 9 | LimitPushdownRule | LIMIT 下推到 Sort |

### 3.7 代价优化器 (CBO)

- **CostModel**: 基数估计 + 选择率计算
  - 默认选择率: EQ=0.1, Range=0.3, AND=乘积, OR=加法
  - 支持 `StatisticsStore` 提供直方图精确估计
- **CostOptimizer**: 基于代价选择 JOIN 算法

**JOIN 算法选择逻辑**:
```
右表行数 > MAX_HASH_BUILD_ROWS → SortMergeJoin
右表有索引 + 启用索引查找       → IndexNestedLoopJoin
Hash 代价 < NL 代价            → HashJoin
否则                           → NestedLoopJoin
```

**代价公式**:
- NestedLoopJoin: O(M × N)
- HashJoin: O(M + N)
- SortMergeJoin: O(M·logM + N·logN)
- IndexNestedLoopJoin: O(M × logN)

### 3.8 物理计划 (PhysicalPlanner)

- **PhysicalPlanner**: 将 RelNode 转为 ExecNode
- 两种模式:
  - **CBO 自动模式**: CostOptimizer 选算法
  - **手动模式**: 指定 `JoinAlgorithm`（用于测试）
- 支持并行模式（多线程执行）

### 3.9 Volcano 执行器

- **ExecNode 接口**: `open()` / `next()` / `close()` — Pull 流式模型
- 每次 `next()` 返回一行 `Row`，null 表示结束

**主要执行器 (35+ 个)**:

| 执行器 | 说明 |
|--------|------|
| ScanExec | 全表扫描 |
| IndexScanExec | 索引扫描 |
| FilterExec | 行过滤 |
| ProjectExec | 列投影 |
| NestedLoopJoinExec | 嵌套循环 JOIN |
| HashJoinExec | 哈希 JOIN (Build/Probe) |
| SortMergeJoinExec | 排序归并 JOIN |
| IndexNestedLoopJoinExec | 索引嵌套循环 JOIN |
| AggregateExec | 哈希聚合 |
| SortExec | 内存排序 |
| DistinctExec | 哈希去重 |
| InsertExec / UpdateExec / DeleteExec | DML 执行 |
| ParallelSortExec | 并行排序 |

### 3.10 数据行格式 (Row)

```java
public class Row {
    private final Map<String, Object> columns;  // LinkedHashMap 保序

    public Object get(String column);            // 支持 "table.col" 和 "col" 两种形式
    public void put(String column, Object value);
    public Row merge(Row other);                 // JOIN 合并行
    public static Row nullRow(Row template);     // OUTER JOIN 空行
}
```

### 3.11 类型系统

```java
public enum SqlType {
    INT32(4B), BIGINT(8B), VARCHAR(64B), DECIMAL(16B), DATETIME(8B)
}
```

- **TypeCoercion**: 自动类型提升（比较时）
- **FunctionRegistry**: UPPER / LOWER / COALESCE 标量函数

---

## 4) SPI 接口设计

### 4.1 CatalogSpi（元数据）

```java
public interface CatalogSpi {
    TableMeta getTable(String name);
    ColumnMeta getColumns(String tableName);
    IndexMeta getIndexes(String tableName);
    // + create/drop 操作
}
```

- `TableMeta`: 表结构（列列表, 行数）
- `ColumnMeta`: 列名, 类型, 是否可空, 默认值
- `StatisticsStore`: 表基数 + 列直方图

### 4.2 DataSourceSpi（存储层桥接）

```java
public interface DataSourceSpi {
    Iterator<Row> scan(String tableName);
    void insertRow(String tableName, Row row);
    int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater);
    int deleteRows(String tableName, Predicate<Row> filter);

    // 可选: 索引查找
    default Iterator<Row> lookup(String tableName, String column, Object value);
    default boolean supportsLookup(String tableName, String column);

    // 可选: 并行扫描
    default int partitionCount(String tableName);
    default Iterator<Row> scanPartition(String tableName, int id, int total);
}
```

### 4.3 ExecutionContext（事务桥接）

```java
public class ExecutionContext {
    private final TransactionManager txnManager;
    private Transaction currentTxn;
    private int parallelism;
    private QueryThreadPool queryThreadPool;
}
```

---

## 5) 设计模式

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Visitor / Copy** | RelNode | 不可变树变换，`copy()` 返回新节点 |
| **Iterator (Volcano)** | ExecNode | 流式拉取，低内存 |
| **SPI** | CatalogSpi / DataSourceSpi | 可插拔元数据和存储 |
| **Record (ADT)** | SqlNode AST | Java Record 不可变 + 模式匹配 |
| **Fixed-Point Iteration** | RuleOptimizer | 规则反复应用至收敛 |
| **Builder / Factory** | SqlToRelConverter / RelFactories | Bottom-Up 构建计划树 |
| **Strategy** | CostOptimizer | 代价驱动的算法选择 |

---

## 6) 使用注意事项

### 6.1 扩展新 SQL 语法
1. 在 `SqlLexer` 中添加关键字（如果是新关键字）
2. 在 `ast/` 中创建对应 SqlNode（推荐使用 Record）
3. 在 `SqlParser` 中添加解析分支
4. 在 `SqlValidator` 中添加语义校验逻辑
5. 在 `SqlToRelConverter` 中添加 RelNode 映射
6. 在 `PhysicalPlanner` 中添加 ExecNode 映射
7. 实现对应的 ExecNode 执行器

### 6.2 添加优化规则
1. 实现 `OptimizeRule` 接口（`matches()` + `apply()`）
2. 在 `RuleOptimizer.rules` 列表中注册（注意顺序）
3. 规则要返回新的 RelNode（不可变变换）
4. 防止规则互相冲突导致无限循环（最多 20 轮限制）

### 6.3 添加 JOIN 算法
1. 在 `JoinAlgorithm` 枚举中添加新算法
2. 实现对应 ExecNode（遵循 Volcano 接口）
3. 在 `CostOptimizer` 中添加代价公式和选择逻辑
4. 在 `PhysicalPlanner` 中添加规划分支

### 6.4 NULL 处理
- Hash 表中 NULL 键被排除（SQL 语义: NULL ≠ NULL）
- OUTER JOIN 未匹配行使用 `Row.nullRow()` 填充
- 聚合中 NULL 需要特殊处理（COUNT(*) 包含 NULL 行, COUNT(col) 不包含）

### 6.5 事务集成
- `ExecutionContext` 持有当前事务
- DDL/DML 执行器通过 `DataSourceSpi` 与存储交互
- `BEGIN/COMMIT/ROLLBACK` 由 `PhysicalPlanner.planSqlNode()` 直接处理

### 6.6 测试约定
- 使用 `MockCatalog` + `MockDataSource` 进行单元测试
- 使用 `StorageSqlSessionIntegrationTest` 进行端到端集成测试
- 优化规则应有独立的规则测试（输入计划 → 输出计划断言）

---

## 7) 关键不变量 (Invariants)

1. **RelNode 不可变**: 所有变换通过 `copy()` 返回新实例
2. **RBO 收敛保证**: 最多 20 轮迭代，防止规则循环
3. **Volcano 协议**: `next()` 返回 null 表示 EOF，之后不可再调用
4. **行格式一致性**: LinkedHashMap 保证列顺序; 合并行时列名唯一
5. **NULL 语义**: NULL ≠ NULL（Hash JOIN、DISTINCT、GROUP BY 均遵守）
6. **OUTER JOIN 完整性**: 通过 `matchedKeys` 跟踪，EOF 时输出未匹配行

---

## 8) 测试覆盖

| 测试文件 | 覆盖功能 |
|----------|---------|
| PredicateTest | WHERE 谓词求值 |
| SelectAliasTest | SELECT 别名 |
| SelectDistinctTest | DISTINCT 去重 |
| JoinCommuteRuleTest | JOIN 交换律规则 |
| IndexPathEnabledTest | 索引路径规划 |
| OuterJoinTest | 外连接 |
| SemiAntiJoinTest | Semi/Anti JOIN |
| HavingAggregateTest | HAVING + 聚合 |
| AggregateValidationTest | 聚合校验 |
| TransactionSqlTest | 事务控制 |
| StorageSqlSessionIntegrationTest | 端到端集成 |
| TypeCoercionTest | 类型转换 |
| ArithmeticExprTest | 算术表达式 |
| DerivedTableTest | 派生表(子查询) |
| ExistsInSubqueryTest | EXISTS/IN 子查询 |

---

## 9) 后续可扩展方向

1. **两阶段聚合**: PartialAgg + FinalAgg（AVG → SUM + COUNT）
2. **Sort 下推 / MergeSort**: 排序下推到存储层
3. **并行 Hash Aggregate**: 分区并行聚合
4. **窗口函数执行器**: ROW_NUMBER / RANK / DENSE_RANK
5. **预编译语句 (Prepared Statement)**: 参数化查询 + 计划缓存
6. **更多标量函数**: 字符串/日期/数学函数扩展
7. **EXPLAIN 命令**: 输出查询计划可视化
