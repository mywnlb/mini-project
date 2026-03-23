package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.PartialAggCall;
import cn.zhangyis.minidb.sql.ast.SqlAggCall;
import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 最终聚合逻辑节点：合并 PartialAggregate 的中间结果
 */
public class RelFinalAggregate extends RelNode {
    private final RelNode input;
    private final SqlNodeList groupKeys;
    private final List<SqlAggCall> originalCalls;
    private final List<PartialAggCall> partialCalls;

    public RelFinalAggregate(RelNode input, SqlNodeList groupKeys,
                             List<SqlAggCall> originalCalls, List<PartialAggCall> partialCalls) {
        this.input = input;
        this.groupKeys = groupKeys;
        this.originalCalls = originalCalls;
        this.partialCalls = partialCalls;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelFinalAggregate(inputs.get(0), groupKeys, originalCalls, partialCalls);
    }

    @Override
    public String explain() {
        String groups = groupKeys != null
                ? groupKeys.nodes().stream().map(Object::toString).collect(Collectors.joining(", "))
                : "[]";
        String aggs = originalCalls.stream().map(SqlAggCall::toString).collect(Collectors.joining(", "));
        return "RelFinalAggregate(groupBy=[" + groups + "], finalAggs=[" + aggs + "])\n" +
                "  " + input.explain();
    }

    public RelNode input() { return input; }
    public SqlNodeList groupKeys() { return groupKeys; }
    public List<SqlAggCall> originalCalls() { return originalCalls; }
    public List<PartialAggCall> partialCalls() { return partialCalls; }
}
