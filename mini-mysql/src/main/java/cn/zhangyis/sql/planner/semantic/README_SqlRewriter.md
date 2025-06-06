# SQL重写器 (SqlRewriter) - 严格遵循Apache Calcite标准

## 重要说明：performUnconditionalRewrites的实际范围

**关键认识**：在Apache Calcite的`SqlValidatorImpl.performUnconditionalRewrites()`中，**表达式级别的重写实际上并不在这个阶段进行**。

### Apache Calcite中的实际做法

在Apache Calcite中，`performUnconditionalRewrites`的职责非常明确和有限：

```java
// Apache Calcite的实际performUnconditionalRewrites范围
public SqlNode performUnconditionalRewrites(SqlNode node, boolean underFrom) {
    if (node instanceof SqlUpdate) {
        // 1. UPDATE转SELECT：创建源SELECT用于验证
        SqlSelect select = createSourceSelectForUpdate(call);
        call.setSourceSelect(select);
        return call;
    }
    
    if (node instanceof SqlDelete) {
        // 2. DELETE转SELECT：创建源SELECT用于验证
        SqlSelect select = createSourceSelectForDelete(call);
        call.setSourceSelect(select);
        return call;
    }
    
    if (node instanceof SqlMerge) {
        // 3. MERGE语句的类似处理
    }
    
    // 注意：这里没有表达式级别的重写！
    return node;
}
```

### 表达式重写在Calcite中的实际位置

表达式级别的重写在Apache Calcite中通常在以下阶段进行：

1. **RexSimplify阶段**：常量折叠、表达式简化
2. **优化规则阶段**：函数重写、谓词下推等  
3. **SQL-to-Rex转换后**：在RelNode构建完成后进行

## 核心功能（严格按照Calcite标准）

### 1. UPDATE语句转SELECT重写

```java
// 原始UPDATE语句
UPDATE users SET name = 'John', age = 30 WHERE id = 1;

// 重写后的内部SELECT语句（注意：表达式保持原样）
SELECT 'John' as name, 30 as age FROM users WHERE id = 1;
```

### 2. DELETE语句转SELECT重写

```java
// 原始DELETE语句
DELETE FROM users WHERE age > 65;

// 重写后的内部SELECT语句（注意：表达式保持原样）
SELECT * FROM users WHERE age > 65;
```

### 3. 不在此阶段进行的重写

以下重写在Apache Calcite中**不是**在`performUnconditionalRewrites`阶段进行的：

- ❌ **函数重写**: `COUNT(*)` → `COUNT(1)`
- ❌ **NULL处理函数标准化**: `ISNULL/NVL/IFNULL` → `COALESCE`  
- ❌ **常量折叠**: `1+2` → `3`
- ❌ **ORDER BY位置引用**: `ORDER BY 1, 2` → `ORDER BY column1, column2`
- ❌ **表达式简化和优化**

这些属于**表达式级重写**，在Calcite中通常在后续的优化阶段处理。

## 设计原则（修正后）

### 严格遵循Calcite标准

```java
public class SqlRewriter {
    // 只处理语句级重写，不涉及表达式重写
    public SQLStatement rewrite(SQLStatement statement) {
        switch (statement.getType()) {
            case UPDATE:
                return rewriteUpdateStatement(statement, context);
            case DELETE:
                return rewriteDeleteStatement(statement, context);
            // 表达式重写不在这里进行
        }
    }
}
```

### 配置简化

```java
public static class RewriteConfiguration {
    // 只保留语句级重写配置
    private boolean enableUpdateToSelectRewrite = true;
    private boolean enableDeleteToSelectRewrite = true;
    private boolean includeAllColumnsInUpdateSelect = false;
    
    // 移除了表达式级重写配置：
    // ❌ enableConstantFolding
    // ❌ enableFunctionRewriting
    // ❌ enableOrderByRewriting
}
```

## 关键实现细节

### 1. 保持表达式原样

```java
private SelectStatement createSourceSelectForUpdate(UpdateStatement update, RewriteContext context) {
    for (UpdateStatement.Assignment assignment : assignments) {
        String columnName = assignment.getColumnName();
        Expression newValue = assignment.getValueExpression();
        
        // 按照Calcite标准，不在这里重写表达式，保持原样
        selectItems.add(new SelectStatement.SelectItem(newValue, columnName));
    }
    
    // WHERE条件也保持原样
    return new SelectStatement(
        selectItems, fromTables, null, 
        whereCondition, // 保持原样，不重写
        null, null, null, null, false
    );
}
```

### 2. 递归处理子查询（仅语句级）

```java
private SelectStatement rewriteSelectStatement(SelectStatement select, RewriteContext context) {
    // 只处理子查询的语句级重写
    if (select.getFrom() != null) {
        for (SelectStatement.TableReference tableRef : select.getFrom()) {
            if (tableRef.isSubquery()) {
                // 递归重写子查询（语句级）
                SelectStatement rewrittenSubquery = rewriteSelectStatement(
                    tableRef.getSubquery(), context.createChildContext());
            }
        }
    }
    // 不对SELECT项、WHERE、ORDER BY等进行表达式重写
}
```

## 架构优势（修正后）

### 1. 完全符合Calcite标准

- ✅ 严格遵循`performUnconditionalRewrites`的实际范围
- ✅ 标准的UPDATE/DELETE转SELECT转换
- ✅ 表达式保持原样，留给后续阶段处理

### 2. 职责清晰

- ✅ 专注于语句结构重写
- ✅ 不越界处理表达式优化
- ✅ 与表达式重写阶段清晰分离

### 3. 易于理解和维护

- ✅ 功能范围明确有限
- ✅ 符合Calcite用户的期望
- ✅ 便于与标准Calcite集成

## 表达式重写的正确位置

在我们的SQL查询优化器中，表达式级重写应该在以下阶段进行：

### 1. 语义分析阶段
```java
// 在构建RelNode时进行表达式到Rex的转换和简化
RexBuilder rexBuilder = ...;
RexSimplify rexSimplify = new RexSimplify(rexBuilder, ...);
```

### 2. 逻辑优化阶段
```java
// 通过优化规则进行表达式重写
public class ConstantFoldingRule extends RelOptRule {
    // 常量折叠逻辑
}

public class FunctionRewriteRule extends RelOptRule {
    // 函数重写逻辑
}
```

### 3. 物理优化阶段
```java
// 根据执行引擎特性进行最终的表达式优化
```

## 总结

经过对Apache Calcite标准的深入理解，我们的`SqlRewriter`现在：

1. **严格限制范围**：只处理语句级重写（UPDATE/DELETE转SELECT）
2. **保持表达式原样**：不在无条件重写阶段修改表达式
3. **符合Calcite标准**：完全遵循`performUnconditionalRewrites`的实际做法
4. **职责清晰**：为后续的表达式重写阶段留出空间

这确保了我们的实现与Apache Calcite的标准做法保持一致，便于理解、维护和扩展。 