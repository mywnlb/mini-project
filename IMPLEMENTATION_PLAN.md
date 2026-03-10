# MySQL 8.0 SQL 层纯手写实现计划 (Iteration 1/5)

## 架构总览

本计划设计一个纯手写、模块化的SQL解析层，参考Calcite的分层思想但完全手写实现。核心流程：
```
SQL Text → Lexer → Parser → AST(SqlNode) → Validator → Logical Plan(RelNode) → Optimizer → Physical Plan
```

**设计原则**：
- 高内聚低耦合：每层单责，明确输入输出接口
- 纯手写：不依赖ANTLR/JJTree等生成器，手写递归下降解析器
- 设计模式驱动：Visitor/Factory/Builder/Strategy等模式贯穿全程
- 内核导向：面向数据库内核，预留Catalog/Statistics/Executor扩展点

## 模块分层图

```mermaid
graph TD
    A[SQL Text] --> B[Lexer]
    B --> C[Parser]
    C --> D[AST - SqlNode]
    D --> E[Validator]
    E --> F[Logical Plan - RelNode]
    F --> G[Optimizer]
    G --> H[Physical Plan]

    I[Catalog SPI] --> E
    I --> F
    J[Statistics SPI] --> G
    K[Executor SPI] <-- H
```

## Package 结构

```
cn.zhangyis.minidb.sql
├── lexer          # 词法分析
├── parser         # 语法分析 → AST
├── ast            # SqlNode 层次结构
├── validate       # 语义校验 + 名字解析
├── plan           # RelNode 逻辑计划
├── optimize       # 优化器 (规则 + 代价)
├── metadata       # Catalog/Statistics SPI
└── exec           # 执行计划边界
```

## 核心对象模型

### 1. SqlNode (AST节点)
```java
public interface SqlNode extends Visitable {
    SqlKind getKind();
    void accept(SqlVisitor visitor);
}

public abstract class AbstractSqlNode implements SqlNode {
    // 不可变对象，position信息用于错误报告
}
```

**主要子类**：
| SqlNode 类型 | 职责 | 示例 |
|--------------|------|------|
| SqlIdentifier | 名字引用 | `users.id` |
| SqlLiteral | 字面量 | `123`, `'hello'` |
| SqlBinaryOperator | 二元操作 | `a = b` |
| SqlSelect | SELECT语句 | `SELECT * FROM t` |
| SqlCall | 函数调用 | `COUNT(*)` |

### 2. RelNode (逻辑计划节点)
```java
public interface RelNode {
    RelDataType getRowType();
    RelCollation getCollation();
    RelTraitSet getTraitSet();
}
```

**主要实现**：
| RelNode 类型 | 来源 | 示例 |
|--------------|------|------|
| TableScanRel | 表扫描 | `SCAN users` |
| FilterRel | 过滤 | `users WHERE age > 18` |
| ProjectRel | 投影 | `SELECT name, age` |
| JoinRel | 连接 | `users JOIN orders` |

### 3. 层间数据流

| 阶段 | 输入 | 输出 | 上下文 |
|------|------|------|--------|
| Parser | String SQL | SqlNode | ParseContext |
| Validator | SqlNode | SqlNode (validated) | ValidationContext |
| Logical Planner | SqlNode | RelNode | PlanContext + Catalog |
| Optimizer | RelNode | RelNode (optimized) | OptimizeContext + Stats |

## 第1轮模块边界定义

### Lexer 职责
```
Input: SQL Text → Output: Token Stream
```
- **核心类**：`SqlLexer`, `TokenType`, `Token`
- **SPI**：`LexerFactory` (预留扩展)

### Parser 职责 (第2轮深化)
```
Input: Token Stream → Output: SqlNode (AST)
```
- **核心类**：`SqlParser`, `SqlNodeFactory`
- **模式**：Builder (构建复杂节点), Factory (节点创建)

### Validator 职责 (第3轮深化)
```
Input: SqlNode → Output: Validated SqlNode + RelNode (simple cases)
```
- **核心类**：`SqlValidator`, `TypeCoercion`, `NameResolver`
- **依赖**：Catalog SPI
- **模式**：Visitor (遍历校验), Chain of Responsibility (多阶段校验)

