//package cn.zhangyis.sql.optimizer.rules;
//
///**
// * 常量表达式优化规则
// */
//public class ConstantFilterOptimizationRule implements OptimizationRule {
//
//    @Override
//    public boolean matches(RelNode node) {
//        // 检查是否为Filter节点
//        return node instanceof LogicalFilter;
//    }
//
//    @Override
//    public RelNode apply(RelNode node, RuleContext context) {
//        LogicalFilter filter = (LogicalFilter) node;
//        RexNode condition = filter.getCondition();
//
//        // 检查是否为常量表达式
//        if (RexUtil.isConstantExpression(condition)) {
//            Boolean result = RexUtil.evaluateConstant(condition);
//
//            if (Boolean.TRUE.equals(result)) {
//                // 条件恒为true，移除过滤器
//                return filter.getInput();
//            } else {
//                // 条件恒为false，返回空结果集
//                return LogicalValues.createEmpty(filter.getCluster());
//            }
//        }
//
//        return filter; // 无变化
//    }
//}