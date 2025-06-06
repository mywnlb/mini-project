# SemanticAnalyzerScopeUtil 实现说明 - Apache Calcite 语义分析核心工具

## 🎯 概述

`SemanticAnalyzerScopeUtil` 是严格按照 **Apache Calcite SqlValidatorImpl** 标准实现的语义分析器作用域工具类，专注于 SQL 语义分析的**基础设施建设阶段**。

### 📋 核心职责

本类专注于 Apache Calcite SqlValidatorImpl 的核心功能：
1. **registerQuery()** - 注册查询，构建scope和namespace
2. **validateQueryStructure()** - 基础语义验证（不与DefaultSemanticAnalyzer重复）

### 🎭 职责边界

- **SemanticAnalyzerScopeUtil**：构建scope和namespace，基础的结构性验证
- **DefaultSemanticAnalyzer**：详细的表名列名验证、类型推导、常量折叠等

参考：[Apache Calcite SqlValidatorImpl.java](https://github.com/apache/calcite/blob/master/core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java)

## 🧠 核心概念理解

### 🔧 Scope（作用域）- SQL的"眼界"

#### 💡 Scope 是什么？

**Scope 就像是人的"眼界"或"视野"** - 它决定了在 SQL 的特定位置能"看到"哪些表和列。

想象你站在不同的楼层看风景：
- 🏠 **1楼（FROM作用域）**：只能看到直接的表和别名
- 🏢 **2楼（SELECT作用域）**：可以看到FROM中的表，还能看到一些计算列
- 🏗️ **3楼（子查询作用域）**：不仅能看到自己的表，还能"透视"到外层查询的表

#### 🏗️ Scope 的层次结构

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

#### 🎯 Scope 的核心功能

```java
public class SqlValidatorScope {
    // 作用域类型
    public enum ScopeType {
        TOP_LEVEL, SELECT, FROM, WHERE, GROUP_BY, HAVING, ORDER_BY, SUBQUERY
    }
    
    private final SqlValidatorScope parent;
    private final ScopeType scopeType;
    private final Map<String, SqlValidatorNamespace> namespaces = new LinkedHashMap<>();
    
    // 核心功能
    public void addNamespace(String name, SqlValidatorNamespace namespace);
    public Column findColumn(String tableName, String columnName);
    public SqlValidatorScope createChildScope(ScopeType scopeType);
    public boolean canAccess(String tableName);
    public Set<String> getVisibleTableNames();
    
    /**
     * 名称解析 - 核心功能
     * 支持：
     * 1. 限定名：table.column
     * 2. 非限定名：column
     * 3. 作用域链查找：子查询可以访问外层表
     * 4. 歧义检测：多个同名列的处理
     */
    public Column findColumn(String tableName, String columnName) throws SemanticException {
        // 1. 限定名查找
        if (tableName != null) {
            SqlValidatorNamespace namespace = namespaces.get(tableName);
            if (namespace != null) {
                return namespace.findColumn(columnName);
            }
            // 向上查找父作用域
            if (parent != null) {
                return parent.findColumn(tableName, columnName);
            }
            throw new SemanticException("Table '" + tableName + "' not found");
        }
        
        // 2. 非限定名查找
        List<Column> candidates = new ArrayList<>();
        for (SqlValidatorNamespace namespace : namespaces.values()) {
            Column column = namespace.findColumn(columnName);
            if (column != null) {
                candidates.add(column);
            }
        }
        
        // 3. 歧义检测
        if (candidates.size() > 1) {
            throw new SemanticException("Column '" + columnName + "' is ambiguous");
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        
        // 4. 向上查找父作用域
        if (parent != null) {
            return parent.findColumn(null, columnName);
        }
        
        throw new SemanticException("Column '" + columnName + "' not found");
    }
}
```

**主要功能：**
1. **🎯 名称解析**：`scope.findColumn("tableName", "columnName")` - 就像"寻人启事"
2. **🏷️ 别名管理**：处理表别名和列别名 - 就像"花名册"
3. **👁️ 可见性控制**：决定哪些表和列在当前位置可见 - 就像"权限管理"
4. **⚠️ 歧义检测**：发现重复的列名引用 - 就像"重名检查"

### 📦 Namespace（命名空间）- 数据的"身份证"

#### 💡 Namespace 是什么？

**Namespace 就像是数据源的"身份证档案"** - 它包含了该数据源的所有详细信息。

就像一个人的身份证包含：姓名、性别、年龄、地址等信息，Namespace 包含：
- 📛 **名称**：表名或别名
- 🏷️ **类型**：是表、视图、还是子查询结果
- 📋 **列清单**：包含哪些列，每列的类型是什么
- 🔍 **查找功能**：快速找到指定的列

#### 🗂️ Namespace 的接口设计

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
    
    String getName();                       // 获取"商品名称"
    NamespaceType getType();               // 获取"商品类型"
    Column findColumn(String columnName);   // 查找"商品属性"
    List<Column> getColumns();             // 获取"商品清单"
    boolean hasColumn(String columnName);   // 检查"是否有某属性"
    int getColumnCount();                  // 获取"属性数量"
    void validate() throws SemanticException; // "质量检查"
}
```

#### 🏭 Namespace 的具体实现

**TableNamespace - 表命名空间**
```java
public class TableNamespace implements SqlValidatorNamespace {
    private final Table table;
    private final String alias;
    private final CatalogManager catalogManager;
    
