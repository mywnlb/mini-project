# analyzeScopes 方法详解：Scope 和 Namespace 的构建逻辑

## 🎯 analyzeScopes 方法的整体逻辑

`analyzeScopes` 方法是语义分析的**第一阶段**，负责为 SQL 语句构建**作用域（Scope）**和**命名空间（Namespace）**的层次结构。这是 Apache Calcite 中 `SqlValidatorImpl.registerQuery()` 的核心实现。

参考：[Apache Calcite语义分析详解](https://zhuanlan.zhihu.com/p/58139279)中的registerQuery阶段描述。

### 🧠 核心思想

**analyzeScopes 就像是为 SQL 语句建立一个"地图索引系统"**：

1. **🗺️ 建立地图**：为每个 SQL 子句创建作用域（Scope），就像划分不同的区域
2. **📍 标记位置**：为每个数据源创建命名空间（Namespace），就像在地图上标记重要地点
3. **🔗 建立联系**：通过 IdentityMapping 将 AST 节点与 Scope/Namespace 关联起来
4. **🎯 提供导航**：为后续的名称解析、类型推导等提供精确的"导航服务"

### 📋 两阶段处理流程

```java
public SqlValidatorScope analyzeScopes(SQLStatement statement, SqlValidatorScope parentScope) {
    // 创建根作用域
    SqlValidatorScope rootScope = parentScope != null ? parentScope : new SqlValidatorScope();
    
    // 阶段1: 注册查询 - 构建scope和namespace (对应Calcite的registerQuery)
    registerQuery(rootScope, null, statement, statement, null, false);
    
    // 阶段2: 基础验证 - 仅做结构性验证 (对应Calcite的validateQuery基础部分)
    validateQueryStructure(statement, rootScope);
    
    return getScope(statement);
}
```

**为什么分两个阶段？**
- **阶段1**：专注于"地图绘制"，构建完整的作用域和命名空间结构
- **阶段2**：进行"地图验证"，确保构建的结构是合理和完整的

## 🏗️ registerQuery 核心实现

### 1. IdentityMapping 机制

```java
public class SemanticAnalyzerScopeUtil {
    // IdentityMapping: 维护AST节点到Scope/Namespace的映射
    private final IdentityHashMap<SQLStatement, SqlValidatorScope> scopes = new IdentityHashMap<>();
    private final IdentityHashMap<SQLStatement, SqlValidatorNamespace> namespaces = new IdentityHashMap<>();
}
```

**这就像是为每个 AST 节点发放"身份证"**：
- 每个 SQL 语句节点都有唯一的 Scope（它的"活动范围"）
- 每个 SQL 语句节点都有对应的 Namespace（它能"看到的数据"）

### 2. 关键特性

#### **IdentityMapping机制**
- 使用 `IdentityHashMap` 维护AST节点的对象身份映射
- 确保每个AST节点对应唯一的Scope/Namespace
- 支持快速查找和避免重复注册

#### **递归处理**
- 自动处理嵌套子查询
- 递归构建作用域层次结构
- 正确处理作用域的继承关系

#### **AST改写**
- `from tbl => from tbl as tbl`：自动添加表别名
- 标准化表引用格式
- 为后续验证阶段准备标准化的AST

### 3. 核心处理逻辑

```java
/**
 * 注册查询 - 完全按照Calcite SqlValidatorImpl.registerQuery()实现
 * 
 * 这是Calcite中最核心的方法，负责：
 * 1. 创建正确的作用域层次结构
 * 2. 为每个节点注册对应的namespace
 * 3. 处理表引用和子查询的递归注册
 * 4. 建立IdentityMapping关系
 */
protected void registerQuery(
    SqlValidatorScope parentScope,
    SqlValidatorScope usingScope,
    SQLStatement node,
    SQLStatement enclosingNode,
    String alias,
    boolean forceNullable) throws SemanticException {
    
    // 检查是否已经注册过（避免重复注册）
    if (scopes.containsKey(node)) {
        return;
    }
    
    // 按照语句类型分发注册逻辑
    switch (node.getType()) {
        case SELECT:
            registerSelect(parentScope, usingScope, (SelectStatement) node, enclosingNode, alias, forceNullable);
            break;
        // ... 其他类型
    }
}
```

## 🔧 Scope（作用域）- SQL的"眼界"

### 💡 Scope 是什么？

**Scope 就像是人的"眼界"或"视野"** - 它决定了在 SQL 的特定位置能"看到"哪些表和列。

想象你站在不同的楼层看风景：
- 🏠 **1楼（FROM作用域）**：只能看到直接的表和别名
- 🏢 **2楼（SELECT作用域）**：可以看到FROM中的表，还能看到一些计算列
- 🏗️ **3楼（子查询作用域）**：不仅能看到自己的表，还能"透视"到外层查询的表

### 🏗️ Scope 的层次结构

```
TOP_LEVEL (顶级作用域) - 就像"宇宙视角"
├── SELECT (SELECT 作用域) - "主查询视角"
│   ├── FROM (FROM 作用域) - "表级视角"
│   ├── WHERE (WHERE 作用域) - "过滤视角"
│   ├── GROUP_BY (GROUP BY 作用域) - "分组视角"
│   ├── HAVING (HAVING 作用域) - "分组过滤视角"
│   └── ORDER_BY (ORDER BY 作用域) - "排序视角"
└── SUBQUERY (子查询作用域) - "嵌套视角"
```

### 🎯 Scope 的核心功能

```java
public class SqlValidatorScope {
    // 作用域类型
    public enum ScopeType {
        TOP_LEVEL, SELECT, FROM, WHERE, GROUP_BY, HAVING, ORDER_BY, SUBQUERY
    }
    
    // 核心功能
    public void addNamespace(String name, SqlValidatorNamespace namespace);
    public Column findColumn(String tableName, String columnName);
    public SqlValidatorScope createChildScope(ScopeType scopeType);
}
```

**主要功能：**
1. **🎯 名称解析**：`scope.findColumn("tableName", "columnName")` - 就像"寻人启事"
2. **🏷️ 别名管理**：处理表别名和列别名 - 就像"花名册"
3. **👁️ 可见性控制**：决定哪些表和列在当前位置可见 - 就像"权限管理"
4. **⚠️ 歧义检测**：发现重复的列名引用 - 就像"重名检查"

## 📦 Namespace（命名空间）- 数据的"身份证"

### 💡 Namespace 是什么？

**Namespace 就像是数据源的"身份证档案"** - 它包含了该数据源的所有详细信息。

就像一个人的身份证包含：姓名、性别、年龄、地址等信息，Namespace 包含：
- 📛 **名称**：表名或别名
- 🏷️ **类型**：是表、视图、还是子查询结果
- 📋 **列清单**：包含哪些列，每列的类型是什么
- 🔍 **查找功能**：快速找到指定的列

### 🗂️ Namespace 的接口设计

```java
public interface SqlValidatorNamespace {
    enum NamespaceType {
        TABLE,          // 普通表：就像"原装商品"
        VIEW,           // 视图：就像"精装版商品"
        SELECT,         // SELECT 查询结果：就像"定制商品"
        SUBQUERY,       // 子查询：就像"套装商品"
        TABLE_FUNCTION, // 表值函数：就像"智能商品"
        VALUES,         // VALUES 子句：就像"样品商品"
        JOIN,           // JOIN 结果：就像"组合商品"
        UNION,          // UNION 结果：就像"合并商品"
        WITH            // WITH 子句：就像"预制商品"
    }
    
    String getName();                    // 获取"商品名称"
    NamespaceType getType();            // 获取"商品类型"
    Column findColumn(String columnName); // 查找"商品属性"
    List<Column> getColumns();          // 获取"商品清单"
    boolean hasColumn(String columnName); // 检查"是否有某属性"
    int getColumnCount();               // 获取"属性数量"
    void validate() throws SemanticException; // "质量检查"
}
```

### 🏭 Namespace 的具体实现

#### **TableNamespace - 表命名空间**
```java
public class TableNamespace implements SqlValidatorNamespace {
    private final Table table;
    private final String alias;
    
    // 就像"原厂说明书" - 直接从表元数据获取列信息
    // 支持别名处理 - 就像"产品别名"
}
```

#### **SelectNamespace - SELECT结果命名空间**
```java
public class SelectNamespace implements SqlValidatorNamespace {
    private final SelectStatement select;
    
    // 就像"定制说明书" - 从SELECT项推导列信息
    // 处理别名和表达式列名 - 就像"个性化定制"
}
```

#### **SubqueryNamespace - 子查询命名空间**
```java
public class SubqueryNamespace implements SqlValidatorNamespace {
    private final SelectStatement subquery;
    private final String alias;
    
    // 就像"套装说明书" - 递归处理子查询结果
    // 必须有别名 - 就像"套装必须有品牌名"
}
```

### 🛠️ Namespace 的核心功能

1. **📋 列信息管理**：存储列名、类型、约束等 - 就像"商品规格表"
2. **🔍 类型推导**：为表达式提供类型信息 - 就像"兼容性检查"
3. **⭐ 星号展开**：`SELECT *` 时获取所有列 - 就像"全套商品展示"
4. **🛡️ 权限检查**：验证列访问权限 - 就像"访问控制"

## 🌰 具体示例：从零开始理解

让我们通过一个完整的例子来看看 `analyzeScopes` 是如何工作的：

### 📝 SQL 示例

```sql
SELECT u.name, u.email, o.amount, 
       (SELECT COUNT(*) FROM orders WHERE user_id = u.id) as order_count
FROM users u 
JOIN orders o ON u.id = o.user_id 
WHERE u.age > 18 AND o.status = 'COMPLETED'
ORDER BY o.amount DESC
```

**这个SQL的复杂之处：**
- 🔗 **表连接**：users 和 orders 两个表
- 🏷️ **表别名**：u 和 o
- 🔍 **子查询**：嵌套的 COUNT 查询
- 🌐 **作用域穿透**：子查询引用外层的 u.id

### 🔍 Step-by-Step 分析过程

#### 1️⃣ **创建根作用域 - "搭建舞台"**

```java
SqlValidatorScope rootScope = new SqlValidatorScope(null, ScopeType.TOP_LEVEL);
```

**就像搭建一个空舞台：**
```
🎭 rootScope (TOP_LEVEL)
├── 演员名单: {}
└── 舞台布景: []
```

#### 2️⃣ **注册主查询 - "安排主角登场"**

```java
// 1. 创建 SELECT 作用域 - "主舞台"
SqlValidatorScope selectScope = new SqlValidatorScope(rootScope, ScopeType.SELECT);

// 2. 注册 FROM 子句 - "安排演员入场"
SqlValidatorScope fromScope = registerFrom(selectScope, fromList, select);
```

**FROM 子句处理流程：**

```java
/**
 * 注册FROM子句 - 就像"演员签到"
 */
private SqlValidatorScope registerFrom(
    SqlValidatorScope selectScope,
    List<SelectStatement.TableReference> fromList,
    SelectStatement select) throws SemanticException {
    
    // 1. 创建FromScope - "演员休息区"
    SqlValidatorScope fromScope = new SqlValidatorScope(selectScope, SqlValidatorScope.ScopeType.FROM);
    
    // 2. 处理FROM子句中的每个表引用 - "登记每个演员"
    for (SelectStatement.TableReference tableRef : fromList) {
        registerTableReference(fromScope, tableRef, select);
    }
    
    // 3. 处理JOIN子句 - "安排演员搭档"
    if (select.getJoins() != null) {
        for (SelectStatement.JoinClause join : select.getJoins()) {
            registerTableReference(fromScope, join.getJoinTable(), select);
            registerJoinCondition(fromScope, join);
        }
    }
    
    return fromScope;
}
```

**注册表引用 - "给演员分配角色"：**

```java
// 注册 users 表 - "主角登场"
registerTableReference(fromScope, "users u", select);
// 创建 TableNamespace(users, "u") - "给主角制作身份牌"
// 添加到 fromScope: {"u" -> TableNamespace(users)} - "主角入座"

// 注册 orders 表 (通过 JOIN) - "配角登场"
registerTableReference(fromScope, "orders o", select);  
// 创建 TableNamespace(orders, "o") - "给配角制作身份牌"
// 添加到 fromScope: {"o" -> TableNamespace(orders)} - "配角入座"
```

**当前作用域结构 - "舞台全景"：**

```
🎭 rootScope (TOP_LEVEL) - "剧院"
└── 🎪 selectScope (SELECT) - "主舞台"
    └── 🎨 fromScope (FROM) - "演员席"
        ├── 🎭 "u" -> TableNamespace(users) - "主角u的档案"
        │   ├── id: INTEGER      - "年龄"
        │   ├── name: VARCHAR    - "姓名"
        │   ├── age: INTEGER     - "年龄"
        │   └── email: VARCHAR   - "联系方式"
        └── 🎭 "o" -> TableNamespace(orders) - "配角o的档案"
            ├── id: INTEGER      - "订单号"
            ├── user_id: INTEGER - "关联用户"
            ├── amount: DECIMAL  - "金额"
            └── status: VARCHAR  - "状态"
```

#### 3️⃣ **注册子查询 - "安排客串演员"**

```java
// 发现子查询: (SELECT COUNT(*) FROM orders WHERE user_id = u.id)
SqlValidatorScope subqueryScope = new SqlValidatorScope(selectScope, ScopeType.SUBQUERY);

// 递归注册子查询 - "客串演员也要登记"
registerQuery(subqueryScope, null, subquery, enclosingSelect, tableRef.getAlias(), false);
```

**子查询处理 - "小舞台搭建"：**

```
🎪 selectScope (SELECT) - "主舞台"
├── 🎨 fromScope (FROM) - "主演员席"
│   ├── 🎭 "u" -> TableNamespace(users) - "主角档案"
│   └── 🎭 "o" -> TableNamespace(orders) - "配角档案"
└── 🎬 subqueryScope (SUBQUERY) - "客串小舞台"
    └── 🎨 subqueryFromScope (FROM) - "客串演员席"
        └── 🎭 "orders" -> TableNamespace(orders) - "客串演员档案"
```

#### 4️⃣ **构建完整的映射关系 - "演员花名册"**

**scopes 映射 - "舞台分配表"：**
```java
IdentityHashMap<SQLStatement, SqlValidatorScope> scopes = {
    mainSelect -> selectScope,    // "主剧本 -> 主舞台"
    subquery -> subqueryScope     // "客串剧本 -> 小舞台"
}
```

**namespaces 映射 - "演员身份档案"：**
```java
IdentityHashMap<SQLStatement, SqlValidatorNamespace> namespaces = {
    mainSelect -> SelectNamespace(mainSelect),  // "主剧本的演员阵容"
    subquery -> SelectNamespace(subquery)       // "客串剧本的演员阵容"
}
```

## 🎪 名称解析的魔法 - "找人"系统

现在我们有了完整的"舞台布局"和"演员花名册"，可以进行名称解析了！

### 🔍 示例 1: 解析 `u.name` - "找主角的姓名"

```java
SqlValidatorScope selectScope = scopes.get(mainSelect);
Column column = selectScope.findColumn("u", "name");

// 解析过程就像"找人"：
// 1. 在 selectScope(主舞台) 中找"u"演员 -> 没找到
// 2. 去 fromScope(演员席) 中找"u"演员 -> 找到了！
// 3. 在 TableNamespace(users) 中找"name"属性 -> 找到了！
// 4. 返回 Column(name, VARCHAR) -> "找到了u演员的姓名属性"
```

**过程图解：**
```
🔍 查找 "u.name"
├── 📍 在 selectScope 中找 "u" ❌
├── 📍 在 fromScope 中找 "u" ✅ -> TableNamespace(users)
├── 📍 在 users 表中找 "name" ✅ -> Column(name, VARCHAR)
└── 🎯 返回结果: name(VARCHAR)
```

### 🔍 示例 2: 解析子查询中的 `u.id` - "客串演员找主角"

```java
SqlValidatorScope subqueryScope = scopes.get(subquery);
Column column = subqueryScope.findColumn("u", "id");

// 解析过程就像"跨舞台找人"：
// 1. 在 subqueryScope(小舞台) 中找"u" -> 没找到
// 2. 查找父作用域 selectScope(主舞台) -> 没直接找到"u"
// 3. 查找父作用域的 fromScope(演员席) -> 找到了"u"！
// 4. 在 TableNamespace(users) 中找"id"列 -> 找到了！
// 5. 返回 Column(id, INTEGER) -> "跨舞台成功找到主角的id"
```

**这就是"作用域链"的威力！** 就像客串演员可以"看到"主舞台的主角。

**过程图解：**
```
🔍 子查询中查找 "u.id"
├── 📍 在 subqueryScope 中找 "u" ❌
├── 📍 向上查找父作用域 selectScope ❌
├── 📍 继续向上查找 fromScope ✅ -> 找到 "u"
├── 📍 在 TableNamespace(users) 中找 "id" ✅
└── 🎯 通过作用域链成功找到: id(INTEGER)
```

### 🔍 示例 3: 歧义检测 - "重名问题"

```sql
-- 假设有歧义的情况
SELECT id FROM users u, orders o  -- 两个表都有 id 列
```

```java
Column column = scope.findColumn(null, "id");  // 非限定名查找

// 解析过程就像"找重名的人"：
// 1. 在所有 namespace 中查找 "id"
// 2. 发现 users.id 和 orders.id 都匹配 -> "有两个叫id的人！"
// 3. 抛出 SemanticException: "Column 'id' is ambiguous" -> "请明确指定是哪个id"
```

**过程图解：**
```
🔍 查找 "id" (无限定符)
├── 📍 在 users 中找到 "id" ✅
├── 📍 在 orders 中也找到 "id" ✅
├── ⚠️ 发现重名冲突！
└── 🚨 抛出异常: "列名'id'有歧义，请使用u.id或o.id明确指定"
```

## 🔄 registerQuery 流程详解

### 1. 基本SELECT语句

```java
// SQL: SELECT id, name FROM users WHERE age > 18
SqlValidatorScope scope = validator.registerQuery(statement);

// 流程：
// 1. 创建SELECT作用域 -> "搭建主舞台"
// 2. 注册FROM子句 -> "安排演员入场"
// 3. 注册表引用 -> "给演员制作身份牌"
// 4. 创建SelectNamespace -> "制作剧本说明书"
// 5. 建立映射关系 -> "完成花名册登记"
```

### 2. 子查询处理

```java
// SQL: SELECT u.name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) FROM users u
// 流程：
// 1. 注册主查询 -> "搭建主舞台，安排主角"
// 2. 在registerSubqueries中发现子查询 -> "发现需要客串演员"
// 3. 递归调用registerQuery处理子查询 -> "为客串演员搭建小舞台"
// 4. 为子查询创建独立的作用域和命名空间 -> "客串演员有自己的休息区"
// 5. 建立父子作用域关系 -> "确保客串演员能看到主角"
```

### 3. JOIN处理

```java
// SQL: SELECT u.name, p.title FROM users u JOIN posts p ON u.id = p.author_id
// 流程：
// 1. 在FROM作用域中注册左表（users u） -> "主角先入场"
// 2. 注册右表（posts p） -> "配角后入场"
// 3. 两个表的命名空间都添加到FROM作用域 -> "两位演员都坐在演员席"
// 4. 支持通过别名访问表和列 -> "可以用艺名叫他们"
```

### 4. UPDATE/DELETE处理

```java
// UPDATE users SET name = 'John' WHERE id = 1
// 流程：
// 1. 创建UPDATE作用域 -> "搭建编辑舞台"
// 2. 注册目标表 -> "安排要编辑的演员"
// 3. 如果有来自SqlRewriter的内嵌SELECT，递归注册 -> "如果有查询需求，再搭小舞台"
// 4. 维护一致的作用域结构 -> "保持舞台布局的一致性"
```

## 🔧 AST改写机制

### 1. 表引用标准化

```java
// 改写前：FROM users        -> "演员没有艺名"
// 改写后：FROM users AS users -> "给演员加上默认艺名"

private void performTableReferenceAstRewrite(SelectStatement.TableReference tableRef) {
    if (tableRef.getAlias() == null && !tableRef.isSubquery()) {
        String tableName = tableRef.getTableName();
        tableRef.setAlias(tableName);  // 添加默认别名 -> "用真名作艺名"
    }
}
```

### 2. 未来扩展点

```java
private void performSelectAstRewrite(SelectStatement select) {
    // TODO: 展开SELECT * -> "展开'全体演员'列表"
    // TODO: 标准化函数名 -> "统一'特技'名称"
    // TODO: 处理隐式类型转换 -> "演员'换装'处理"
}
```

## 🔄 实际运行流程

### 完整的执行序列

```java
// 1. 开始分析 -> "开始筹备演出"
SemanticAnalyzerScopeUtil scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);
SqlValidatorScope resultScope = scopeUtil.analyzeScopes(sqlStatement, null);

// 2. 内部执行：
//    a) registerQuery(rootScope, null, sqlStatement, sqlStatement, null, false)
//       └── registerSelect() -> "安排主演出"
//           ├── 创建 selectScope -> "搭建主舞台"
//           ├── registerFrom() -> "安排演员入场"
//           │   ├── 创建 fromScope -> "设置演员席"
//           │   ├── registerTableReference("users", "u") -> "主角u登记"
//           │   └── registerTableReference("orders", "o") -> "配角o登记"
//           ├── 创建 SelectNamespace -> "制作演出说明书"
//           ├── 建立映射: scopes.put(sqlStatement, selectScope) -> "完成舞台登记"
//           └── registerSubqueries() -> "安排客串演出"
//               └── 递归处理子查询 -> "为客串演员搭建小舞台"
//
//    b) validateQueryStructure() -> "检查舞台布置是否合理"

// 3. 获取结果 -> "演出筹备完成"
SqlValidatorScope selectScope = scopeUtil.getScope(sqlStatement);
SqlValidatorNamespace selectNamespace = scopeUtil.getNamespace(sqlStatement);
```

## 🎯 为什么需要 Scope 和 Namespace？

### 🔍 **名称解析的复杂性**

SQL 中的名称解析非常复杂，就像在一个大型演出中找人：

```sql
-- 1. 表别名 -> "用艺名找演员"
SELECT u.name FROM users u;

-- 2. 列别名 -> "用角色名找演员特征"
SELECT name as user_name FROM users;

-- 3. 子查询引用外部表 -> "客串演员要找主角"
SELECT (SELECT COUNT(*) FROM orders WHERE user_id = u.id) FROM users u;

-- 4. 多表连接 -> "多个演员同台演出"
SELECT * FROM users u JOIN orders o ON u.id = o.user_id;

-- 5. 复杂嵌套 -> "多层舞台，复杂关系"
WITH user_stats AS (
    SELECT user_id, COUNT(*) as order_count 
    FROM orders 
    GROUP BY user_id
)
SELECT u.name, s.order_count 
FROM users u 
JOIN user_stats s ON u.id = s.user_id;
```

### 🛡️ **类型安全**

```java
// 通过 Namespace 获取准确的类型信息 -> "确认演员的专业技能"
Column nameCol = namespace.findColumn("name");  // VARCHAR -> "文字表演"
Column ageCol = namespace.findColumn("age");    // INTEGER -> "数字表演"

// 类型兼容性检查 -> "确保演员搭配合理"
if (!isCompatible(nameCol.getType(), "INTEGER")) {
    throw new SemanticException("Type mismatch: VARCHAR vs INTEGER");
    // -> "文字演员不能演数字角色！"
}
```

### ⚡ **性能优化**

```java
// 使用 IdentityHashMap 提供 O(1) 查找 -> "快速找到演员"
// 避免重复解析同一个表达式 -> "不重复登记同一个演员"
private final IdentityHashMap<SQLStatement, SqlValidatorScope> scopes;
private final Map<String, Column> resolvedColumns; // 缓存已解析的列 -> "演员通讯录"
```

## 🏆 架构优势

### 1. **完全符合Apache Calcite标准**

- ✅ 精确实现registerQuery阶段的职责范围 -> "严格按照导演的要求"
- ✅ 使用IdentityHashMap维护节点映射 -> "用最好的通讯设备"
- ✅ 支持递归子查询处理 -> "支持多层嵌套演出"
- ✅ 正确的作用域层次结构 -> "舞台布局合理"

### 2. **可扩展性**

- ✅ 清晰的Namespace接口，易于添加新类型 -> "容易添加新类型演员"
- ✅ 灵活的ScopeType枚举，支持各种作用域 -> "支持各种舞台类型"
- ✅ 模块化的AST改写机制 -> "支持剧本修改"

### 3. **性能优化**

- ✅ IdentityHashMap提供O(1)查找性能 -> "瞬间找到任何演员"
- ✅ 缓存机制避免重复计算 -> "不重复做同样的工作"
- ✅ 延迟验证策略 -> "需要时才进行详细检查"

### 4. **调试友好**

- ✅ 详细的注册统计信息 -> "完整的演出记录"
- ✅ 清晰的toString方法 -> "易读的演员档案"
- ✅ 完整的示例代码 -> "详细的使用说明"

## 🔗 与其他阶段的集成

### 1. **前置阶段：SqlRewriter**
```java
// SqlRewriter进行无条件重写（UPDATE/DELETE转SELECT）-> "剧本预处理"
statement = sqlRewriter.rewrite(statement);

// registerQuery处理重写后的AST -> "根据最终剧本安排演员"
scope = validator.registerQuery(statement);
```

### 2. **后续阶段：语义验证**
```java
// 使用registerQuery建立的Scope和Namespace进行验证 -> "利用演员档案进行演出验证"
// 1. 列存在性验证 -> "确认演员确实存在"
// 2. 类型兼容性检查 -> "确认演员能胜任角色"
// 3. 聚合函数验证 -> "确认群演配置合理"
// 4. 子查询相关性验证 -> "确认客串演员与主演关系正确"
```

## 🏆 总结

`analyzeScopes` 方法是语义分析的**基础设施建设阶段**，就像为一场大型演出做前期筹备：

1. **🏗️ 构建基础设施**：为后续的详细验证、类型推导、星号展开等提供必要的"舞台"和"演员档案"

2. **🎯 精确的名称解析**：支持复杂的 SQL 特性，就像能在复杂的演出中准确找到任何演员

3. **🔒 类型安全保障**：为表达式类型推导和兼容性检查提供准确的元数据，就像确保演员能胜任角色

4. **⚡ 高性能查找**：使用 IdentityHashMap 和缓存机制，就像有超高效的演员通讯录

5. **🧩 模块化设计**：清晰分离作用域构建与详细验证，就像前期筹备与正式演出分工明确

6. **🔄 递归处理能力**：正确处理嵌套子查询和复杂SQL结构，就像能处理多层嵌套的复杂演出

7. **🔧 AST改写能力**：在注册过程中进行必要的语法标准化，就像对剧本进行必要的规范化

**形象比喻总结：**
如果说整个SQL语义分析是一场大型演出，那么 `analyzeScopes` 就是：
- 🎭 **舞台搭建师**：构建各种类型的舞台（Scope）
- 📋 **演员经纪人**：管理所有演员的档案（Namespace）  
- 🗺️ **导演助理**：为导演提供完整的"演员-舞台"地图
- 🔍 **现场协调员**：确保任何时候都能快速找到需要的演员

这种设计使得语义分析器能够处理任意复杂的 SQL 语句，同时保持高性能和可扩展性，为后续的语义验证、类型推导和优化阶段提供了坚实的基础。 