# SemanticAnalyzerScopeUtil - 专注于核心语义分析功能

## 🎯 重构概述

根据您的建议，我们重构了 `SemanticAnalyzerScopeUtil`，使其专注于 Apache Calcite SqlValidatorImpl 的两个核心功能：

1. **registerQuery** - 注册查询，构建 scope 和 namespace
2. **validateQuery** - 验证查询语义

**重要变化：** 移除了 `performUnconditionalRewrites`，因为该功能已在 `DefaultSemanticAnalyzer` 中处理。

## 🏗️ 新架构设计

### 职责分离

```java
DefaultSemanticAnalyzer (总入口)
├── performUnconditionalRewrites()     // 无条件重写
├── SemanticAnalyzerScopeUtil          // 核心语义分析
│   ├── registerQuery()                // 构建scope和namespace  
│   └── validateQuery()                // 验证语义正确性
├── expandStarColumns()                // 展开星号
├── inferTypes()                       // 类型推导
├── foldConstants()                    // 常量折叠
└── buildLogicalPlan()                 // 构建逻辑计划
```

### 核心方法

```java
public class SemanticAnalyzerScopeUtil {
    // 核心分析方法 - 两阶段处理
    public SqlValidatorScope analyzeScopes(SQLStatement statement, SqlValidatorScope parentScope) {
        // 阶段1: registerQuery - 构建scope和namespace
        registerQuery(rootScope, null, statement, statement, null, false);
        
        // 阶段2: validateQuery - 验证语义正确性  
        validateQuery(statement, rootScope);
        
        return getScope(statement);
    }
    
    // 向后兼容的静态方法
    public static void buildNamespaces(SQLStatement statement, SqlValidatorScope rootScope, CatalogManager catalogManager) {
        SemanticAnalyzerScopeUtil analyzer = new SemanticAnalyzerScopeUtil(catalogManager);
        analyzer.analyzeScopes(statement, rootScope);
    }
}
```

## 🔄 与 DefaultSemanticAnalyzer 的集成

### 完整流程

```java
public class DefaultSemanticAnalyzer {
    public RelNode analyze(SQLStatement statement) throws SemanticException {
        // ====== 阶段0: 预处理 ======
        statement = performUnconditionalRewrites(statement);  // 在这里处理
        
        // ====== 阶段1: 构建命名空间和作用域 ======
        SemanticAnalyzerScopeUtil.buildNamespaces(statement, rootScope, catalogManager);
        
        // ====== 阶段2: 后续语义分析 ======
        validateTablesAndColumns(statement, rootScope);
        expandStarColumns(statement, rootScope);
        inferTypes(statement, rootScope);
        foldConstants(statement);
        
        // ====== 阶段3: 逻辑计划构建 ======
        RelNode logicalPlan = buildLogicalPlan(statement, rootScope);
        
        return optimizer.optimize(logicalPlan);
    }
}
```

### 调用时序

```
1. DefaultSemanticAnalyzer.analyze()
   ↓
2. performUnconditionalRewrites()        [在 DefaultSemanticAnalyzer 中]
   ↓  
3. SemanticAnalyzerScopeUtil.buildNamespaces()
   ├── registerQuery()                   [在 SemanticAnalyzerScopeUtil 中]
   └── validateQuery()                   [在 SemanticAnalyzerScopeUtil 中]
   ↓
4. 后续语义分析                          [回到 DefaultSemanticAnalyzer 中]
   ├── validateTablesAndColumns()
   ├── expandStarColumns()
   ├── inferTypes()
   └── foldConstants()
   ↓
5. buildLogicalPlan()                    [在 DefaultSemanticAnalyzer 中]
```

## 💡 核心优势

### 1. 清晰的职责分离

- **DefaultSemanticAnalyzer**: 总体流程控制、AST 预处理、后续分析
- **SemanticAnalyzerScopeUtil**: 专注于 scope/namespace 构建和基础验证

### 2. 符合 Calcite 架构

```java
// 完全按照 Apache Calcite SqlValidatorImpl 的设计
protected void registerQuery(
    SqlValidatorScope parentScope,
    SqlValidatorScope usingScope, 
    SQLStatement node,
    SQLStatement enclosingNode,
    String alias,
    boolean forceNullable) throws SemanticException
```

### 3. IdentityMapping 支持

```java
// 使用 IdentityHashMap 维护映射关系
private final IdentityHashMap<SQLStatement, SqlValidatorScope> scopes = new IdentityHashMap<>();
private final IdentityHashMap<SQLStatement, SqlValidatorNamespace> namespaces = new IdentityHashMap<>();
```

### 4. 递归子查询处理

```java
// 自动处理嵌套子查询
private void registerExpressionSubqueries(Expression expr, SqlValidatorScope scope) {
    if (expr instanceof SubqueryExpression) {
        SubqueryExpression subqueryExpr = (SubqueryExpression) expr;
        // 递归注册子查询
        registerQuery(scope, null, subqueryExpr.getSubquery(), subqueryExpr.getSubquery(), null, false);
    }
}
```

