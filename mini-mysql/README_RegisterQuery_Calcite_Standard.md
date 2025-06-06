# SemanticAnalyzerScopeUtil - Apache Calcite 标准实现

## 概述

本次重构完全按照 [Apache Calcite 的 SqlValidatorImpl.registerQuery()](https://github.com/apache/calcite/blob/40d12b7419bd544bd829011a18ac37f6bc529ce7/core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java#L2336) 标准流程重新实现了 `SemanticAnalyzerScopeUtil`，确保与 Calcite 的语义分析逻辑完全一致。

## 核心改进

### 1. 标准的 registerQuery 流程

**重构前问题：**
- 使用 ThreadLocal + Stack 管理作用域，不符合 Calcite 模式
- 缺少正确的 IdentityMapping 机制
- 没有按照 Calcite 的递归注册逻辑

**重构后解决方案：**
```java
public static SqlValidatorScope registerQuery(
    SQLStatement statement,
    SqlValidatorScope parentScope,
    Map<SQLStatement, SqlValidatorScope> nodeToScopeMap,           // IdentityMapping
    Map<SQLStatement, SqlValidatorNamespace> nodeToNamespaceMap,  // IdentityMapping
    Map<SelectStatement.TableReference, SqlValidatorNamespace> tableRefToNamespaceMap,
    CatalogManager catalogManager) throws SemanticException {
    
    // 1. 检查是否已经注册过 (防重复注册)
    SqlValidatorScope existingScope = nodeToScopeMap.get(statement);
    if (existingScope != null) {
        return existingScope;
    }
    
    // 2. 根据节点类型分发注册逻辑
    SqlValidatorScope scope = registerByStatementType(statement, parentScope, ...);
    
    // 3. 建立AST节点到Scope的映射关系 (IdentityMapping)
    nodeToScopeMap.put(statement, scope);
    
    return scope;
}
```

### 2. IdentityMapping 机制

使用 `IdentityHashMap` 建立 AST 节点与 Scope/Namespace 的一对一映射：

```java
// 三个核心映射表，完全按照 Calcite 标准
Map<SQLStatement, SqlValidatorScope> nodeToScopeMap = new IdentityHashMap<>();
Map<SQLStatement, SqlValidatorNamespace> nodeToNamespaceMap = new IdentityHashMap<>();
Map<SelectStatement.TableReference, SqlValidatorNamespace> tableRefToNamespaceMap = new IdentityHashMap<>();
```

**优势：**
- O(1) 查找性能
- 基于对象引用，而非 equals() 比较
- 完全符合 Calcite 的设计模式

### 3. 递归注册逻辑

**SELECT 语句注册流程：**
```java
private static SqlValidatorScope registerSelect(...) {
    // 1. 创建SELECT作用域
    SqlValidatorScope selectScope = parentScope.createChildScope(SqlValidatorScope.ScopeType.SELECT);
    
    // 2. 注册FROM子句 -> 创建FROM作用域并注册表引用
    SqlValidatorScope fromScope = registerFrom(select, selectScope, ...);
    
    // 3. 创建SELECT的命名空间并注册
    SelectNamespace selectNamespace = new SelectNamespace(select);
    nodeToNamespaceMap.put(select, selectNamespace);
    
    // 4. 执行AST改写
    performSelectAstRewrite(select);
    
    // 5. 递归注册子查询
    registerSubqueries(select, selectScope, ...);
    
    return selectScope;
}
```

### 4. 表引用注册

**核心逻辑：**
```java
private static void registerTableReference(...) {
    SqlValidatorNamespace namespace = null;
    
    if (tableRef.isSubquery()) {
        // 子查询引用 - 递归注册 (关键递归点)
        SelectStatement subquery = tableRef.getSubquery();
        registerQuery(subquery, fromScope, ...); // 递归调用
        namespace = new SubqueryNamespace(subquery, tableRef.getAlias());
    } else {
        // 普通表引用
        performTableReferenceAstRewrite(tableRef); // AST改写: from tbl => from tbl as tbl
        Table table = catalogManager.getTable(tableName);
        namespace = new TableNamespace(table, tableRef.getAlias());
    }
    
    // 建立映射关系 (IdentityMapping)
    tableRefToNamespaceMap.put(tableRef, namespace);
    fromScope.addNamespace(nameInScope, namespace);
}
```

### 5. AST 改写支持

实现 Calcite 标准的 AST 改写：

```java
// 表引用改写：from tbl => from tbl as tbl
private static void performTableReferenceAstRewrite(SelectStatement.TableReference tableRef) {
    if (tableRef.getAlias() == null && !tableRef.isSubquery()) {
        String tableName = tableRef.getTableName();
        tableRef.setAlias(tableName); // 添加默认别名
    }
}
```

## 架构对比

### 重构前架构问题

```java
// ❌ 不符合 Calcite 标准的设计
public class SemanticAnalyzerScopeUtil {
    private static final ThreadLocal<Stack<SqlValidatorScope>> scopeStackThreadLocal = ...;
    
    public static void buildNamespaces(SQLStatement statement, SqlValidatorScope rootScope, ...) {
        clearScopeStack();
        pushScope(rootScope);
        
        switch (statement.getType()) {
            case SELECT: buildSelectNamespaces(...); break;
            // 缺少递归和映射机制
        }
    }
}
```

### 重构后标准架构

```java
// ✅ 完全符合 Calcite 标准的设计
public class SemanticAnalyzerScopeUtil {
    // 主入口：标准 registerQuery 接口
    public static SqlValidatorScope registerQuery(...) {
        // 检查、分发、映射、递归 - 完整流程
    }
    
    // 类型特定注册方法
    private static SqlValidatorScope registerSelect(...) { }
    private static SqlValidatorScope registerUpdate(...) { }
    private static SqlValidatorScope registerDelete(...) { }
    
    // 递归处理子结构
    private static void registerTableReference(...) { }
    private static void registerSubqueries(...) { }
    
    // AST改写支持
    private static void performSelectAstRewrite(...) { }
    private static void performTableReferenceAstRewrite(...) { }
}
```

## 使用示例

### 基本用法

```java
// 创建映射表
Map<SQLStatement, SqlValidatorScope> nodeToScopeMap = new IdentityHashMap<>();
Map<SQLStatement, SqlValidatorNamespace> nodeToNamespaceMap = new IdentityHashMap<>();
Map<SelectStatement.TableReference, SqlValidatorNamespace> tableRefToNamespaceMap = new IdentityHashMap<>();

// 注册查询
SqlValidatorScope resultScope = SemanticAnalyzerScopeUtil.registerQuery(
    statement, rootScope, nodeToScopeMap, nodeToNamespaceMap, 
    tableRefToNamespaceMap, catalogManager);

// 查询注册结果
SqlValidatorScope statementScope = nodeToScopeMap.get(statement);
SqlValidatorNamespace statementNamespace = nodeToNamespaceMap.get(statement);
```

### 复杂查询示例

```java
// 包含子查询的SQL: SELECT * FROM users WHERE dept_id IN (SELECT id FROM departments)
SelectStatement mainQuery = createSubqueryExample();

SemanticAnalyzerScopeUtil.registerQuery(mainQuery, rootScope, ...);

// 验证递归注册结果
System.out.println("注册的AST节点数量: " + nodeToScopeMap.size()); // 主查询 + 子查询
System.out.println("命名空间数量: " + nodeToNamespaceMap.size());
```

## 技术优势

### 1. **完整的 Calcite 兼容性**
- 所有逻辑都按照 Apache Calcite 的标准实现
- 支持后续与 Calcite 生态系统的集成

### 2. **高性能的 IdentityMapping**
- 使用 `IdentityHashMap` 实现 O(1) 查找
- 避免了昂贵的 `equals()` 比较

### 3. **正确的递归处理**
- 自动处理嵌套子查询
- 维护正确的作用域层次结构

### 4. **AST 改写支持**
- 在注册阶段进行必要的改写
- 为后续优化阶段准备标准化的 AST

### 5. **线程安全**
- 移除了 ThreadLocal，使用参数传递
- 支持并发处理不同的查询

## 测试验证

运行 `RegisterQueryCalciteExample` 查看完整的功能演示：

```bash
cd mini-mysql
javac -cp . src/main/java/cn/zhangyis/sql/planner/semantic/RegisterQueryCalciteExample.java
java -cp . cn.zhangyis.sql.planner.semantic.RegisterQueryCalciteExample
```

**预期输出：**
```
=== 演示标准的registerQuery流程 ===
注册完成，作用域映射数量: 1
命名空间映射数量: 1
表引用映射数量: 2

=== 演示复杂查询注册（包含子查询）===
复杂查询注册完成:
- 作用域映射: 2
- 命名空间映射: 2
- 表引用映射: 2

注册的AST节点类型:
- SelectStatement -> SELECT
- SelectStatement -> SELECT

=== 演示IdentityMapping的维护 ===
Query1 == Query2: false
Query1映射存在: true
Query2映射存在: true
映射到相同作用域: false
IdentityMapping验证通过！
```

## 总结

通过这次重构，`SemanticAnalyzerScopeUtil` 现在完全符合 Apache Calcite 的 `registerQuery` 标准：

1. **标准化流程**：检查 → 分发 → 映射 → 递归 → 改写
2. **IdentityMapping**：使用 `IdentityHashMap` 维护精确的对象映射
3. **递归处理**：正确处理子查询和嵌套结构
4. **AST改写**：在注册阶段进行必要的语法树标准化
5. **作用域管理**：维护正确的层次结构和命名空间

这为后续的语义验证、优化等阶段提供了坚实的基础，完全符合生产级SQL处理引擎的标准。 