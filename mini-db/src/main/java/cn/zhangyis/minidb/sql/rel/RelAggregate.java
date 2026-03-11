package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlAggCall;
import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import java.util.List;
import java.util.stream.Collectors;

public class RelAggregate extends RelNode {
    private final RelNode input;
    private final SqlNodeList groupKeys;
    private final List<SqlAggCall> aggCalls;

    public RelAggregate(RelNode input, SqlNodeList groupKeys, List<SqlAggCall> aggCalls) {
        this.input = input;
        this.groupKeys = groupKeys;
        this.aggCalls = aggCalls;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelAggregate(inputs.get(0), groupKeys, aggCalls);
    }

    @Override
    public String explain() {
        String groups = groupKeys != null
            ? groupKeys.nodes().stream().map(Object::toString).collect(Collectors.joining(", "))
            : "[]";
        String aggs = aggCalls.stream().map(SqlAggCall::toString).collect(Collectors.joining(", "));
        return "RelAggregate(groupBy=[" + groups + "], aggs=[" + aggs + "])\n" +
               "  " + input.explain();
    }

    public RelNode input() { return input; }
    public SqlNodeList groupKeys() { return groupKeys; }
    public List<SqlAggCall> aggCalls() { return aggCalls; }
}
