# 扩展作用域解析功能说明

## 概述

本文档说明了在 Apache Calcite 标准的基础上，对作用域解析功能进行的扩展，主要增加了对 VALUES、JOIN、TABLE_FUNCTION 等类型的完整支持。

## 新增功能

### 1. VALUES 子句支持 (`ValuesNamespace`)

**功能描述：**
- 支持 VALUES 子句的作用域解析和类型推导
- 自动从值列表推导列的数量和类型
- 支持多行数据的类型合并和兼容性检查

**使用场景：**
```sql
-- 支持的 VALUES 语法
VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Charlie')

-- 在 FROM 子句中使用
SELECT * FROM (VALUES (1, 'Alice'), (2, 'Bob')) AS t(id, name)

-- 在 INSERT 语句中使用
INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')
```

**核心特性：**
- **类型推导**：自动推导每列的最佳数据类型
- **类型合并**：处理同一列不同行之间的类型兼容性
- **验证检查**：确保所有行具有相同的列数
- **别名支持**：支持 VALUES 子句的别名

### 2. 表值函数支持 (`TableFunctionNamespace`)

**功能描述：**
- 支持标准表值函数的作用域解析
- 函数签名验证和参数类型检查
- 返回结果的列结构推导

**支持的函数：**
```sql
-- GENERATE_SERIES: 生成数字序列
SELECT * FROM TABLE(GENERATE_SERIES(1, 10)) AS t(value)

-- UNNEST: 展开数组
SELECT * FROM TABLE(UNNEST(ARRAY[1,2,3,4,5])) AS t(value)

-- JSON_TABLE: JSON 数据表格化
SELECT * FROM TABLE(JSON_TABLE('{"a": 1, "b": 2}', '$' 
  COLUMNS(a INT PATH '$.a', b INT PATH '$.b'))) AS t

-- STRING_SPLIT: 字符串分割
SELECT * FROM TABLE(STRING_SPLIT('a,b,c', ',')) AS t(value)
```

**核心特性：**
- **函数注册**：预定义的表值函数库
- **签名验证**：参数数量和类型检查
- **结果推导**：根据函数类型推导返回列结构
- **扩展性**：易于添加新的表值函数

### 3. JOIN 操作增强 (`JoinNamespace`)

**功能描述：**
- 完整的 JOIN 作用域解析和列合并
- 支持所有 JOIN 类型的语义处理
- 处理列名冲突和可空性

**支持的 JOIN 类型：**
```sql
-- INNER JOIN
SELECT * FROM users u INNER JOIN orders o ON u.id = o.user_id

-- LEFT JOIN (右表列可为空)
SELECT * FROM users u LEFT JOIN orders o ON u.id = o.user_id

-- RIGHT JOIN (左表列可为空)
SELECT * FROM users u RIGHT JOIN orders o ON u.id = o.user_id
```

**核心特性：**
- **列合并**：智能合并左右表的列结构
- **冲突处理**：自动处理同名列的冲突
- **可空性**：根据 JOIN 类型设置列的可空属性
- **限定查找**：支持表名限定的列引用

## 架构设计

### 命名空间层次结构

```
SqlValidatorNamespace (接口)
├── TableNamespace          // 普通表
├── SelectNamespace         // SELECT 查询结果
├── SubqueryNamespace       // 子查询
├── ValuesNamespace         // VALUES 子句 ✓ 新增
├── TableFunctionNamespace  // 表值函数 ✓ 新增
└── JoinNamespace          // JOIN 结果 ✓ 新增
```

### 作用域解析流程

```
1. 解析 SQL 语句 → AST
2. 注册查询 (registerQuery)
   ├── 注册 FROM 子句 (registerFrom)
   │   ├── 注册表引用 (registerTableReference) ✓ 扩展
   │   └── 注册 JOIN 操作 (registerJoinOperation) ✓ 新增
   ├── 创建命名空间
   │   ├── VALUES → ValuesNamespace ✓ 新增
   │   ├── TABLE_FUNCTION → TableFunctionNamespace ✓ 新增
   │   └── JOIN → JoinNamespace ✓ 新增
   └── 建立作用域映射
3. 基础验证 (validateQueryStructure)
4. 返回根作用域
```