    public TableNamespace(Table table, String alias, CatalogManager catalogManager) {
        this.table = table;
        this.alias = alias;
        this.catalogManager = catalogManager;
    }
    
    @Override
    public List<Column> getColumns() {
        return table.getColumns();
    }
    
    @Override
    public Column findColumn(String columnName) {
        return table.findColumn(columnName);
    }
    
    @Override
    public String getName() {
        return alias != null ? alias : table.getTableName();
    }
    
    @Override
    public NamespaceType getType() {
        return NamespaceType.TABLE;
    }
    
    // 就像"原厂说明书" - 直接从表元数据获取列信息
    // 支持别名处理 - 就像"产品别名"
}
```

**SelectNamespace - SELECT结果命名空间**
```java
public class SelectNamespace implements SqlValidatorNamespace {
    private final SelectStatement select;
    private final String alias;
    private List<Column> columns; // 懒加载
    
    public SelectNamespace(SelectStatement select, String alias) {
        this.select = select;
        this.alias = alias;
    }
    
    @Override
    public List<Column> getColumns() {
        if (columns == null) {
            deriveColumns(); // 从SELECT项推导列信息
        }
        return columns;
    }
    
    private void deriveColumns() {
        // 从SELECT列表推导结果列
        // 处理：
        // 1. 普通列：name -> name
        // 2. 别名列：name as user_name -> user_name
        // 3. 表达式：age + 1 -> expr$0 (或自定义名称)
        // 4. 星号：* -> 展开所有列
    }
    
    // 就像"定制说明书" - 从SELECT项推导列信息
    // 处理别名和表达式列名 - 就像"个性化定制"
}
```

**SubqueryNamespace - 子查询命名空间**
```java
public class SubqueryNamespace implements SqlValidatorNamespace {
    private final SelectStatement subquery;
    private final String alias;
    private final SqlValidatorImpl validator;
    
    public SubqueryNamespace(SelectStatement subquery, String alias, SqlValidatorImpl validator) {
        this.subquery = subquery;
        this.alias = alias; // 子查询必须有别名
        this.validator = validator;
        
        if (alias == null) {
            throw new SemanticException("Subquery must have an alias");
        }
    }
    
    @Override
    public List<Column> getColumns() {
        // 递归获取子查询的结果列
        SqlValidatorNamespace subqueryNamespace = validator.getNamespace(subquery);
        return subqueryNamespace.getColumns();
    }
    
