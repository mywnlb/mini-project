# SQL Layer Remediation Roadmap

本文档将当前 SQL 层、优化器、执行层、catalog/storage bridge 的审查结论整理为正式实施计划。目标不是并行铺开所有问题，而是先消除会返回错误结果的语义问题，再打通事务与 storage 闭环，最后恢复真实索引能力并扩展 SQL 能力面。

# 架构总览

当前主链路为：

`Parser/AST -> Validator -> SqlToRelConverter -> RuleOptimizer -> CostOptimizer -> PhysicalPlanner -> ExecNode -> DataSourceSpi/StorageDataSource -> storage`

当前存在四条关键断裂：

1. 语义断裂：
   - `JoinCommuteRule` 改变了输入顺序，但 join condition 没有同步重写。
   - `HAVING` 与聚合准备、聚合输出槽位之间没有统一语义表示。
   - `FilterExec` 使用 Java boolean 语义，不符合 SQL 三值逻辑。
2. 事务断裂：
   - `ExecutionContext.currentTxn/auto-commit` 只对事务控制语句生效，普通 DML executor 没消费事务上下文。
3. 索引断裂：
   - rule/CBO 假设索引可用，但 executor 没有真实 `IndexScan` / lookup join。
4. 元数据断裂：【已完成】 StorageCatalog + SchemaRegistry 初始化（P1-5 真闭环）
   - StorageCatalog 完整委托 CatalogManager，DDL/createTable 路径强制生成 SchemaRegistry(v=1)
   - CatalogBootstrap.rebuildTableDescriptor 与 CatalogManager.buildSchema 一致重建 schema
   - SchemaRegistry.get 支持 version=0 安全 fallback（兼容旧测试数据）
   - PK 检测强化，pageNo=-1 问题解决，StorageSqlSessionIntegrationTest 通过
   - optimizer 现在基于真实元数据和索引路径决策

后续 optimizer 工作的前置条件：

- JOIN 重写后的语义映射必须稳定，不能再出现逻辑计划合法但物理执行错侧的问题。
- 聚合/HAVING 必须有统一的逻辑表示和稳定输出槽位。
- optimizer 只能对真实可执行能力做规则和代价决策，不能继续依赖伪索引能力。
- storage catalog 与事务桥接必须先提供真实元数据和事务语义，否则 explain/CBO 只会继续放大错误假设。

# 风险分级（P0/P1/P2）

## P0

### 1. JoinCommuteRule 条件未同步重写

- 定性：语义错误，必须先修
- 分类：错误结果风险
- 根因：
  - 交换左右输入后保留原 condition，`PhysicalPlanner` 仍按旧 condition 提取 `leftKey/rightKey`
- 影响范围：
  - 所有被 commute 过的等值 JOIN
  - `HashJoin`、`SortMergeJoin`、`IndexJoin`
- 最小修复面：
  - commute 时同步重写 condition 左右引用，或为 `RelJoin` 引入显式 join key 绑定，禁止下游再通过位置推断
  - 增加 planner 侧一致性校验，不一致则退回 `NestedLoop + 原条件`

### 2. HAVING / 聚合准备链路不完整

- 定性：语义错误，必须先修
- 分类：错误结果风险
- 根因：
  - 只有 `GROUP BY != null` 才构造聚合
  - `aggCalls` 仅来自 SELECT projection，HAVING 中未投影聚合不会被准备
  - executor 使用字符串拼接列名承载聚合结果，没有稳定内部槽位
- 影响范围：
  - `HAVING` without `GROUP BY`
  - HAVING 中未投影聚合
  - 聚合过滤、投影、别名协同路径
- 最小修复面：
  - 按“是否存在聚合”而不是“是否存在 GROUP BY”决定是否建 `RelAggregate`
  - 同时收集 SELECT/HAVING 中的 `SqlAggCall`
  - 为每个聚合分配稳定输出槽位，统一供 HAVING 和 projection 使用

