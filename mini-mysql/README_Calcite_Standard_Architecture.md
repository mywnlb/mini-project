# Apache Calcite 标准架构重构说明

## 重构背景

经过深入分析 Apache Calcite 的真实设计模式，我们发现之前的架构存在职责重复的问题：

### 问题诊断

1. **职责重复**：`SemanticAnalyzerScopeUtil.validateQuery()` 与 `DefaultSemanticAnalyzer.validateTablesAndColumns()` 存在功能重复
2. **不符合标准**：没有严格按照 Apache Calcite `SqlValidatorImpl` 的设计原则
3. **边界模糊**：各组件的职责边界不够清晰

### Apache Calcite 的真实流程

根据 [Apache Calcite SqlValidatorImpl 源码](https://github.com/apache/calcite/blob/master/core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java)，标准流程如下：

```java
// Apache Calcite SqlValidatorImpl.validate() 的核心流程
public SqlNode validate(SqlNode topNode) {
    // 阶段1: 无条件重写
    SqlNode rewritten = performUnconditionalRewrites(topNode, false);
    
    // 阶段2: 注册查询（构建scope和namespace）
    if (rewritten.isA(SqlKind.TOP_LEVEL)) {
        registerQuery(scope, null, rewritten, rewritten, null, false);
    }
    
    // 阶段3: 基础验证
    rewritten.validate(this, scope);
    
    return rewritten;
}
```

## 重构后的架构设计

### 职责分工

#### 1. DefaultSemanticAnalyzer（总协调器）
```
├── performUnconditionalRewrites()    // 无条件重写
├── SemanticAnalyzerScopeUtil.analyzeScopes()  // 委托scope构建
├── validateTablesAndColumns()        // 详细的表名列名验证
├── expandStarColumns()               // 展开星号
├── inferTypes()                      // 类型推导
├── foldConstants()                   // 常量折叠
└── buildLogicalPlan()                // 构建逻辑计划
```

#### 2. SemanticAnalyzerScopeUtil（核心语义分析）
```
├── registerQuery()                   // 构建scope和namespace（核心）
└── validateQueryStructure()          // 基础结构验证（轻量级）
```

### 详细对比

| 组件 | 职责 | 对应Calcite组件 |
|------|------|----------------|
| **DefaultSemanticAnalyzer** | 总流程协调、详细验证 | SqlValidatorImpl.validate() |
| **SemanticAnalyzerScopeUtil** | scope/namespace构建 | SqlValidatorImpl.registerQuery() |
| **SqlRewriter** | AST标准化 | SqlValidatorImpl.performUnconditionalRewrites() |

## 重构要点

### 1. 严格按照 Calcite 标准

**之前的问题**：
```java
// 错误：在SemanticAnalyzerScopeUtil中做详细验证
private void validateQuery(SQLStatement statement, SqlValidatorScope scope) {
    validateSelectList(select, selectScope);  // 重复！
    validateWhere(select, selectScope);       // 重复！
    // ... 详细验证逻辑
}
```

**重构后**：
```java
// 正确：只做基础结构验证
private void validateQueryStructure(SQLStatement statement, SqlValidatorScope scope) {
    // 验证作用域是否正确构建
    SqlValidatorScope statementScope = getScope(statement);
    if (statementScope == null) {
        throw new SemanticException("Statement scope not properly registered");
    }
    
    // 基础结构验证，不涉及详细的表名列名检查
    if (statement.getType() == SQLStatement.SQLType.SELECT) {
        validateSelectStructure((SelectStatement) statement);
    }
}
```

### 2. 职责边界清晰

| 验证类型 | 负责组件 | 说明 |
|----------|----------|------|
| **表存在性验证** | SemanticAnalyzerScopeUtil | 在registerQuery阶段检查 |
| **列存在性验证** | DefaultSemanticAnalyzer | 在validateTablesAndColumns阶段 |
| **类型推导** | DefaultSemanticAnalyzer | 在inferTypes阶段 |
| **星号展开** | DefaultSemanticAnalyzer | 在expandStarColumns阶段 |
| **作用域构建** | SemanticAnalyzerScopeUtil | 核心职责 |

### 3. 符合 Calcite 设计模式

#### registerQuery 的标准实现
```java
protected void registerQuery(
    SqlValidatorScope parentScope,
    SqlValidatorScope usingScope,
    SQLStatement node,
    SQLStatement enclosingNode,
    String alias,
    boolean forceNullable) {
    
    // 1. 避免重复注册
    if (scopes.containsKey(node)) {
        return;
    }
    
    // 2. 按语句类型分发
    switch (node.getType()) {
        case SELECT:
            registerSelect(parentScope, usingScope, (SelectStatement) node, 
                          enclosingNode, alias, forceNullable);
            break;
        // ... 其他类型
    }
}
```

#### 两阶段处理模式
```java
public SqlValidatorScope analyzeScopes(SQLStatement statement, SqlValidatorScope parentScope) {
    // 阶段1: 注册查询 - 构建scope和namespace
    registerQuery(rootScope, null, statement, statement, null, false);
    
    // 阶段2: 基础验证 - 仅做结构性验证
    validateQueryStructure(statement, rootScope);
    
    return getScope(statement);
}
```

## 使用示例

### 重构后的标准用法

```java
// 1. 创建分析器
DefaultSemanticAnalyzer analyzer = new DefaultSemanticAnalyzer(catalogManager);

// 2. 完整的语义分析流程
RelNode logicalPlan = analyzer.analyze(sqlStatement);

// 内部流程：
// ├── performUnconditionalRewrites(sqlStatement)
// ├── SemanticAnalyzerScopeUtil.buildNamespaces(statement, rootScope, catalogManager)
// │   ├── registerQuery() - 构建scope和namespace
// │   └── validateQueryStructure() - 基础验证
// ├── validateTablesAndColumns() - 详细验证
// ├── expandStarColumns() - 星号展开
// ├── inferTypes() - 类型推导
// ├── foldConstants() - 常量折叠
// └── buildLogicalPlan() - 逻辑计划构建
```

### 独立使用 SemanticAnalyzerScopeUtil

```java
// 如果只需要构建scope和namespace
SemanticAnalyzerScopeUtil scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);
SqlValidatorScope rootScope = scopeUtil.analyzeScopes(statement, null);

// 获取作用域和命名空间
SqlValidatorScope selectScope = scopeUtil.getScope(statement);
SqlValidatorNamespace selectNamespace = scopeUtil.getNamespace(statement);
```

## 性能优化

### 1. IdentityHashMap 高效查找
- 使用 `IdentityHashMap<SQLStatement, SqlValidatorScope>` 提供 O(1) 查找
- 避免重复注册同一个 statement

### 2. 延迟验证
- registerQuery 阶段只构建结构，不做详细验证
- 详细验证延迟到 DefaultSemanticAnalyzer 中进行

### 3. 递归处理优化
- 子查询注册采用递归方式，自动处理嵌套结构
- 避免手工遍历复杂的 AST 树

## 兼容性

### 向后兼容
- 保留静态便利方法 `buildNamespaces()`
- 现有使用方式不受影响

### 扩展性
- 清晰的接口设计，易于扩展新的语句类型
- 符合 Calcite 标准，便于集成其他 Calcite 组件

## 总结

重构后的架构严格按照 Apache Calcite 的设计原则：

1. **职责单一**：每个组件都有明确的职责边界
2. **符合标准**：完全按照 Calcite SqlValidatorImpl 的设计模式
3. **无重复处理**：消除了 validateQuery 和 validateTablesAndColumns 的重复
4. **高性能**：使用 IdentityHashMap，避免重复注册
5. **易扩展**：清晰的接口设计，便于添加新功能

这样的设计既保持了与 Apache Calcite 的兼容性，又提供了高效的语义分析能力。 