    // 就像"套装说明书" - 递归处理子查询结果
    // 必须有别名 - 就像"套装必须有品牌名"
}
```

#### 🛠️ Namespace 的核心功能

1. **📋 列信息管理**：存储列名、类型、约束等 - 就像"商品规格表"
2. **🔍 类型推导**：为表达式提供类型信息 - 就像"兼容性检查"
3. **⭐ 星号展开**：`SELECT *` 时获取所有列 - 就像"全套商品展示"
4. **🛡️ 权限检查**：验证列访问权限 - 就像"访问控制"

## 🏗️ 核心实现架构

### 1. 核心映射表 - 完全按照Calcite设计

```java
public class SemanticAnalyzerScopeUtil {
    /**
     * 核心映射表 - 完全按照Calcite SqlValidatorImpl的设计
     */
    private final IdentityHashMap<SQLStatement, SqlValidatorScope> scopes = new IdentityHashMap<>();
    private final IdentityHashMap<SQLStatement, SqlValidatorNamespace> namespaces = new IdentityHashMap<>();
    private final CatalogManager catalogManager;
    private SqlValidatorScope emptyScope;
    
    public SemanticAnalyzerScopeUtil(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
        this.emptyScope = new SqlValidatorScope();
    }
}
```

**核心特性：**
- ✅ 使用 `IdentityHashMap` 确保AST节点的对象身份映射
- ✅ 分离 scopes 和 namespaces 的管理，符合Calcite设计
- ✅ 支持空作用域的管理

### 2. analyzeScopes - 核心分析方法

```java
/**
 * 核心分析方法 - 按照Calcite SqlValidatorImpl的两阶段处理
 * 
 * 注意：此方法假设 AST 已经经过无条件重写处理
 * 
 * 两阶段处理：
 * 1. registerQuery() - 注册查询，构建scope和namespace
 * 2. validateQueryStructure() - 基础语义验证（结构性验证，不重复详细验证）
 */
public SqlValidatorScope analyzeScopes(SQLStatement statement, SqlValidatorScope parentScope) throws SemanticException {
    Objects.requireNonNull(statement, "Statement cannot be null");
    
    // 创建根作用域
    SqlValidatorScope rootScope = parentScope != null ? parentScope : new SqlValidatorScope();
    
    // 阶段1: 注册查询 - 构建scope和namespace (对应Calcite的registerQuery)
    registerQuery(rootScope, null, statement, statement, null, false);
    
    // 阶段2: 基础验证 - 仅做结构性验证 (对应Calcite的validateQuery基础部分)
    validateQueryStructure(statement, rootScope);
    
    return getScope(statement);
}
```

**核心设计原则：**
- 🎯 **两阶段处理**：严格分离构建阶段和验证阶段
- 🔧 **假设前置处理**：假设AST已经过无条件重写处理
- 📋 **职责明确**：只做基础设施建设，不重复详细验证工作

## 🔄 阶段1：registerQuery - 注册查询

### 核心方法签名

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
    boolean forceNullable) throws SemanticException
```

### 支持的语句类型

```java
// 按照语句类型分发注册逻辑
switch (node.getType()) {
    case SELECT:
        registerSelect(parentScope, usingScope, (SelectStatement) node, enclosingNode, alias, forceNullable);
        break;
    case UPDATE:
        registerUpdate(parentScope, usingScope, (UpdateStatement) node, enclosingNode, alias, forceNullable);
        break;
    case DELETE:
        registerDelete(parentScope, usingScope, (DeleteStatement) node, enclosingNode, alias, forceNullable);
        break;
    case INSERT:
        registerInsert(parentScope, usingScope, (InsertStatement) node, enclosingNode, alias, forceNullable);
        break;
    default:
        throw new SemanticException("Unsupported statement type: " + node.getType());
}
```

## 🎪 SELECT语句注册流程

### registerSelect - 按照Calcite标准流程

