package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;

public class RelFilter extends RelNode {
    private final RelNode input;
    private final SqlNode condition;

    public RelFilter(RelNode input, SqlNode condition) {
        this.input = input;
        this.condition = condition;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelFilter(inputs.get(0), condition);
    }

    @Override
    public String explain() {
        return "RelFilter(condition=" + condition + ")\n" +
               "  " + input.explain();
    }

    public RelNode input() { return input; }
    public SqlNode condition() { return condition; }
}