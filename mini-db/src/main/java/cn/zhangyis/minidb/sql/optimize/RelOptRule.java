package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.rel.*;

public abstract class RelOptRule {
    public abstract boolean matches(RelNode rel);
    public abstract RelNode apply(RelNode rel);
}

class RelOptRuleCall {
    private final RelNode rel;
    public RelOptRuleCall(RelNode rel) { this.rel = rel; }
    public RelNode rel(int pos) { return rel; }
}