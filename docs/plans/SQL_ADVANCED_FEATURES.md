# SQL 高级特性扩展计划
# 日期: 2026-03-16
# 版本: v2.0 (已审查)

## 待实现特性（优先级排序）

### 7. OFFSET 支持
**状态**: 未开始
**核心需求**: 支持 `LIMIT n OFFSET m` 语法
**实现路径**:
- 新增 `SqlOffset.java`
- 修改 `SqlParser.parseLimitClause()`
- 扩展 `RelLimit` 或新增 `RelOffset`
- 在 `SortExec` 或独立 Exec 中实现跳过逻辑

### 8. UNION / UNION ALL
**状态**: 未开始
**核心需求**: 支持集合操作
**实现路径**:
- 新增 `SqlSetOperation.java`
- 新增 `RelUnion.java` + `UnionExec.java`
- 处理列类型对齐和去重逻辑

### 9. CASE WHEN 表达式
**状态**: 未开始
**核心需求**: 支持条件分支表达式jixu
**实现路径**:
- 新增 `SqlCase.java`
- 在 Parser 中增加 `parseCaseExpression()`
- 在执行层增加 CASE 求值分支

### 10. 标量函数支持
**状态**: 未开始
**核心需求**: UPPER, LOWER, COALESCE, CAST 等
**实现路径**:
- 建立 `FunctionRegistry` + `ScalarFunction` 框架
- 修改表达式求值系统（FilterExec/ProjectExec）
- 首批实现 4-5 个常用函数

## 实施原则（强制遵守）
1. 每次只实现一个特性，完成测试后再进行下一个
2. 所有新语法必须有完整的 AST → RelNode → ExecNode 链路
3. Parser 修改必须最小化，避免回归
4. 每个特性完成后必须更新测试用例
5. 遵循 Fail-First 设计原则

## 文件修改影响评估
- **高风险文件**: SqlParser.java, SqlValidator.java, PhysicalPlanner.java
- **基础设施文件**: 需要新增函数注册和表达式求值框架

**下一步行动**: 等待用户指示从第7项（OFFSET）开始实现

---
文档状态: 已写入
最后更新: 2026-03-16