## 类型推导系统

### VALUES 类型推导

```java
// 数值类型优先级：DOUBLE > FLOAT > BIGINT > INT
VALUES (1, 2.5, 'text')  →  Column1: DOUBLE, Column2: DOUBLE, Column3: VARCHAR

// 字符串类型优先级：TEXT > VARCHAR > CHAR
VALUES ('a', 'hello world')  →  Column1: VARCHAR, Column2: VARCHAR
```

### 表值函数类型映射

```java
GENERATE_SERIES(start, end [, step])  →  value: BIGINT
UNNEST(array)                        →  value: VARCHAR (可扩展)
JSON_TABLE(json, path COLUMNS(...))  →  根据 COLUMNS 定义
STRING_SPLIT(string, delimiter)      →  value: VARCHAR
```

### JOIN 列合并策略

```java
// 列名冲突处理
users.id + orders.id  →  id, orders_id

// 可空性处理
LEFT JOIN:  右表列设为 nullable=true
RIGHT JOIN: 左表列设为 nullable=true
INNER JOIN: 保持原有可空性
```

## 使用示例

### 完整的查询解析

```java
// 创建作用域解析器
SemanticAnalyzerScopeUtil scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);

// 解析复杂查询
String sql = """
    SELECT u.name, o.amount, v.score
    FROM users u
    LEFT JOIN orders o ON u.id = o.user_id
    CROSS JOIN (VALUES (1, 95), (2, 87), (3, 92)) AS v(id, score)
    WHERE u.id IN (SELECT value FROM TABLE(GENERATE_SERIES(1, 10)))
    """;

// 进行作用域解析
SqlValidatorScope rootScope = scopeUtil.analyzeScopes(statement, null);

// 查看解析结果
for (String nsName : rootScope.getNamespaces().keySet()) {
    SqlValidatorNamespace ns = rootScope.findNamespace(nsName);
    System.out.println("Namespace: " + nsName + " (Type: " + ns.getType() + ")");
}
```

## 扩展指南

### 添加新的表值函数

```java
// 1. 在 TableFunctionNamespace 中添加函数签名
functions.put("MY_FUNCTION", new TableFunctionSignature(
    "MY_FUNCTION", 2, 3, 1, "VARCHAR"
));

// 2. 实现列推导逻辑
case "MY_FUNCTION":
    deriveMyFunctionColumns();
    break;

// 3. 实现具体的列推导方法
private void deriveMyFunctionColumns() {
    Column resultColumn = new Column("result", "VARCHAR");
    columns.add(resultColumn);
    columnMap.put("result", resultColumn);
}
```

### 扩展 VALUES 类型支持

```java
// 在 ValuesNamespace.inferExpressionType() 中添加新类型处理
if (expr instanceof MyCustomExpression) {
    return "MY_CUSTOM_TYPE";
}

// 在 mergeTypes() 中添加类型合并规则
if (isMyCustomType(type1) || isMyCustomType(type2)) {
    return "MY_CUSTOM_TYPE";
}
```

## 注意事项

1. **性能考虑**：大型 VALUES 子句可能影响类型推导性能
2. **内存使用**：JOIN 操作会复制列结构，注意内存使用
3. **兼容性**：确保与现有语义分析器的兼容性
4. **扩展性**：新增功能遵循 Apache Calcite 的设计原则

## 总结

通过这次扩展，我们的作用域解析器现在完全支持：

- ✅ **VALUES 子句**：完整的类型推导和验证
- ✅ **表值函数**：标准函数库和扩展机制
- ✅ **JOIN 操作**：完整的列合并和作用域处理
- ✅ **复合查询**：多种数据源的组合查询

这些功能的实现严格遵循 Apache Calcite 的设计模式，确保了系统的一致性和可扩展性。 