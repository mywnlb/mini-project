---
name: sql-optimization
description: 解释 SQL 查询优化体系：查询优化器（RBO/CBO）、查询改写与下推、执行器模型、JOIN/子查询/聚合/排序的优化策略与执行算法选择。基于 PolarDB-X 文档，结合 mini-db 项目实现。
---

# SQL 查询优化 Skill

## 适用范围
用于回答 SQL 查询优化相关问题，重点讲清：
- 查询优化器两阶段架构（RBO 规则改写 → CBO 代价枚举）
- 算子下推策略（Filter/Project/Limit/Sort/Agg/JOIN/子查询）
- 执行器模型（Pull-Push 混合、TP_LOCAL/AP_LOCAL/MPP）
- JOIN 算法选择（NLJoin/HashJoin/BKAJoin/SortMergeJoin）与顺序枚举
- 子查询去关联化（Unnesting → SemiJoin/AntiJoin）
- 聚合两阶段优化（PartialAgg + FinalAgg）
- 排序优化（MemSort/TopN/MergeSort）

## 强制解释主线（必须出现）
SQL → Parser(AST) → 逻辑计划 → RBO(规则改写/下推) → CBO(代价枚举) → 物理计划 → Executor(结果)

## 核心知识点

### 1. 两阶段优化
- **RBO（Rule-Based Optimizer）**：启发式规则改写，子查询去关联化、算子下推
- **CBO（Cost-Based Optimizer）**：统计信息 + 基数估计 + 代价模型 + 搜索引擎，枚举计划选最优

### 2. 下推策略
目标：让计算尽量靠近存储层，减少网络传输、提前过滤、并行加速。
- Filter/Project 下推：提前过滤行和列
- Limit/Sort 下推：拆分为 MergeSort + LocalSort
- Agg 下推：拆分为 FinalAgg + LocalAgg（两阶段聚合，AVG → SUM + COUNT）
- JOIN 下推：条件 — 拆分方式一致 + JOIN条件含拆分键等值关系
- 子查询下推：先转 Semi/Anti Join → 判断 JOIN 下推条件 → 下推后还原

### 3. JOIN 算法选择
| 算法 | 适用场景 |
|------|---------|
| NLJoin | 非等值 JOIN |
| HashJoin | 大部分等值 JOIN（内表建哈希表） |
| BKAJoin | 外表小、内表大（批量 Lookup） |
| SortMergeJoin | 数据倾斜 / 输入已有序 |

### 4. 聚合算子
| 算子 | 适用场景 |
|------|---------|
| HashAgg | 大部分场景首选 |
| SortAgg | 内存不足 / 输入已排序 / 数据倾斜 |

### 5. 排序算子
| 算子 | 适用场景 |
|------|---------|
| MemSort | 通用排序 |
| TopN | ORDER BY + LIMIT（维护堆） |
| MergeSort | 排序下推到存储层，上层归并 |

## 回答格式要求
1) 结论 1~2 句
2) 分阶段解释（RBO 改写 → CBO 枚举 → 执行器选择）
3) 给一个具体优化流程示例
4) 关联 mini-db 项目已有实现（如适用）

## mini-db 项目已有实现
- `PushFilterIntoScanRule`: Filter 下推到 Scan
- `FilterJoinPushdownRule`: Filter 下推到 JOIN
- `FilterProjectTransposeRule`: Filter 与 Project 交换
- `JoinCommuteRule`: JOIN 交换律
- `RuleOptimizer`: RBO 规则优化器
- `CostModel` / `CostOptimizer`: CBO 代价模型

## 后续可实现方向
1. Agg 下推 / 两阶段聚合
2. Sort 下推 / MergeSort
3. 子查询去关联化（Unnesting → SemiJoin）
4. JOIN 顺序枚举（Bushy / Left-Deep）
5. JOIN 算法选择（Hash/NL/SortMerge/Lookup）

## 资源
- 详细参考资料：reference.md
- 来源文档：`docs/sql_优化/` 目录（共 7 篇）