```java
/**
 * 注册SELECT语句 - 按照Calcite的标准流程
 * 
 * Calcite中SELECT的注册流程：
 * 1. 创建SelectScope
 * 2. 注册FROM子句 -> 创建FromScope并处理表引用
 * 3. 创建SelectNamespace并注册
 * 4. 建立scopes和namespaces映射
 */
private void registerSelect(
    SqlValidatorScope parentScope,
    SqlValidatorScope usingScope,
    SelectStatement select,
    SQLStatement enclosingNode,
    String alias,
    boolean forceNullable) throws SemanticException {
    
    // 1. 创建SelectScope - Calcite标准做法
    SqlValidatorScope selectScope = new SqlValidatorScope(parentScope, SqlValidatorScope.ScopeType.SELECT);
    
    // 2. 注册FROM子句 - 这是关键步骤
    if (select.getFrom() != null && !select.getFrom().isEmpty()) {
        SqlValidatorScope fromScope = registerFrom(selectScope, select.getFrom(), select);
    }
    
    // 3. 创建SelectNamespace
    SelectNamespace selectNamespace = new SelectNamespace(select);
    
    // 4. 注册namespace - 按照Calcite的registerNamespace()方法
    registerNamespace(usingScope, alias, selectNamespace, forceNullable);
    
    // 5. 建立IdentityMapping - 这是Calcite的核心
    scopes.put(select, selectScope);
    namespaces.put(select, selectNamespace);
    
    // 6. 递归处理子查询 - 在表达式中查找
    registerSubqueries(select, selectScope);
}
```

### registerFrom - FROM子句注册

```java
/**
 * 注册FROM子句 - 完全按照Calcite SqlValidatorImpl.validateFrom()的模式
 */
private SqlValidatorScope registerFrom(
    SqlValidatorScope selectScope,
    List<SelectStatement.TableReference> fromList,
    SelectStatement select) throws SemanticException {
    
    // 1. 创建FromScope - Calcite标准做法
    SqlValidatorScope fromScope = new SqlValidatorScope(selectScope, SqlValidatorScope.ScopeType.FROM);
    
    // 2. 处理FROM子句中的每个表引用
    for (SelectStatement.TableReference tableRef : fromList) {
        registerTableReference(fromScope, tableRef, select);
    }
    
    // 3. 处理JOIN子句
    if (select.getJoins() != null) {
        for (SelectStatement.JoinClause join : select.getJoins()) {
            registerTableReference(fromScope, join.getJoinTable(), select);
            // JOIN条件在专门的JoinScope中处理
            registerJoinCondition(fromScope, join);
        }
    }
    
    return fromScope;
}
```

### registerTableReference - 表引用注册

```java
/**
 * 注册单个表引用 - 按照Calcite的标准模式
 */
private void registerTableReference(
    SqlValidatorScope fromScope,
    SelectStatement.TableReference tableRef,
    SelectStatement enclosingSelect) throws SemanticException {
    
    SqlValidatorNamespace namespace;
    
    if (tableRef.isSubquery()) {
        // 子查询处理 - 递归调用registerQuery
        SelectStatement subquery = tableRef.getSubquery();
        
        // 为子查询创建新的作用域
        SqlValidatorScope subqueryScope = new SqlValidatorScope(fromScope, SqlValidatorScope.ScopeType.SUBQUERY);
        
        // 递归注册子查询 - 这是关键的递归点
        registerQuery(subqueryScope, null, subquery, enclosingSelect, tableRef.getAlias(), false);
        
        // 创建子查询namespace
        namespace = new SubqueryNamespace(subquery, tableRef.getAlias());
        
    } else {
        // 普通表引用处理
        String tableName = tableRef.getTableName();
        
        // 验证表是否存在
        if (!catalogManager.tableExists(tableName)) {
            throw new SemanticException.TableNotFoundException(tableName);
        }
        
        Table table = catalogManager.getTable(tableName);
        
        // 创建表namespace
        namespace = new TableNamespace(table, tableRef.getAlias());
    }
    
    // 注册namespace到当前作用域 - 按照Calcite的registerNamespace()
    String nameInScope = tableRef.getAlias() != null ? tableRef.getAlias() : tableRef.getTableName();
    registerNamespace(fromScope, nameInScope, namespace, false);
}
```

## 🔍 子查询递归处理

### registerSubqueries - 在表达式中查找子查询

