# SQL 功能扩展 Phase 2 - 技术实现计划
# 日期: 2026-03-16
# 状态: 已审查

## 背景
当前 mini-db 已支持基础 SELECT、JOIN、谓词下推、常量折叠等功能。
本计划目标是实现以下 4 个重要 SQL 特性：

## 功能列表与优先级

### Phase 1: OFFSET 支持 (优先实现)
**目标**：支持 `LIMIT n OFFSET m` 语法

**新增文件**：
- `sql/ast/SqlOffset.java`

**修改文件**：
- `sql/parser/SqlParser.java`: `parseLimitClause()` → `parseLimitOffsetClause()`
- `sql/planner/SqlToRelConverter.java`: `convertSelect()`
- `sql/rel/RelLimit.java`: 增加 offset 字段
- `sql/exec/SortExec.java` 或 `OffsetExec.java`: 实现跳过逻辑
- `sql/optimize/RuleOptimizer.java`: 可能需要新规则

**核心不变式**：
- OFFSET 必须在 ORDER BY 之后生效才有确定性
- OFFSET 值必须为非负整数

---

### Phase 2: 标量函数框架
**目标**：建立可扩展的函数系统，支持 UPPER/LOWER/COALESCE/CAST 等

**新增文件**：
- `sql/functions/ScalarFunction.java`
- `sql/functions/FunctionRegistry.java`
- `sql/ast/SqlFunctionCall.java`
- `sql/functions/impl/UpperFunction.java` 等

**修改文件**：
- `sql/parser/SqlParser.java`: 函数调用解析
- `sql/validation/SqlValidator.java`: 函数参数检查
- `sql/exec/FilterExec.java`: `resolveValue()` 增加函数分支
- `sql/exec/ProjectExec.java`: 同上

---

### Phase 3: CASE WHEN 表达式
**目标**：支持条件表达式

**新增文件**：
- `sql/ast/SqlCase.java`

**修改文件**：
- `sql/parser/SqlParser.java`: `parseCaseExpression()`
- `sql/validation/SqlValidator.java`: 分支类型检查
- `sql/exec/FilterExec.java`: CASE 求值逻辑

---

### Phase 4: UNION / UNION ALL
**目标**：支持集合操作

**新增文件**：
- `sql/ast/SqlSetOperation.java`
- `sql/rel/RelUnion.java`
- `sql/exec/UnionExec.java`

**修改文件**：
- `sql/parser/SqlParser.java`
- `sql/planner/SqlToRelConverter.java`
- `sql/validation/SqlValidator.java`: 列类型对齐检查
- `sql/exec/PhysicalPlanner.java`

---

## 实施原则
1. 每次只实现一个 Phase，完成并测试后再进行下一个
2. 所有新节点必须完整实现 AST → RelNode → ExecNode 链路
3. Parser 修改必须谨慎，采用最小改动原则
4. 每个功能完成后必须补充测试用例

## 风险点
- Parser 成为修改热点
- 类型系统压力增大
- 执行引擎复杂度上升

---
**版本**: 2.0 (已审查)
**作者**: Claude
**下一步**: 等待用户指示从哪一项开始实现