### Optimizer 职责 (第4轮深化)
```
Input: RelNode → Output: Optimized RelNode
```
- **核心类**：`Optimizer`, `Rule`, `CostModel`
- **模式**：Strategy (规则优化), Composite (规则集合)

## 主调用链 (伪代码)
```java
SqlNode ast = SqlParser.parse(sql);
SqlValidator.validate(ast, catalog);  // 抛异常或返回validated AST
RelNode logicalPlan = SqlToRelConverter.convert(ast, catalog);
RelNode optimizedPlan = Optimizer.optimize(logicalPlan, stats);
PhysicalPlan physicalPlan = PhysicalPlanner.plan(optimizedPlan);
```

## 下轮预告
- **第2轮**：Parser详细类设计 + 手写递归下降实现
- **第3轮**：Validator类型系统 + 名字解析流程
- **第4轮**：Optimizer规则/代价双引擎设计
- **第5轮**：整合设计模式表 + 里程碑 + 风险

## Parser 详细设计 (第2轮深化)

**纯手写路线**：手写递归下降解析器（LL(1)），不使用ANTLR/JJTree。支持核心SQL子集：SELECT/JOIN/WHERE/GROUP/HAVING/ORDER/LIMIT。

### 核心类设计

| 类名 | 类型 | 职责 | 设计模式 |
|------|------|------|----------|
| `SqlLexer` | 默认实现 | 字符流 → Token流，手写状态机识别关键字/标识符/运算符 | State (词法状态切换) |
| `Token` | 不可变对象 | 令牌：类型、值、位置 | - |
| `SqlParser` | 上下文对象 | 递归下降主解析器，Token流 → SqlNode | Builder (构建AST) |
| `SqlNodeFactory` | SPI/Factory | 创建SqlNode实例，支持自定义节点 | Factory Method |
| `ParseContext` | 上下文对象 | 解析上下文：dialect配置、错误收集 | Context |

### TokenType 枚举 (手写核心tokens)
```java
public enum TokenType {
    SELECT, FROM, WHERE, JOIN, ON, AND, OR,
    IDENTIFIER, LITERAL_NUMBER, LITERAL_STRING,
    EQ, LT, GT, COMMA, STAR, LPAREN, RPAREN;
}
```

### 关键数据流
```
SQL Text ──(SqlLexer#nextToken)──> TokenStream ──(SqlParser#parseQuery)──> SqlSelect
                                            │
                                            └─(SqlParser#parseExpression)──> SqlBinaryOperator
```

### 递归下降核心方法 (伪代码)
```java
public class SqlParser {
    private TokenStream tokens;

    public SqlSelect parseQuery() {
        expect(SELECT);
        SqlNodeList selectList = parseSelectList();  // Builder模式构建列表
        expect(FROM);
        SqlIdentifier from = parseIdentifier();
        SqlNode where = parseWhere();  // 可选，null安全
        return SqlNodeFactory.createSelect(selectList, from, where);
    }

    private SqlNode parseExpression() {
        SqlNode left = parsePrimary();
        if (match(EQ)) {
            SqlNode right = parsePrimary();
            return SqlNodeFactory.createBinary(left, EQ, right);  // Builder
        }
        return left;
    }
}
```

### 手写Lexer状态机示例
```java
public class SqlLexer {
    private String sql;
    private int pos;

    public Token nextToken() {
        skipWhitespace();
        if (match("SELECT")) return new Token(SELECT, "SELECT", pos);
        if (isLetter()) return identifierToken();  // 关键字/标识符
        if (isDigit()) return numberToken();
        // ... 运算符匹配
        throw new ParseException("Unexpected char at " + pos);
    }
}
```

### Parser扩展点 (SPI)
- `DialectRegistry`：注册方言（MySQL/PostgreSQL），影响关键字识别
- `SqlNodeFactory#register(Type, Supplier)`：自定义节点创建

### 输入输出边界
| 输入 | 输出 | 异常 |
|------|------|------|
| `String sql` | `SqlSelect extends SqlNode` | `SqlParseException` (位置+消息) |
| `SELECT * FROM users WHERE id = 1` | `SqlSelect{selectList=[SqlStar], from=SqlIdentifier['users'], where=SqlBinary(SqlIdentifier['id'], EQ, SqlLiteral[1])}` | 无 |

