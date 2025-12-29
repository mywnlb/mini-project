# Apache Calcite 中 SQL 语义分析的 Scope 与 Namespace 解析

本文详细分析了在 Apache Calcite 中，一条复杂的 SQL 查询在语义分析（Validation）阶段是如何生成和使用 Scope (作用域) 与 Namespace (命名空间) 的。

## 核心概念

在深入分析之前，我们先理解两个核心概念：

* **`Namespace` (命名空间)**: `SqlValidatorNamespace` 的实例。它代表了一个可以提供结构化数据（即行）的实体。简单来说，一个 Namespace 就是一个“数据源”，它可以是一个表、一个视图、一个子查询，甚至是 `GROUP BY` 或 `ORDER BY` 子句的结果。它的主要职责是告诉 Calcite “我能提供哪些列？”。
* **`Scope` (作用域)**: `SqlValidatorScope` 的实例。它定义了在 SQL 的某个特定部分，哪些标识符（表名、列名、别名）是可见和可被解析的。Scope 呈层次结构，子 Scope 可以访问父 Scope 中定义的名称。它的主要职责是回答“在这个地方，这个名字（比如 `a.department_id`）代表什么？”。

---

## 待分析的 SQL 语句

我们将以下面的 SQL 语句为例进行分步解析：

```sql
SELECT
    a.department_id,
    COUNT(*) AS countvalue,
    SUM(a.salary) AS total_salary
FROM
    employees a
JOIN
    departments d ON a.department_id = d.id
WHERE
    a.salary > 5000 AND d.location IN ('Beijing', 'Shanghai')
GROUP BY
    a.department_id
HAVING
    COUNT(*) > 5
ORDER BY
    total_salary DESC
LIMIT 10

```

Scope 和 Namespace 的构建过程（自底向上）
Calcite 的语义分析是一个从 FROM 子句开始，逐步向上构建和验证的过程。

1. FROM 子句
   这是所有 Scope 和 Namespace 的起点。

FROM employees a:
Calcite 会为 employees 表创建一个 TableNamespace。
同时，它会创建一个 IdentifierNamespace，代表别名 a，并将其指向 employees 的 TableNamespace。
JOIN departments d ON a.department_id = d.id:
与上面类似，为 departments 表创建 TableNamespace，并为别名 d 创建 IdentifierNamespace。
JOIN 操作会创建一个 JoinNamespace。这个 JoinNamespace 包含了左右两个子 Namespace（即代表 a 和 d 的 Namespace）。它向外暴露的列是 a 和 d 的所有列的并集。
处理完整个 FROM 子句后，会形成一个 FromScope。这个 Scope 是最基础的，它包含了所有在 FROM 中定义的表别名（a 和 d）。

ON a.department_id = d.id: ON 条件的表达式是在这个 FromScope 中进行验证的。Calcite 可以在这个 Scope 中成功解析出 a.department_id 和 d.id。
2. WHERE 子句
   WHERE a.salary > 5000 AND d.location IN (...):
   WHERE 子句的验证发生在一个新的 WhereScope 中。
   WhereScope 是 FromScope 的直接子 Scope。因此，它继承了 FromScope 的所有可见名称（a 和 d）。
   重要: 在 WHERE 子句中，不可以引用 SELECT 列表中的别名（如 countvalue 或 total_salary），因为 SELECT 列表的 Namespace 此时还未创建。
3. GROUP BY 子句
   GROUP BY a.department_id:
   GROUP BY 子句的验证会创建一个 GroupByScope。
   GroupByScope 的父 Scope 是 FromScope（注意，不是 WhereScope）。它和 WhereScope 是兄弟关系。
   这个子句本身会生成一个非常重要的 GroupByNamespace。这个新的 Namespace 的行类型（Row Type）不再是原始表的列，而是 GROUP BY 的键（这里是 a.department_id）。这个 Namespace 是后续 SELECT 和 HAVING 子句中非聚合表达式的基础。
4. HAVING 子句
   HAVING COUNT(*) > 5:
   HAVING 子句的验证发生在一个 HavingScope 中，其父 Scope 是 GroupByScope。
   在 HavingScope 中，你可以引用：
   GROUP BY 的键: 来自 GroupByNamespace。
   聚合函数: 例如 COUNT(*), SUM(a.salary) 等。
   同样，HAVING 子句不能引用 SELECT 列表中的别名。
5. SELECT 子句（投影列表）
   SELECT a.department_id, COUNT(*) AS countvalue, ...:
   Calcite 会创建一个 SelectScope，其父 Scope 是 GroupByScope。
   SELECT 列表中的表达式都在 SelectScope 中进行验证。
   a.department_id: 这是一个 GROUP BY 的键，在 GroupByNamespace 中是有效的，解析成功。
   COUNT(*) 和 SUM(a.salary): 这是聚合函数，它们的参数 a.salary 在 SelectScope 中可以找到。
   处理完 SELECT 列表后，会创建一个 SelectNamespace。这个 Namespace 的行类型由 SELECT 列表定义，即包含三列：department_id, countvalue, total_salary。
6. ORDER BY 子句
   ORDER BY total_salary DESC:
   为了验证 ORDER BY，Calcite 会创建一个 OrderByScope。
   它的 Namespace 是 SelectNamespace。这意味着 ORDER BY 子句可以看到并使用 SELECT 列表中定义的所有列，包括它们的别名。
   因此，total_salary 在这里可以被成功解析。
   ORDER BY 子句本身也会创建一个 OrderNamespace。
7. LIMIT (FETCH/OFFSET) 子句
   LIMIT 10: LIMIT 子句的验证在 OrderByScope 之后进行，它不会引入新的 Scope 或 Namespace，而是作用于 OrderNamespace 的结果之上。


总结
Scope 层次结构 (简化版)
```plaintext
+------------------+
                      |   OrderByScope   |  (可以解析 total_salary)
                      +------------------+
                              | (父)
                      +------------------+
                      |   SelectScope    |  (可以解析 a.department_id, a.salary)
                      +------------------+
                              | (父)
                      +------------------+
                      |   HavingScope    |  (可以解析 a.department_id, 聚合函数)
                      +------------------+
                              | (父)
                      +------------------+
                      |   GroupByScope   |  (可以解析 a.department_id)
                      +------------------+
                              | (父)
+-----------------+   +------------------+
|   WhereScope    |---|     FromScope    |  (可以解析 a, d, a.column, d.column)
+-----------------+   +------------------+
     (兄弟)
```

Namespace 演进链
TableNamespace (employees) & TableNamespace (departments)

列：各自表的所有列
JoinNamespace

列：employees 和 departments 的列并集
GroupByNamespace

列：a.department_id (分组键)
SelectNamespace

列：department_id, countvalue, total_salary
OrderNamespace

列：与 SelectNamespace 相同，但附加了排序规则。


正确的层次结构应该是：

FromScope (由 FROM 子句创建，包含所有表和别名)
WhereScope (父级是 FromScope，用于验证 WHERE 表达式)
GroupByScope (父级也是 FromScope，用于验证 GROUP BY 表达式)
HavingScope (父级是 GroupByScope，用于验证 HAVING 表达式)
SelectScope (父级也是 GroupByScope，用于验证 SELECT 列表中的表达式)
OrderByScope (父级是 SelectScope，因为 ORDER BY 可以使用 SELECT 的别名)