```java
/**
 * 注册子查询 - 在表达式中查找并注册子查询
 */
private void registerSubqueries(SelectStatement select, SqlValidatorScope selectScope) throws SemanticException {
    // 在SELECT项中查找子查询
    for (SelectStatement.SelectItem item : select.getSelectItems()) {
        registerExpressionSubqueries(item.getExpression(), selectScope);
    }
    
    // 在WHERE子句中查找子查询
    if (select.getWhere() != null) {
        registerExpressionSubqueries(select.getWhere(), selectScope);
    }
    
    // 在HAVING子句中查找子查询
    if (select.getHaving() != null) {
        registerExpressionSubqueries(select.getHaving(), selectScope);
    }
    
    // 在ORDER BY子句中查找子查询
    if (select.getOrderByItems() != null) {
        for (SelectStatement.OrderByItem orderItem : select.getOrderByItems()) {
            registerExpressionSubqueries(orderItem.getExpression(), selectScope);
        }
    }
}

/**
 * 在表达式中注册子查询
 */
private void registerExpressionSubqueries(Expression expr, SqlValidatorScope scope) throws SemanticException {
    if (expr instanceof SubqueryExpression) {
        SubqueryExpression subqueryExpr = (SubqueryExpression) expr;
        // 递归注册子查询
        registerQuery(scope, null, subqueryExpr.getSubquery(), subqueryExpr.getSubquery(), null, false);
    }
    // TODO: 处理其他包含子查询的表达式类型
}
```

## 🎯 DML语句注册

### UPDATE语句注册

```java
/**
 * 注册UPDATE语句
 */
private void registerUpdate(
    SqlValidatorScope parentScope,
    SqlValidatorScope usingScope,
    UpdateStatement update,
    SQLStatement enclosingNode,
    String alias,
    boolean forceNullable) throws SemanticException {
    
    SqlValidatorScope updateScope = new SqlValidatorScope(parentScope, SqlValidatorScope.ScopeType.SELECT);
    
    // 注册目标表
    String tableName = update.getTableName();
    if (!catalogManager.tableExists(tableName)) {
        throw new SemanticException.TableNotFoundException(tableName);
    }
    
    Table table = catalogManager.getTable(tableName);
    TableNamespace tableNamespace = new TableNamespace(table, null);
    
    registerNamespace(updateScope, tableName, tableNamespace, false);
    
    // 建立映射
    scopes.put(update, updateScope);
    namespaces.put(update, tableNamespace);
    
    // 处理内嵌的SELECT语句（来自SqlRewriter的重写）
    if (update.getSelectStatement() != null) {
        registerQuery(updateScope, null, update.getSelectStatement(), update, null, false);
    }
}
```

### DELETE和INSERT语句

```java
// DELETE和INSERT的注册逻辑类似UPDATE，都包含：
// 1. 创建对应的作用域
// 2. 注册目标表
// 3. 建立IdentityMapping
// 4. 处理内嵌的SELECT语句（如果有）
```

## 🔍 阶段2：validateQueryStructure - 基础验证

### 职责边界明确

```java
/**
 * 基础语义验证 - 仅做结构性验证，不重复DefaultSemanticAnalyzer的工作
 * 
 * 按照Apache Calcite的设计原则：
 * - 本方法只做最基础的结构性验证
 * - 详细的表名列名验证留给DefaultSemanticAnalyzer.validateTablesAndColumns()
 * - 类型推导留给DefaultSemanticAnalyzer.inferTypes()
 * - 星号展开留给DefaultSemanticAnalyzer.expandStarColumns()
 * 
 * 职责：验证scope和namespace构建是否正确，SQL结构是否基本合理
 */
private void validateQueryStructure(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
    // 验证作用域是否正确构建
    SqlValidatorScope statementScope = getScope(statement);
    if (statementScope == null) {
        throw new SemanticException("Statement scope not properly registered");
    }
    
    // 验证命名空间是否正确构建
    SqlValidatorNamespace statementNamespace = getNamespace(statement);
    if (statementNamespace == null && statement.getType() == SQLStatement.SQLType.SELECT) {
        throw new SemanticException("Statement namespace not properly registered for SELECT");
    }
    
    // 根据语句类型进行基础结构验证
    switch (statement.getType()) {
        case SELECT:
            validateSelectStructure((SelectStatement) statement);
            break;
        case UPDATE:
        case DELETE:
        case INSERT:
            // 对于DML语句，基础验证已在registerQuery中完成
            break;
        default:
            throw new SemanticException("Unsupported statement type: " + statement.getType());
    }
}
```

