# SQL 优化详细参考（基于 PolarDB-X 文档）

来源: `docs/sql_优化/` 目录，共 7 篇文档。

---

## 1. 查询优化器总览

SQL 执行流程: Parser(AST) → 逻辑计划 → Optimizer(物理计划) → Executor(结果)

核心关系代数算子:
- **Project**: SELECT 列 + 函数计算
- **Filter**: WHERE 条件
- **JOIN**: HashJoin / BKAJoin / NLJoin / SortMergeJoin
- **Agg**: HashAgg / SortAgg
- **Sort**: TopN / MemSort / MergeSort

两阶段优化:
- **RBO (Rule-Based Optimizer)**: 查询改写阶段，应用启发式规则
  - 子查询去关联化 (Unnesting)
  - 算子下推 (Filter/Project/Join/Agg/Sort)
- **CBO (Cost-Based Optimizer)**: 计划枚举阶段，基于代价选最优计划
  - 核心组件: 统计信息、基数估计、转化规则、代价模型、搜索引擎

---

## 2. 查询改写与下推

下推目标: 让计算尽量靠近存储层，减少网络传输、提前过滤、并行加速。

可下推的计算:
- **Filter/Project 下推**: 提前过滤行和列
- **Limit/Sort 下推**: 拆分为 MergeSort + LocalSort，减少内存占用
- **Agg 下推**: 拆分为 FinalAgg + LocalAgg (两阶段聚合)
- **JOIN 下推**: 条件 — 拆分方式一致 + JOIN条件含拆分键等值关系；广播表总可下推
- **JoinClustering**: 多表JOIN时重排序，将可下推的JOIN放到相邻位置
- **子查询下推**: 先转 Semi/Anti Join → 判断是否满足JOIN下推条件 → 下推后还原为子查询

---

## 3. 查询执行器

执行模型: **Pull-Push 混合模型**
- Pipeline 内部: next() 接口按批拉取 (Pull)
- Pipeline 之间: push 接口推送数据
- 按是否需要缓存临时表切分 pipeline

三种执行模式:
- **TP_LOCAL**: 单机单线程，适合点查等 TP 负载
- **AP_LOCAL**: 单机并行 (Parallel Query)，利用多核加速 AP 负载
- **MPP**: 多机并行，协调多个只读实例节点

---

## 4. JOIN 优化和执行

### JOIN 类型
- Inner Join / Left Outer Join / Right Outer Join
- Semi Join (IN/EXISTS 子查询转换) / Anti Join (NOT IN/NOT EXISTS)

### JOIN 算法选择
| 算法 | 适用场景 | 原理 |
|------|---------|------|
| **NLJoin** | 非等值JOIN | 内表全缓存，外表逐行匹配 |
| **HashJoin** | 大部分等值JOIN | 内表(右表/小表)建哈希表，外表探测 |
| **BKAJoin (Lookup)** | 外表小、内表大 | 外表批量取 JOIN Key → IN(...) 查内表 |
| **SortMergeJoin** | 数据倾斜 / 输入已有序 | 双路归并，需输入按 JOIN Key 排序 |

### JOIN 顺序枚举
- N 较小: Bushy 枚举 (全搜索)
- N 较大: Zig-Zag 或 Left-Deep 策略 (减少搜索空间)
- CBO 根据代价选最优顺序，同时考虑算法对左右输入的偏好

---

## 5. 子查询优化和执行

分类:
- **非关联子查询**: 不依赖外层变量，只需计算一次
- **关联子查询**: 含外层引用变量，逻辑上需多次计算

优化策略 — 去关联化 (Unnesting):
- 将关联子查询改写为 SemiJoin / AntiJoin / 普通 JOIN
- 避免循环迭代执行，大幅降低代价

无法去关联化的场景:
- 子查询条件中含 OR 等复杂逻辑 → 退化为迭代执行 (性能差)
- 建议: 改写 SQL 去掉阻碍去关联化的条件

---

## 6. 聚合优化和执行

支持的聚合函数: COUNT / SUM / AVG / MAX / MIN / BIT_OR / BIT_XOR / GROUP_CONCAT

### 聚合算子
| 算子 | 原理 | 适用场景 |
|------|------|---------|
| **HashAgg** | 哈希表分组聚合 | 大部分场景首选 |
| **SortAgg** | 输入已排序，逐组聚合 | 内存不足 / 输入已排序 / 数据严重倾斜 |

### 两阶段聚合优化
- 拆分为 PartialAgg (下推到存储层) + FinalAgg (计算层汇总)
- AVG 拆分为 SUM + COUNT 实现两阶段计算
- 大幅减少网络传输量

---

## 7. 排序优化和执行

### 排序算子
| 算子 | 原理 | 适用场景 |
|------|------|---------|
| **MemSort** | 内存快速排序 | 通用排序，无法下推时使用 |
| **TopN** | ORDER BY + LIMIT → 维护最大/最小堆(N个元素) | 只需前N条结果 |
| **MergeSort** | 排序下推到存储层，上层只做归并 | 可下推时首选，减少内存消耗 |

优化策略:
- 尽量将 Sort 下推到存储层，上层只做 MergeSort 归并
- ORDER BY + LIMIT 合并为 TopN，避免全量排序