## 📋 使用示例

### 基本使用（推荐）

```java
// 通过 DefaultSemanticAnalyzer 使用（推荐方式）
DefaultSemanticAnalyzer analyzer = new DefaultSemanticAnalyzer(catalogManager);
RelNode logicalPlan = analyzer.analyze(sqlStatement);
```

### 直接使用（向后兼容）

```java
// 直接使用 SemanticAnalyzerScopeUtil（需要手动处理无条件重写）
SQLStatement rewrittenStatement = sqlRewriter.rewrite(sqlStatement);  // 必须先重写
SqlValidatorScope rootScope = new SqlValidatorScope();
SemanticAnalyzerScopeUtil.buildNamespaces(rewrittenStatement, rootScope, catalogManager);
```

### 实例化使用

```java
// 创建分析器实例
SemanticAnalyzerScopeUtil scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);

// 分析已重写的语句
SqlValidatorScope resultScope = scopeUtil.analyzeScopes(rewrittenStatement, null);

// 获取分析结果
Map<SQLStatement, SqlValidatorScope> allScopes = scopeUtil.getScopes();
Map<SQLStatement, SqlValidatorNamespace> allNamespaces = scopeUtil.getNamespaces();
```

## 🔧 两阶段处理详解

### 阶段1: registerQuery

**目标：** 构建 scope 和 namespace

**核心逻辑：**
1. 按语句类型分发：SELECT、UPDATE、DELETE、INSERT
2. 创建适当的作用域：FROM -> WHERE -> SELECT -> HAVING -> ORDER BY
3. 注册表引用和命名空间
4. 递归处理子查询
5. 建立 IdentityMapping 关系

### 阶段2: validateQuery

**目标：** 验证语义正确性

**核心逻辑：**
1. 验证表和列的存在性
2. 验证作用域可见性
3. 验证类型兼容性
4. 验证表达式的语义正确性

## 🚀 技术特性

### 1. 完全的 Calcite 兼容性

- 严格按照 SqlValidatorImpl 的方法签名和逻辑
- 支持标准的作用域层次结构
- 实现完整的命名空间体系

### 2. 高性能架构

- 使用 IdentityHashMap 提供 O(1) 查找
- 避免重复注册机制
- 高效的递归处理

### 3. 可扩展设计

- 清晰的接口分离
- 易于添加新的语句类型
- 支持自定义命名空间

## 📊 验证结果

运行 `CalciteStandardExample.java` 的输出：

```
=== 演示核心功能：registerQuery + validateQuery ===
分析完成，根作用域类型: SELECT
作用域映射数量: 3
命名空间映射数量: 2
SELECT语句作用域: SELECT
SELECT语句命名空间: SELECT
✅ registerQuery 和 validateQuery 两阶段处理完成

=== 演示与DefaultSemanticAnalyzer的集成 ===
1. DefaultSemanticAnalyzer执行无条件重写...
2. 调用SemanticAnalyzerScopeUtil构建作用域和命名空间...
3. DefaultSemanticAnalyzer继续执行后续分析...
✅ 集成流程演示完成
- 无条件重写: 在DefaultSemanticAnalyzer中处理
- registerQuery + validateQuery: 在SemanticAnalyzerScopeUtil中处理
- 后续分析: 回到DefaultSemanticAnalyzer中处理

=== 演示IdentityMapping维护 ===
Query1 == Query2: false
Query1在analyzer1中存在: true
Query1在analyzer2中存在: false
Query2在analyzer1中存在: false
Query2在analyzer2中存在: true
✅ IdentityMapping验证通过！每个分析器实例维护独立的映射
```

## 🔄 向后兼容性

为了保持向后兼容，我们保留了静态方法：

```java
// 向后兼容的便利方法
public static void buildNamespaces(
    SQLStatement statement,           // 注意：假设已经过无条件重写
    SqlValidatorScope rootScope,
    CatalogManager catalogManager) throws SemanticException {
    
    SemanticAnalyzerScopeUtil analyzer = new SemanticAnalyzerScopeUtil(catalogManager);
    analyzer.analyzeScopes(statement, rootScope);
}
```

## 📋 总结

重构后的 `SemanticAnalyzerScopeUtil` 实现了：

✅ **职责专注** - 专注于 registerQuery 和 validateQuery 核心功能  
✅ **架构清晰** - 与 DefaultSemanticAnalyzer 职责分离明确  
✅ **Calcite 兼容** - 严格按照 Apache Calcite 标准实现  
✅ **高性能** - IdentityMapping 和避免重复注册  
✅ **向后兼容** - 保留静态便利方法  
✅ **易于维护** - 清晰的两阶段处理逻辑

现在我们的语义分析架构完全符合 Apache Calcite 的设计理念，为后续的查询优化和执行阶段提供了坚实的基础。 