### SELECT结构验证

```java
/**
 * 验证SELECT语句的基础结构
 */
private void validateSelectStructure(SelectStatement select) throws SemanticException {
    // 验证FROM子句的基础结构
    if (select.getFrom() == null || select.getFrom().isEmpty()) {
        throw new SemanticException("SELECT statement must have FROM clause");
    }
    
    // 验证SELECT项的基础结构
    if (select.getSelectItems() == null || select.getSelectItems().isEmpty()) {
        throw new SemanticException("SELECT statement must have select items");
    }
    
    // 其他基础结构验证...
    // 注意：这里不做详细的列名表名验证，那是DefaultSemanticAnalyzer的职责
}
```

## 🎪 名称解析示例

### 🔍 示例 1: 解析限定名 `u.name`

```java
SqlValidatorScope selectScope = scopes.get(mainSelect);
Column column = selectScope.findColumn("u", "name");

// 解析流程:
// 1. 在selectScope中查找"u" -> 未找到
// 2. 查找父作用域fromScope -> 找到"u" -> TableNamespace(users)
// 3. 在users表中查找"name"列 -> Column(name, VARCHAR)
// 4. 返回结果
```

### 🔍 示例 2: 解析子查询中的外部引用 `u.id`

```java
SqlValidatorScope subqueryScope = scopes.get(subquery);
Column column = subqueryScope.findColumn("u", "id");

// 解析流程:
// 1. 在subqueryScope中查找"u" -> 未找到
// 2. 查找父作用域selectScope -> 未找到
// 3. 查找selectScope的父作用域fromScope -> 找到"u"
// 4. 在users表中查找"id"列 -> Column(id, INTEGER)
// 5. 标记为外部引用（相关子查询）
```

### 🔍 示例 3: 歧义检测

```sql
-- 假设users和orders都有id列
SELECT id FROM users u, orders o
```

```java
Column column = selectScope.findColumn(null, "id");
// 抛出SemanticException: "Column 'id' is ambiguous. Use u.id or o.id"
```

## 🔧 公共接口方法

### 核心查询方法

```java
/**
 * 获取语句对应的作用域
 */
public SqlValidatorScope getScope(SQLStatement statement) {
    return scopes.get(statement);
}

/**
 * 获取语句对应的命名空间
 */
public SqlValidatorNamespace getNamespace(SQLStatement statement) {
    return namespaces.get(statement);
}

/**
 * 获取空作用域
 */
public SqlValidatorScope getEmptyScope() {
    return emptyScope;
}
```

### 管理方法

```java
/**
 * 清理所有映射
 */
public void clear() {
    scopes.clear();
    namespaces.clear();
}

/**
 * 获取所有作用域映射（只读）
 */
public Map<SQLStatement, SqlValidatorScope> getScopes() {
    return Collections.unmodifiableMap(scopes);
}

/**
 * 获取所有命名空间映射（只读）
 */
public Map<SQLStatement, SqlValidatorNamespace> getNamespaces() {
    return Collections.unmodifiableMap(namespaces);
}
```

## 🎪 使用示例

### 基本使用流程

```java
// 1. 创建分析器实例
CatalogManager catalogManager = new CatalogManager();
SemanticAnalyzerScopeUtil scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);

// 2. 分析SQL语句（假设已经过无条件重写）
SQLStatement statement = getRewrittenStatement(); // 来自SqlRewriter
SqlValidatorScope resultScope = scopeUtil.analyzeScopes(statement, null);

// 3. 获取构建的scope和namespace
SqlValidatorScope selectScope = scopeUtil.getScope(statement);
SqlValidatorNamespace selectNamespace = scopeUtil.getNamespace(statement);

// 4. 后续由DefaultSemanticAnalyzer进行详细验证
DefaultSemanticAnalyzer analyzer = new DefaultSemanticAnalyzer(catalogManager);
analyzer.validateTablesAndColumns(statement, selectScope, selectNamespace);
analyzer.inferTypes(statement, selectScope, selectNamespace);
analyzer.expandStarColumns(statement, selectScope, selectNamespace);
```

