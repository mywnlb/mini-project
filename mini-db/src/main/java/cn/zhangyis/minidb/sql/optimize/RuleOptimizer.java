package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.rel.*;
import java.util.ArrayList;
import java.util.List;

public class RuleOptimizer {
    private final List<RelOptRule> rules;

    public RuleOptimizer() {
        this(false, null);
    }

    public RuleOptimizer(boolean enableIndexedLookup) {
        this(enableIndexedLookup, null);
    }

    public RuleOptimizer(boolean enableIndexedLookup, CatalogSpi catalog) {
        this(enableIndexedLookup, catalog, null);
    }

    public RuleOptimizer(boolean enableIndexedLookup, CatalogSpi catalog, CostModel costModel) {
        List<RelOptRule> ruleList = new ArrayList<>();
        // SubqueryUnnestingRule 必须排在 FilterJoinPushdownRule 之前
        if (catalog != null) {
            ruleList.add(new SubqueryUnnestingRule(catalog));
        }
        ruleList.add(ConstantFoldingRule.INSTANCE);
        ruleList.add(FilterProjectTransposeRule.INSTANCE);
        ruleList.add(FilterJoinPushdownRule.INSTANCE);
        ruleList.add(enableIndexedLookup ? new PushFilterIntoScanRule() : PushFilterIntoScanRule.INSTANCE);
        // JOIN 重排序（在 FilterJoinPushdown 之后、JoinCommute 之前）
        if (costModel != null) {
            ruleList.add(new JoinReorderRule(costModel));
        }
        ruleList.add(JoinCommuteRule.INSTANCE);
        ruleList.add(ProjectionPruningRule.INSTANCE);
        ruleList.add(LimitPushdownRule.INSTANCE);
        this.rules = List.copyOf(ruleList);
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
        if (node instanceof RelSemiJoin semi) {
            RelNode optL = optimize(semi.left());
            RelNode optR = optimize(semi.right());
            return (optL != semi.left() || optR != semi.right())
                ? semi.copy(List.of(optL, optR)) : node;
        }
        if (node instanceof RelAntiJoin anti) {
            RelNode optL = optimize(anti.left());
            RelNode optR = optimize(anti.right());
            return (optL != anti.left() || optR != anti.right())
                ? anti.copy(List.of(optL, optR)) : node;
        }
        return node;
    }
}