### 3. validator 与 NULL 语义不完整

- 定性：语义错误，必须先修
- 分类：错误结果风险
- 根因：
  - `BETWEEN/IN` 校验不完整
  - 执行层没有 SQL 三值逻辑，`NULL` 比较和布尔组合按 Java boolean 处理
- 影响范围：
  - `NULL = 1`
  - `NULL AND/OR`
  - `IN/NOT IN/BETWEEN`
  - WHERE/HAVING 过滤语义
- 最小修复面：
  - 引入 `TRUE/FALSE/UNKNOWN`
  - 表达式求值与过滤动作分离
  - validator 补齐 `BETWEEN/IN/IS NULL` 结构校验

## P1

### 4. 事务控制语句与普通 DML 未打通

- 定性：伪实现，必须做真
- 分类：伪实现
- 根因：
  - `BEGIN/COMMIT/ROLLBACK` 只操作 `ExecutionContext`
  - INSERT/UPDATE/DELETE executor 只走 `DataSourceSpi`
  - `currentTxn/auto-commit` 没真正驱动 storage DML
- 影响范围：
  - SQL 层显式事务
  - auto-commit 语义
  - storage-backed SQL 事务正确性
- 最小修复面：
  - 为 DML executor 注入统一事务提供者
  - 显式事务复用 `currentTxn`
  - 非显式事务自动 begin/commit，异常时 rollback

### 5. StorageCatalog 的 createTable/addColumn/createIndex/dropIndex/index lookup 未接通真实 storage

- 定性：伪实现，必须做真
- 分类：伪实现
- 根因：
  - 关键接口直接 `UnsupportedOperationException` 或返回空索引列表
  - SQL 元数据到 storage descriptor 的映射仍然依赖简化推断
- 影响范围：
  - SQL DDL
  - 索引元数据读取
  - optimizer 对索引存在性的判断
- 最小修复面：
  - 优先完成 `createTable/createIndex/dropIndex/getIndexes`
  - `addColumn` 若 storage 尚不支持，显式标为受限能力
  - 收敛 SQL `TableMeta/ColumnMeta/IndexMeta` 与 storage descriptor 的一一映射

### 6. 索引支持是假实现

- 定性：伪实现，必须删除或做真
- 分类：伪实现
- 根因：
  - rule 和 CBO 会产出索引路径
  - executor 对 `RelIndexedScan` 仍是 `Scan + Filter`
  - `IndexNestedLoopJoinExec` 实际委托 `HashJoinExec`
- 影响范围：
  - explain/CBO 与真实执行行为不一致
  - 连接算法选择
  - 基于“索引存在”的所有性能结论
- 最小修复面：
  - 短期禁用假索引路径
  - 长期补齐真实 `IndexScanExec` 和 index lookup join，再恢复 CBO 与规则

## P2

### 7. 语法和表达式支持仍是 MVP

- 定性：能力缺失，可以后修
- 分类：能力缺失
- 范围：
  - 多 JOIN
  - SELECT 表达式
  - UPDATE SET 表达式
  - 更多字面量类型
- 处理原则：
  - 只有在 P0 和 P1 完成后再扩能力，避免继续在错误语义和伪执行能力上叠功能

# 问题依赖图

```text
P0-1 Join 语义修复
  -> 是所有 JOIN optimizer / planner / executor 工作的前置条件

P0-2 HAVING / 聚合闭环修复
  -> 是聚合优化、HAVING 下推、聚合表达式支持的前置条件

P0-3 NULL 三值逻辑 / validator 修复
  -> 是谓词优化、过滤执行、表达式扩展的前置条件

P1-4 事务上下文打通 DML
  -> 是 storage-backed SQL 集成测试前置条件
  -> 依赖 storage datasource 路径继续收敛

P1-5 StorageCatalog 真闭环
  -> 是真实 DDL / index metadata / optimizer 感知索引的前置条件

P1-6 索引伪实现清理与真实执行接入
  -> 依赖 P1-5 提供真实 index metadata
  -> 依赖 P0-1 保证 join 语义稳定
  -> 是后续 CBO/optimizer 深化工作的前置条件

P2-7 语法与表达式扩展
  -> 依赖 P0-2 / P0-3 语义底座
```