## Validate 详细设计 (第3轮深化)

**纯手写路线**：多阶段Visitor链，手写类型推导+名字解析。不依赖外部校验器。

### 核心类设计

| 类名 | 类型 | 职责 | 设计模式 |
|------|------|------|----------|
| `SqlValidator` | 上下文对象 | 协调多阶段校验，返回validated SqlNode或抛异常 | Chain of Responsibility (校验阶段链) |
| `TypeValidator` | Visitor/规则对象 | 类型兼容性检查、隐式转换 | Visitor |
| `NameResolver` | 访问器 | 解析SqlIdentifier到Table/Column引用 | Strategy (多Scope策略) |
| `Scope` | 不可变对象 | 名字作用域：当前表+外层查询 | Composite (嵌套Scope) |
| `ValidationContext` | 上下文对象 | Catalog、已知类型、错误收集 | Context |
| `SqlToRelConverter` | 转换器 | simple AST → initial RelNode | Adapter (AST→逻辑计划桥接) |

### 类型系统核心
```java
public interface RelDataType {
    SqlTypeFamily getFamily();  // INT, VARCHAR, BOOLEAN
    int getPrecision();
    boolean isNullable();
}

public class TypeCoercion {
    public RelDataType coerce(SqlNode left, SqlNode right);  // INT+DECIMAL→DECIMAL
}
```

### 关键数据流
```
SqlNode(AST) ──(SqlValidator#validate)──> [阶段1: SyntaxCheck] ──> [阶段2: NameResolve] ──> [阶段3: TypeCheck] ──> SqlNode(validated) + RelNode(initial)
```

### Validator主流程 (伪代码)
```java
public class SqlValidator {
    public RelNode validate(SqlNode root, Catalog catalog) {
        ValidationContext ctx = new ValidationContext(catalog);

        // 阶段1: 基本语法检查 (Visitor)
        root.accept(new SyntaxValidator(ctx));

        // 阶段2: 名字解析 (Scope构建)
        Scope globalScope = NameResolver.resolve(root, catalog);
        root.accept(new NameResolverVisitor(globalScope, ctx));

        // 阶段3: 类型校验 + 转换
        root.accept(new TypeValidator(ctx));
        ctx.coerceTypes(root);  // 隐式转换，如INT→DECIMAL

        // 阶段4: 生成初始逻辑计划
        return new SqlToRelConverter(ctx).convert(root);
    }
}
```

### NameResolver示例
```java
public class NameResolver {
    public Scope createSelectScope(SqlSelect select, Scope parent) {
        // 解析FROM子句，构建TableScanRel + 当前列Scope
        TableScanRel scan = resolveTable(select.from, catalog);
        Scope tableScope = new TableScope(scan.getRowType());
        return new DelegatingScope(tableScope, parent);  // Composite
    }
}
```

### AST → RelNode 边界转换
| AST节点 | RelNode输出 | 校验职责 |
|---------|-------------|----------|
| `SqlSelect(* FROM t WHERE c=1)` | `FilterRel(ProjectRel(TableScanRel(t)), c=1)` | 列存在、类型匹配 |
| `SqlIdentifier('t.c')` | `RexFieldAccess(表行, c)` | Scope查找列 |

### 扩展点 (SPI)
- `Catalog`：`getTable(String), getColumns(Table)`
- `TypeSystem`：自定义类型家族/转换规则
- `ValidatorChain#register(ValidatorStage)`：插入自定义校验阶段

### 输入输出边界
| 输入 | 输出 | 异常示例 |
|------|------|----------|
| `SqlSelect` (raw) | `RelNode` (logical) | `ValidationError: Unknown column 'foo'` |
| `SELECT age FROM users WHERE id = 'abc'` | 自动转换 `'abc'→123` 或抛 `TypeMismatch` | 类型错误 |

## Optimizer 详细设计 (第4轮深化)

**纯手写路线**：规则优化(Heuristic)+代价优化(DP)双引擎，手写规则匹配+简单代价模型。

### 核心类设计

