package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.rel.*;
import java.util.List;

public class RuleOptimizer {
    private final List<RelOptRule> rules;

    public RuleOptimizer() {
        this(false);
    }

    public RuleOptimizer(boolean enableIndexedLookup) {
        this.rules = enableIndexedLookup
            ? List.of(
                ConstantFoldingRule.INSTANCE,
                FilterProjectTransposeRule.INSTANCE,
                FilterJoinPushdownRule.INSTANCE,
                new PushFilterIntoScanRule(),
                JoinCommuteRule.INSTANCE
            )
            : List.of(
                ConstantFoldingRule.INSTANCE,
                FilterProjectTransposeRule.INSTANCE,
                FilterJoinPushdownRule.INSTANCE,
                PushFilterIntoScanRule.INSTANCE,
                JoinCommuteRule.INSTANCE
            );
    }

    public RelNode optimize(RelNode root) {
        // 先递归优化子节点，再对当前节点应用规则（bottom-up）
        RelNode current = optimizeChildren(root);

        boolean changed = true;
        int iterations = 0;
        while (changed && iterations++ < 20) {
            changed = false;
            for (RelOptRule rule : rules) {
                if (rule.matches(current)) {
                    RelNode next = rule.apply(current);
                    if (next != current) {
                        current = next;
                        changed = true;
                        System.out.println("Applied rule: " + rule.getClass().getSimpleName());
                        // 规则应用后重新优化子节点
                        current = optimizeChildren(current);
                        break;
                    }
                }
            }
        }
        return current;
    }

    private RelNode optimizeChildren(RelNode node) {
        if (node instanceof RelProject project) {
            RelNode opt = optimize(project.input());
            return opt != project.input() ? project.copy(List.of(opt)) : node;
        }
        if (node instanceof RelDistinct distinct) {
            RelNode opt = optimize(distinct.input());
            return opt != distinct.input() ? distinct.copy(List.of(opt)) : node;
        }
        if (node instanceof RelFilter filter) {
            RelNode opt = optimize(filter.input());
            return opt != filter.input() ? filter.copy(List.of(opt)) : node;
        }
        if (node instanceof RelJoin join) {
            RelNode optL = optimize(join.left());
            RelNode optR = optimize(join.right());
            return (optL != join.left() || optR != join.right())
                ? join.copy(List.of(optL, optR)) : node;
        }
        if (node instanceof RelAggregate agg) {
            RelNode opt = optimize(agg.input());
            return opt != agg.input() ? agg.copy(List.of(opt)) : node;
        }
        if (node instanceof RelSort sort) {
            RelNode opt = optimize(sort.input());
            return opt != sort.input() ? sort.copy(List.of(opt)) : node;
        }
        return node;
    }
}
