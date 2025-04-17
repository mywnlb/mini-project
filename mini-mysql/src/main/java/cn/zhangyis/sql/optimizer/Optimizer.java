//package cn.zhangyis.sql.optimizer;
//
//import java.util.List;
//
///**
// * @Description TODO
// * @Date 2025/3/13 20:02
// * @Created by libo
// */
//// 优化器实现
//public class Optimizer {
//    private final List<OptimizationRule> rules = new ArrayList<>();
//
//    public void addRule(OptimizationRule rule) {
//        rules.add(rule);
//    }
//
//    public RelNode optimize(RelNode plan) {
//        // 应用优化规则直到计划不再变化
//        boolean changed;
//        do {
//            changed = false;
//            for (OptimizationRule rule : rules) {
//                // 尝试应用规则
//                // ...
//            }
//        } while (changed);
//
//        return plan;
//    }
//}
