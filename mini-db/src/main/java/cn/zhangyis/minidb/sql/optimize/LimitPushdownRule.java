package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.SqlLiteral;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.rel.*;

/**
 * LIMIT 下推规则：将 LIMIT 穿过 Project 和 Filter 下推。
 *
 * <p>转换前: RelSort(limit=N) → RelProject → RelFilter → RelScan
 * <p>转换后: RelSort(limit=N) → RelProject → RelFilter → RelSort(limit=N) → RelScan
 *
 * <p>这使得底层扫描可以提前截断，减少中间结果行数。
 * 注意：仅在无 ORDER BY 或 ORDER BY 可传播时才安全下推。
 */
public class LimitPushdownRule extends RelOptRule {

    public static final LimitPushdownRule INSTANCE = new LimitPushdownRule();

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelSort sort)) return false;
        if (sort.limit() == null) return false;
        // 仅在无 ORDER BY 时下推（有 ORDER BY 时下推 LIMIT 可能改变结果）
        if (sort.orderBy() != null) return false;
        // 子节点必须是 Project 或 Filter
        RelNode child = sort.input();
        return child instanceof RelProject || child instanceof RelFilter;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelSort sort = (RelSort) node;
        SqlNode limitNode = sort.limit();
        SqlNode offsetNode = sort.offset();

        // 计算下推的有效 limit = limit + offset
        int limit = intValue(limitNode);
        int offset = offsetNode != null ? intValue(offsetNode) : 0;
        int pushdownLimit = limit + offset;
        SqlNode pushdownLimitNode = new SqlLiteral(String.valueOf(pushdownLimit),
            cn.zhangyis.minidb.sql.types.SqlType.INT32);

        RelNode child = sort.input();

        if (child instanceof RelProject project) {
            RelNode innerInput = project.input();
            // 避免重复下推（如果内部已有 RelSort limit）
            if (innerInput instanceof RelSort) return node;
            RelNode limitedInput = new RelSort(innerInput, null, pushdownLimitNode, null);
            return new RelSort(new RelProject(limitedInput, project.projection()),
                sort.orderBy(), sort.limit(), sort.offset());
        }

        if (child instanceof RelFilter filter) {
            RelNode innerInput = filter.input();
            if (innerInput instanceof RelSort) return node;
            // Filter 后 LIMIT 不能精确下推（Filter 可能过滤掉行），
            // 但我们可以给底层加一个宽松的 LIMIT 提示
            // 实际中只在 Filter 选择率高时有效，这里简单跳过
            return node;
        }

        return node;
    }

    private int intValue(SqlNode node) {
        if (node instanceof SqlLiteral lit) {
            return Integer.parseInt(lit.value());
        }
        return Integer.MAX_VALUE;
    }
}