### 向后兼容方法

```java
/**
 * 构建命名空间和作用域（向后兼容）
 * 
 * 注意：此方法假设传入的statement已经经过无条件重写处理
 */
public static void buildNamespaces(
    SQLStatement statement,
    SqlValidatorScope rootScope,
    CatalogManager catalogManager) throws SemanticException {
    
    SemanticAnalyzerScopeUtil analyzer = new SemanticAnalyzerScopeUtil(catalogManager);
    analyzer.analyzeScopes(statement, rootScope);
}
```

## 🎯 作用域层次结构示例

### 复杂SQL的作用域结构

```sql
SELECT u.name, 
       (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) as order_count
FROM users u 
JOIN profiles p ON u.id = p.user_id
WHERE u.age > 18
```

**构建的作用域结构：**

```
🎭 rootScope (TOP_LEVEL)
└── 🎪 selectScope (SELECT) 
    └── 🎨 fromScope (FROM)
        ├── 🎭 "u" -> TableNamespace(users)
        ├── 🎭 "p" -> TableNamespace(profiles)
        └── 🎬 subqueryScope (SUBQUERY)
            └── 🎨 subqueryFromScope (FROM)
                └── 🎭 "o" -> TableNamespace(orders)
```

## 🏆 架构优势

### 1. **严格的Calcite兼容性**

- ✅ 完全按照Apache Calcite SqlValidatorImpl的设计模式
- ✅ 使用IdentityHashMap维护AST节点映射
- ✅ 两阶段处理：registerQuery + validateQueryStructure
- ✅ 支持递归子查询处理

### 2. **清晰的职责分工**

- ✅ **SemanticAnalyzerScopeUtil**：基础设施建设，scope/namespace构建
- ✅ **DefaultSemanticAnalyzer**：详细验证，类型推导，语法糖处理
- ✅ **避免重复工作**：明确的边界定义

### 3. **高性能设计**

- ✅ IdentityHashMap提供O(1)查找性能
- ✅ 懒加载策略：只在需要时构建
- ✅ 缓存友好：保持引用直到清理

### 4. **可扩展性**

- ✅ 支持所有主要SQL语句类型（SELECT、UPDATE、DELETE、INSERT）
- ✅ 递归处理复杂嵌套结构
- ✅ 易于添加新的语句类型支持

## 🔗 与其他组件的集成

### 1. **前置阶段：SqlRewriter**
```java
// 1. SqlRewriter进行无条件重写（UPDATE/DELETE转SELECT等）
SQLStatement rewrittenStatement = sqlRewriter.rewrite(originalStatement);

// 2. SemanticAnalyzerScopeUtil构建基础设施
SemanticAnalyzerScopeUtil scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);
SqlValidatorScope scope = scopeUtil.analyzeScopes(rewrittenStatement, null);
```

### 2. **后续阶段：DefaultSemanticAnalyzer**
```java
// 3. DefaultSemanticAnalyzer进行详细验证
DefaultSemanticAnalyzer analyzer = new DefaultSemanticAnalyzer(catalogManager);
analyzer.validateTablesAndColumns(statement, scope, namespace);
analyzer.inferTypes(statement, scope, namespace);
analyzer.expandStarColumns(statement, scope, namespace);
analyzer.performConstantFolding(statement, scope, namespace);
```

## 🎯 总结

**SemanticAnalyzerScopeUtil** 是严格按照Apache Calcite标准实现的语义分析器基础工具类，专注于：

1. **🏗️ 基础设施建设**：为SQL语义分析提供必要的scope和namespace基础设施
2. **🎯 两阶段处理**：registerQuery构建 + validateQueryStructure验证
3. **🔄 递归处理能力**：正确处理嵌套子查询和复杂SQL结构
4. **📋 明确的职责边界**：只做基础设施建设，不重复详细验证工作
5. **⚡ 高性能设计**：IdentityHashMap映射，O(1)查找性能
6. **🧩 完整的Calcite兼容性**：严格遵循Calcite的设计模式和实现标准

**这种设计确保了语义分析的基础阶段高效、准确，为后续的详细验证和优化提供了坚实的基础。**