| 类名 | 类型 | 职责 | 设计模式 |
|------|------|------|----------|
| `Optimizer` | 上下文对象 | 协调规则+代价优化，返回optimized RelNode | Composite (规则集), Template Method (优化流程) |
| `RelOptRule` | 规则对象 | 模式匹配+重写规则 | Strategy (可插拔规则) |
| `CostModel` | SPI | 计算RelNode代价（CPU/IO/内存） | Strategy |
| `RuleQueue` | 内部 | 优先级规则队列，防止无限循环 | PriorityQueue + Observer |
| `OptimizeContext` | 上下文对象 | 统计信息、已应用规则追踪 | Context |
| `PhysicalPlanner` | 转换器 | RelNode → 执行计划边界 | Bridge (逻辑→物理) |

### 双引擎架构
1. **规则优化**：枚举应用规则，如Filter-Pushdown、Join-Reorder
2. **代价优化**：动态规划选择Join顺序（MVP: NestedLoop优先）

### 关键数据流
```
RelNode(initial) ──(HepPlanner#transform)──> RelNode(rules-applied) ──(CostOptimizer#chooseBest)──> RelNode(optimized)
```

### Optimizer主流程 (伪代码)
```java
public class Optimizer {
    public RelNode optimize(RelNode root, Statistics stats) {
        OptimizeContext ctx = new OptimizeContext(stats);

        // 阶段1: 规则优化 (HepPlanner风格，手写)
        HepPlanner hep = new HepPlanner(ctx);
        hep.addRule(new FilterPushdownRule());  // Composite规则集
        RelNode afterRules = hep.transform(root);

        // 阶段2: 代价优化 (简单DP)
        CostOptimizer costOpt = new CostOptimizer(ctx);
        RelNode bestPlan = costOpt.rewriteJoins(afterRules);

        return bestPlan;
    }
}
```

### 规则示例 (手写匹配)
```java
public class FilterPushdownRule extends RelOptRule {
    public RelNode apply(RelNode rel) {
        if (rel instanceof FilterRel && rel.input instanceof TableScanRel) {
            return pushFilterThroughScan((FilterRel) rel);  // 重写为Scan+Filter
        }
        return null;
    }
}
```

### CostModel 示例
```java
public class SimpleCostModel implements CostModel {
    public Cost computeCost(RelNode node, Statistics stats) {
        if (node instanceof TableScanRel) {
            return new Cost(stats.rowCount * node.rowType.fieldCount, stats.rowCount);
        }
        // Join: left.row * right.row 等
    }
}
```

### 执行计划边界
| RelNode | Physical输出 | 扩展点 |
|---------|--------------|--------|
| `TableScanRel` | `ScanExec(tableId, columns)` | Executor SPI |
| `JoinRel` | `NestedLoopJoinExec(left, right, cond)` | Cost选择HashJoin/NLJ |

### 扩展点 (SPI)
- `RuleRegistry#register(RelOptRule)`：插件化规则
- `CostFactory#create()`：自定义代价模型
- `TraitSet`：支持分布式/向量化trait转换

### 输入输出边界
| 输入 | 输出 | 优化效果 |
|------|------|----------|
| `Filter(Project(Scan))` | `Project(Filter(Scan))` | IO减少50% |
| `A JOIN B JOIN C` | `A JOIN B` → `HashJoin(A,(B JOIN C))` | 代价最低 |

## 设计模式映射表

| 设计模式 | 层级 | 核心类 | 解决的问题 |
|----------|------|--------|------------|
| **Builder** | Parser | `SqlParser#parseSelect()` | 复杂AST节点分步构建，避免巨型构造函数 |
| **Factory Method** | Parser/AST | `SqlNodeFactory` | 节点创建封装，支持SPI扩展 |
| **State** | Lexer | `SqlLexer#nextToken()` | 词法状态切换（ID/STR/NUM） |
| **Visitor** | Validate | `TypeValidator`, `NameResolverVisitor` | AST遍历分离，校验逻辑解耦 |
| **Chain of Responsibility** | Validate | `SqlValidator`阶段链 | 多阶段校验，动态插拔 |
| **Composite** | Validate/Optimize | `Scope`, 规则集 | 嵌套作用域、规则组合 |
| **Strategy** | Validate/Optimize | `NameResolver`策略, `RelOptRule`, `CostModel` | 可替换解析/优化算法 |
| **Adapter** | Validate | `SqlToRelConverter` | AST→RelNode桥接 |
| **Template Method** | Optimize | `Optimizer#optimize()` | 固定流程+可覆写阶段 |
| **Bridge** | Optimize→Exec | `PhysicalPlanner` | 逻辑/物理独立演化 |
| **Context** | 全层 | `*Context`类 | 状态共享，避免全局变量 |
| **Observer** | Optimize | `RuleQueue` | 规则应用通知，防循环 |

