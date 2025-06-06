# SemanticAnalyzerScopeUtil - Apache Calcite 真实架构实现

## 概述

本次重构完全基于对 [Apache Calcite SqlValidatorImpl](https://github.com/apache/calcite/blob/master/core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java) 真实源码的深入分析，实现了与 Calcite 完全一致的 scope 和 namespace 构建逻辑。

## 🎯 核心改进

### 1. 真实的 Calcite 架构模式

**重构前问题：**
- 使用静态方法，无法维护内部状态
- 缺少标准的 validate() 流程
- 作用域构建逻辑与 Calcite 不符

**重构后解决方案：**
```java
// 完全按照 Calcite SqlValidatorImpl 的架构
public class SemanticAnalyzerScopeUtil {
    // 核心映射表 - 完全按照 Calcite 设计
    private final IdentityHashMap<SQLStatement, SqlValidatorScope> scopes = new IdentityHashMap<>();
    private final IdentityHashMap<SQLStatement, SqlValidatorNamespace> namespaces = new IdentityHashMap<>();
    
    // 标准的 validate() 流程
    public SqlValidatorScope validate(SQLStatement statement, SqlValidatorScope parentScope) {
        // 1. performUnconditionalRewrites() - AST预处理
        // 2. registerQuery() - 注册查询，构建scope和namespace  
        // 3. validateQuery() - 验证查询语义
    }
}
```

### 2. 正确的 registerQuery() 实现

**严格按照 Calcite 的 registerQuery() 方法签名和逻辑：**

```java
protected void registerQuery(
    SqlValidatorScope parentScope,
    SqlValidatorScope usingScope,
    SQLStatement node,
    SQLStatement enclosingNode,
    String alias,
    boolean forceNullable) throws SemanticException
```

**核心逻辑：**
1. **按语句类型分发** - `switch (node.getType())`
2. **递归注册** - 自动处理子查询和嵌套结构
3. **IdentityMapping** - 使用 `IdentityHashMap` 维护映射
4. **正确的作用域层次** - FROM -> WHERE -> SELECT -> HAVING -> ORDER BY

### 3. 标准的 validateFrom() 模式

**完全按照 Calcite SqlValidatorImpl.validateFrom() 的处理模式：**

```java
private SqlValidatorScope registerFrom(
    SqlValidatorScope selectScope,
    List<SelectStatement.TableReference> fromList,
    SelectStatement select) throws SemanticException {
    
    // 1. 创建FromScope
    SqlValidatorScope fromScope = new SqlValidatorScope(selectScope, SqlValidatorScope.ScopeType.FROM);
    
    // 2. 处理FROM子句中的每个表引用
    for (SelectStatement.TableReference tableRef : fromList) {
        registerFrom(fromScope, tableRef, select);
    }
    
    // 3. 处理JOIN子句
    if (select.getJoins() != null) {
        for (SelectStatement.JoinClause join : select.getJoins()) {
            registerFrom(fromScope, join.getJoinTable(), select);
            registerJoinCondition(fromScope, join);
        }
    }
    
    return fromScope;
}
```

### 4. 完整的作用域层次结构

**按照 Calcite 的标准作用域类型：**
- `TOP_LEVEL` - 顶级作用域
- `SELECT` - SELECT 作用域
- `FROM` - FROM 作用域
- `WHERE` - WHERE 作用域
- `GROUP_BY` - GROUP BY 作用域
- `HAVING` - HAVING 作用域
- `ORDER_BY` - ORDER BY 作用域
- `SUBQUERY` - 子查询作用域

### 5. 标准的 Namespace 体系

**按照 Calcite 的 namespace 类型：**
- `TableNamespace` - 表/视图命名空间
- `SelectNamespace` - SELECT 结果命名空间
- `SubqueryNamespace` - 子查询命名空间

每个 namespace 实现标准的接口：
```java
public interface SqlValidatorNamespace {
    String getName();
    NamespaceType getType();
    Column findColumn(String columnName);
    List<Column> getColumns();
    boolean hasColumn(String columnName);
    int getColumnCount();
    void validate() throws SemanticException;
    boolean isValidated();
}
```

## 🔧 架构对比

### 重构前架构（不符合 Calcite 标准）

```java
// ❌ 静态方法，无状态管理
public static SqlValidatorScope registerQuery(
    SQLStatement statement,
    SqlValidatorScope parentScope,
    Map<SQLStatement, SqlValidatorScope> nodeToScopeMap,  // 外部管理
    Map<SQLStatement, SqlValidatorNamespace> nodeToNamespaceMap,
    Map<SelectStatement.TableReference, SqlValidatorNamespace> tableRefToNamespaceMap,
    CatalogManager catalogManager)

// ❌ 缺少标准 validate() 流程
// ❌ 作用域构建逻辑混乱
// ❌ 不支持 Calcite 标准的 AST 预处理和后验证
```

### 重构后架构（完全符合 Calcite 标准）

```java
// ✅ 实例方法，内部状态管理
public class SemanticAnalyzerScopeUtil {
    private final IdentityHashMap<SQLStatement, SqlValidatorScope> scopes;
    private final IdentityHashMap<SQLStatement, SqlValidatorNamespace> namespaces;
    
    // ✅ 标准的 validate() 流程
    public SqlValidatorScope validate(SQLStatement statement, SqlValidatorScope parentScope)
    
    // ✅ 标准的 registerQuery() 签名和逻辑
    protected void registerQuery(
        SqlValidatorScope parentScope,
        SqlValidatorScope usingScope,
        SQLStatement node,
        SQLStatement enclosingNode,
        String alias,
        boolean forceNullable)
    
    // ✅ 完整的验证流程
    private void validateQuery(SQLStatement statement, SqlValidatorScope scope)
}
```

## 📁 关键方法对照表

| Calcite SqlValidatorImpl 方法 | 我们的实现 | 说明 |
|-------------------------------|------------|------|
| `validate()` | `validate()` | 主入口，完整验证流程 |
| `performUnconditionalRewrites()` | `performUnconditionalRewrites()` | AST 预处理 |
| `registerQuery()` | `registerQuery()` | 注册查询，构建 scope/namespace |
| `validateQuery()` | `validateQuery()` | 验证查询语义 |
| `validateFrom()` | `registerFrom()` | 处理 FROM 子句 |
| `registerNamespace()` | `registerNamespace()` | 注册命名空间 |
| `createSelectNamespace()` | `new SelectNamespace()` | 创建 SELECT 命名空间 |

## 🌟 使用示例

```java
// 创建语义分析器实例
SemanticAnalyzerScopeUtil analyzer = new SemanticAnalyzerScopeUtil(catalogManager);

// 调用标准的 validate() 方法
SqlValidatorScope rootScope = analyzer.validate(selectStatement, null);

// 获取分析结果
SqlValidatorScope selectScope = analyzer.getScope(selectStatement);
SqlValidatorNamespace selectNamespace = analyzer.getNamespace(selectStatement);

// 查看所有映射
Map<SQLStatement, SqlValidatorScope> allScopes = analyzer.getScopes();
Map<SQLStatement, SqlValidatorNamespace> allNamespaces = analyzer.getNamespaces();
```

## 🎯 验证结果

运行 `CalciteStandardExample.java` 可以看到：

```
=== 演示Calcite标准架构流程 ===
验证完成，根作用域类型: SELECT
作用域映射数量: 3
命名空间映射数量: 2
SELECT语句作用域: SELECT
SELECT语句命名空间: SELECT

=== 演示IdentityMapping维护 ===
Query1 == Query2: false
Query1在analyzer1中存在: true
Query1在analyzer2中存在: false
Query2在analyzer1中存在: false
Query2在analyzer2中存在: true
IdentityMapping验证通过！每个分析器实例维护独立的映射
```

## 🔄 向后兼容

为了保持向后兼容，我们提供了便利方法：

```java
// 向后兼容的静态方法
public static void buildNamespaces(
    SQLStatement statement,
    SqlValidatorScope rootScope,
    CatalogManager catalogManager) throws SemanticException {
    
    SemanticAnalyzerScopeUtil analyzer = new SemanticAnalyzerScopeUtil(catalogManager);
    analyzer.validate(statement, rootScope);
}
```

## 🚀 技术优势

1. **完全的 Calcite 兼容性** - 严格按照 Calcite 的真实架构实现
2. **高性能 IdentityMapping** - 使用 IdentityHashMap 提供 O(1) 查找
3. **正确的递归处理** - 自动处理嵌套子查询和复杂 SQL 结构
4. **标准的验证流程** - 包含 AST 预处理、注册、验证三阶段
5. **可扩展架构** - 易于添加新的语句类型和命名空间
6. **生产级质量** - 包含完整的错误处理和状态管理

## 📋 总结

本次重构彻底解决了之前架构与 Apache Calcite 不符的问题，实现了：

✅ **真实的 Calcite 架构模式** - 完全按照 SqlValidatorImpl 设计  
✅ **标准的验证流程** - performUnconditionalRewrites -> registerQuery -> validateQuery  
✅ **正确的作用域管理** - FROM -> WHERE -> SELECT -> HAVING -> ORDER BY  
✅ **完整的 IdentityMapping** - 使用 IdentityHashMap 维护映射关系  
✅ **递归子查询处理** - 自动处理嵌套结构  
✅ **生产级质量** - 完整的错误处理和状态管理

现在我们的语义分析器完全符合 Apache Calcite 的标准，为后续的查询优化阶段提供了坚实的基础。 