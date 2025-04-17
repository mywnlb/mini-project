//package cn.zhangyis.sql.planner;
//
//public class LogicalPlanBuilder {
//    private final CatalogReader catalog;
//
//    // 将AST转换为逻辑计划树
//    public RelNode convert(SQLNode validatedNode) {
//        if (validatedNode instanceof SelectStatement) {
//            return convertSelect((SelectStatement) validatedNode);
//        } else if (validatedNode instanceof InsertStatement) {
//            return convertInsert((InsertStatement) validatedNode);
//        }
//        // ...
//    }
//
//    private RelNode convertSelect(SelectStatement select) {
//        // 构建逻辑算子树: Scan -> Join -> Filter -> Project -> Sort
//        RelNode scan = createScan(select.getFromClause());
//        RelNode filter = createFilter(scan, select.getWhereClause());
//        // ...
//        return project;
//    }
//
//    // 创建表扫描算子
//    private TableScan createScan(TableReference table) {
//        // ...
//    }
//
//    // 创建过滤算子
//    private Filter createFilter(RelNode input, Expression condition) {
//        // 将SQL表达式转换为关系表达式
//        RexNode rex = convertExpression(condition);
//        return new LogicalFilter(input, rex);
//    }
//}