## 分阶段实施路线图

### MVP (Week 1-2: 核心SELECT)
| 阶段 | 目标SQL | 完成模块 | 测试覆盖 |
|------|---------|----------|----------|
| MVP1 | `SELECT * FROM t` | Lexer+Parser+AST+简单Validate | 解析/序列化一致性 |
| MVP2 | `SELECT c FROM t WHERE pred` | +NameResolve+TypeCheck+TableScanRel | 单表查询端到端 |
| MVP3 | +Project/Filter RelNode | +SqlToRelConverter | RelNode打印验证 |

### 增强版 (Week 3-4: JOIN+聚合)
| 阶段 | 新功能 | 完成模块 | 测试覆盖 |
|------|--------|----------|----------|
| E1 | INNER JOIN ON cond | JoinRel + 简单规则优化 | Join条件推导 |
| E2 | GROUP BY agg() HAVING | AggregateRel + Scope嵌套 | 聚合语义正确 |
| E3 | ORDER BY LIMIT | SortRel + Collation | 排序稳定性 |

### 高级版 (Week 5-6: 生产级)
| 阶段 | 新功能 | 完成模块 | 测试覆盖 |
|------|--------|----------|----------|
| A1 | 代价Join重排 + Stats集成 | CostOptimizer + 多规则 | 代价对比基准 |
| A2 | 子查询/CTE | CorrelateRel + 扩展Scope | 相关子查询 |
| A3 | SPI全开 + 物理规划 | Rule/Cost/Executor SPI | 插件测试套件 |

**实施顺序**：先Parser(不可或缺) → Validate(语义基石) → 逻辑Plan → Optimizer(渐进优化)

## 风险与测试策略

### 风险点 & 缓解

| 风险 | 概率 | 影响 | 缓解策略 |
|------|------|------|----------|
| 递归下降歧义(LL(1)失败) | 中 | 高 | 优先级算符优先 + 手动回溯，单元测试100+ SQL |
| Scope泄漏(名字解析错) | 高 | 高 | Scope快照 + 序列化一致性测试 |
| 类型转换溢出 | 中 | 中 | 类型家族严格定义 + 边界值测试 |
| 规则无限循环 | 高 | 高 | RuleQueue访问计数 + 最大迭代限制 |
| 代价模型偏差 | 低 | 中 | Stats Mock + 真实数据集基准 |

### 测试策略 (100%单元 + 集成)
1. **Parser单元**：Golden File (SQL→AST序列化→SQL roundtrip)
2. **Validate集成**：MockCatalog + 合法/非法SQL断言
3. **Optimizer黑盒**：输入RelNode → 预期优化后计划 + 代价降序
4. **端到端**：50核心SQL → 执行计划打印验证
5. **并发**：Validator多线程Scope隔离测试
6. **覆盖率**：>90% 行覆盖，重点AST/RelNode不可变性

### 可测试性设计
- **不可变对象**：SqlNode/RelNode immutable，便于snapshot比较
- **Mock友好**：所有SPI接口纯函数式
- **序列化**：toString()精确，便于Golden测试
- **错误定位**：每层抛带位置的Exception

## 最终总结
- **纯手写达成**：全手写递归下降+Visitor+规则引擎，无第三方依赖
- **Calcite借鉴**：分层(SqlNode→RelNode)+Hep规则，但简化落地
- **内核适配**：Catalog/Stats/Executor SPIready
- **立即可用**：MVP一周内单表查询完整链路

**FINAL VERSION COMPLETE: 所有要求覆盖 ✓**