package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 两阶段聚合拆分规则：
 * RelAggregate → RelFinalAggregate(RelPartialAggregate(input))
 *
 * 聚合函数拆分映射：
 *   COUNT(x) → Partial: COUNT(x) as _partial_count_N,   Final: SUM(_partial_count_N)
 *   SUM(x)   → Partial: SUM(x) as _partial_sum_N,       Final: SUM(_partial_sum_N)
 *   AVG(x)   → Partial: SUM(x) as _partial_sum_N + COUNT(x) as _partial_count_N,
 *               Final:   SUM(_partial_sum_N) / SUM(_partial_count_N)
 *   MAX(x)   → Partial: MAX(x) as _partial_max_N,       Final: MAX(_partial_max_N)
 *   MIN(x)   → Partial: MIN(x) as _partial_min_N,       Final: MIN(_partial_min_N)
 *
 * 默认不在 RuleOptimizer 中注册，需手动启用。
 */
public class AggregateSplitRule extends RelOptRule {

    public static final AggregateSplitRule INSTANCE = new AggregateSplitRule();

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelAggregate)) return false;
        // 避免重复拆分：如果 input 已经是 PartialAggregate 则跳过
        RelAggregate agg = (RelAggregate) node;
        return !(agg.input() instanceof RelPartialAggregate);
    }

    @Override
    public RelNode apply(RelNode node) {
        RelAggregate agg = (RelAggregate) node;
        List<SqlAggCall> originalCalls = agg.aggCalls();
        List<PartialAggCall> partialCalls = new ArrayList<>();

        int idx = 0;
        for (SqlAggCall call : originalCalls) {
            String func = call.funcName().toUpperCase();
            switch (func) {
                case "COUNT" -> {
                    String alias = "_partial_count_" + idx;
                    partialCalls.add(new PartialAggCall("COUNT", call.arg(), alias, "COUNT"));
                    idx++;
                }
                case "SUM" -> {
                    String alias = "_partial_sum_" + idx;
                    partialCalls.add(new PartialAggCall("SUM", call.arg(), alias, "SUM"));
                    idx++;
                }
                case "AVG" -> {
                    // AVG 拆为 SUM + COUNT
                    String sumAlias = "_partial_sum_" + idx;
                    partialCalls.add(new PartialAggCall("SUM", call.arg(), sumAlias, "AVG"));
                    idx++;
                    String countAlias = "_partial_count_" + idx;
                    partialCalls.add(new PartialAggCall("COUNT", call.arg(), countAlias, "AVG"));
                    idx++;
                }
                case "MAX" -> {
                    String alias = "_partial_max_" + idx;
                    partialCalls.add(new PartialAggCall("MAX", call.arg(), alias, "MAX"));
                    idx++;
                }
                case "MIN" -> {
                    String alias = "_partial_min_" + idx;
                    partialCalls.add(new PartialAggCall("MIN", call.arg(), alias, "MIN"));
                    idx++;
                }
                default -> {
                    // 不支持的聚合函数，不拆分
                    return node;
                }
            }
        }

        RelPartialAggregate partial = new RelPartialAggregate(agg.input(), agg.groupKeys(), partialCalls);
        return new RelFinalAggregate(partial, agg.groupKeys(), originalCalls, partialCalls);
    }
}