# 核心闭环缺口

## 1. 逻辑语义闭环缺口

- join rewrite 后的语义没有被物理规划层可靠继承
- HAVING 与 Aggregate 之间没有统一内部表示
- SQL NULL 三值逻辑没有进入执行层

## 2. SQL 控制语义到 storage 执行语义的桥接缺口

- 事务控制语句只更新上下文，不影响普通 DML
- `StorageDataSource` 是桥接雏形，但还不是 SQL 默认事务执行入口

## 3. 元数据与执行能力的一致性缺口

- `StorageCatalog` 不能提供真实 DDL/index 元数据
- optimizer 使用的“索引能力”不是 executor 的真实能力

## 4. 测试闭环缺口

- 缺少 JOIN 顺序翻转回归
- 缺少聚合/HAVING 回归
- 缺少 DDL/索引/事务的 storage-backed SQL 测试
- 缺少 CBO 选择与真实执行路径一致性测试

# 分模块修复方案

## Optimizer / Rule 层

- 修复 `JoinCommuteRule`
  - 交换输入时同步重写 condition
  - 或为 `RelJoin` 引入显式 join key 绑定，禁止下游通过位置猜
- 收紧规则前提
  - 没有真实 executor 能力支撑的索引路径，先不要让 rule 产生

## Planner / 聚合语义层

- 重构聚合触发条件
  - 只要 SELECT 或 HAVING 含聚合，就构造 `RelAggregate`
- 建立统一聚合收集阶段
  - 同时扫描 SELECT/HAVING 中的 `SqlAggCall`
- 建立稳定聚合输出槽位
  - HAVING、projection、后续 filter 共用同一内部标识

## Validator / 表达式语义层

- 补齐 `BETWEEN/IN/IS NULL` 的结构校验
- 引入 SQL 三值逻辑
- 统一 NULL 在比较、布尔组合、`IN/NOT IN/BETWEEN` 中的传播规则

## Executor / 事务桥接层

- 为 INSERT/UPDATE/DELETE executor 注入统一事务上下文
- 统一显式事务与 auto-commit 语义
- 将 `StorageDataSource` 纳入真实 SQL 执行路径，而不是旁路实验件

## Catalog / Storage bridge 层

- 优先完成真闭环：
  - `createTable`
  - `createIndex`
  - `dropIndex`
  - `getIndexes`
- `addColumn` 如暂不支持，则显式 fail-fast
- 收敛 SQL 元数据和 storage descriptor 映射

## Index / CBO 层

- 第一阶段：删除或禁用伪索引路径
  - 默认 planner 不再产出假 `RelIndexedScan` / `INDEX_NESTED_LOOP`
- 第二阶段：接入真实索引能力
  - 真实 `IndexScanExec`
  - 真实 index lookup / index nested loop join
- 第三阶段：恢复真实 CBO / rule 决策

## Parser / SQL 能力层

- 在前述语义底座完成后再扩：
  - 多 JOIN
  - SELECT 投影表达式
  - UPDATE `SET col = expr`
  - 更多字面量类型

# 测试补齐方案

## 1. 语义正确性回归

- JOIN 顺序反转：
  - 同一 SQL 在 commute 前后、不同 join 算法下结果一致
- HAVING / 聚合：
  - `HAVING` without `GROUP BY`
  - HAVING 中未投影聚合
  - 聚合别名与 HAVING 组合
- NULL 三值逻辑：
  - `NULL = 1`
  - `NULL AND TRUE`
  - `x IN (1, NULL)`
  - `x NOT IN (1, NULL)`
  - `x BETWEEN NULL AND 3`

## 2. Planner / Executor 一致性测试

