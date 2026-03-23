package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.PartialAggCall;
import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 部分聚合逻辑节点：输出 group keys + partial 中间结果列
 */
public class RelPartialAggregate extends RelNode {
    private final RelNode input;
    private final SqlNodeList groupKeys;
    private final List<PartialAggCall> partialCalls;

    public RelPartialAggregate(RelNode input, SqlNodeList groupKeys, List<PartialAggCall> partialCalls) {
        this.input = input;
        this.groupKeys = groupKeys;
        this.partialCalls = partialCalls;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelPartialAggregate(inputs.get(0), groupKeys, partialCalls);
    }

    @Override
    public String explain() {
        String groups = groupKeys != null
                ? groupKeys.nodes().stream().map(Object::toString).collect(Collectors.joining(", "))
                : "[]";
        String aggs = partialCalls.stream().map(PartialAggCall::toString).collect(Collectors.joining(", "));
        return "RelPartialAggregate(groupBy=[" + groups + "], partialAggs=[" + aggs + "])\n" +
                "  " + input.explain();
    }

    public RelNode input() { return input; }
    public SqlNodeList groupKeys() { return groupKeys; }
    public List<PartialAggCall> partialCalls() { return partialCalls; }
}
