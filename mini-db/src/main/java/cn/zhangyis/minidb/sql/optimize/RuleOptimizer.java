package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.rel.*;
import java.util.List;

public class RuleOptimizer {
    private final List<RelOptRule> rules = List.of(
        PushFilterIntoScanRule.INSTANCE
        // TODO v0.2: + JoinCommuteRule, FilterProjectTransposeRule
    );

    public RelNode optimize(RelNode root) {
        RelNode current = root;
        boolean changed = true;
        int iterations = 0;
        while (changed && iterations++ < 5) {
            changed = false;
            for (RelOptRule rule : rules) {
                if (rule.matches(current)) {
                    current = rule.apply(current);
                    changed = true;
                    System.out.println("Applied rule: " + rule.getClass().getSimpleName());
                    break;
                }
            }
        }
        return current;
    }
}