- join key 归属与左右输入一致
- explain / chosen algo 与实际 executor 类型一致
- 无真实 index exec 时，planner 不产出索引路径

## 3. Storage-backed SQL 集成测试

- `CREATE TABLE -> INSERT -> SELECT`
- `CREATE INDEX -> 查询/连接`
- `BEGIN -> DML -> COMMIT/ROLLBACK`
- auto-commit 单语句 DML

## 4. 能力边界与 fail-fast 测试

- 暂不支持能力必须稳定报错
- 未完成索引路径不得悄悄退化成伪实现
- `addColumn` 若未实现，应明确 fail-fast

# 分阶段实施路线图

## 阶段 1：语义止血

- 修复 `JoinCommuteRule` 条件同步问题
- 修复 HAVING/聚合准备闭环
- 引入 SQL 三值逻辑并补齐 validator 对 `BETWEEN/IN` 的校验

## 阶段 2：事务与 storage 桥接落地

- 将 `ExecutionContext` 注入普通 DML 执行路径
- 打通 `currentTxn/auto-commit -> StorageDataSource -> TransactionalDml`
- 建立 storage-backed SQL 事务集成测试

## 阶段 3：Catalog 真闭环

- 接通 `StorageCatalog.createTable/createIndex/dropIndex/getIndexes`
- 将暂不支持的 `addColumn` 明确为受限能力
- 补齐 DDL 后元数据可见性和 SQL 行为测试

## 阶段 4：索引伪实现清理与真实索引路径恢复 【已完成】

- 先禁用默认伪索引 rule/CBO 路径 → **完成**
- 再实现真实 `IndexScanExec` / index lookup join → **完成**
- 更新 `CostOptimizer`/`CostModel` 恢复受控的索引选择（enableIndexLookup）→ **完成**
- 恢复真实 `RelIndexedScan` 和 `INDEX_NESTED_LOOP` → **完成**
- 测试文件重构为 `IndexPathEnabledTest.java`，新增对应验证

**完成标准已满足**：
- 默认 planner 不再产出伪索引路径
- 真实索引执行能力接入后，CBO 可受控启用索引路径
- explain / chosen algo / executor 类型一致
- 这是继续做 CBO 和 optimizer 深化工作的第二前置条件

## 阶段 5：能力扩展

- 扩 parser / validator / planner / executor 的表达式与语法能力
- 补多表 JOIN、SELECT 表达式、UPDATE SET 表达式、更多字面量

# 每阶段完成标准

## 阶段 1 完成标准

- `JoinCommuteRule` 不再导致 key 错侧
- `HAVING` without `GROUP BY` 与 HAVING 未投影聚合均可正确执行
- `NULL` 相关谓词符合 SQL 三值逻辑
- 新增 JOIN / HAVING / NULL 回归测试全部通过
- 这是所有后续 optimizer 工作的第一前置条件

## 阶段 2 完成标准

- 显式事务可控制 INSERT/UPDATE/DELETE
- auto-commit 对单语句 DML 生效
- rollback / commit 语义可由 storage-backed SQL 测试证明
- SQL 事务路径不再是语法壳子

## 阶段 3 完成标准

- `StorageCatalog` 可完成 create/drop index 和 create table 真操作
- SQL 层读取到的索引元数据与 storage 一致
- 暂不支持的 DDL 能力显式 fail-fast
- optimizer 获得的 catalog 信息不再是空壳或假信息

## 阶段 4 完成标准

- 默认 planner 不再产出伪索引路径
- 真实索引执行能力接入后，CBO 才重新启用索引路径
- explain / chosen algo / executor 类型一致
- 这是继续做 CBO 和 optimizer 深化工作的第二前置条件

## 阶段 5 完成标准

- 多 JOIN、表达式投影、UPDATE SET 表达式、字面量扩展可稳定工作
- 所有新增能力都建立在已修复语义和真实执行能力之上
- 不再新增“能力看似支持、实际是假实现”的路径
