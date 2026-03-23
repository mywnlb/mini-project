package cn.zhangyis.minidb.sql.ast;

/**
 * 窗口函数:
 * - 排名函数: ROW_NUMBER() / RANK() / DENSE_RANK() / PERCENT_RANK() / CUME_DIST() OVER (...)
 * - 聚合窗口: SUM(expr) / COUNT(expr|*) / AVG(expr) / MIN(expr) / MAX(expr) OVER (...)
 * - 偏移函数: LAG(col [, offset [, default]]) / LEAD(col [, offset [, default]]) OVER (...)
 * - 分桶函数: NTILE(n) OVER (...)
 */
public record SqlWindowFunction(
    String funcName,
    SqlNode arg,          // 第一个参数（如 SUM(amount) 中的 amount），排名函数为 null
    SqlNodeList partitionBy,
    SqlNodeList orderBy,
    SqlNodeList extraArgs // LAG/LEAD 的完整参数列表 (col, offset, default)，NTILE 的 (n)
) implements SqlNode {

    /** 兼容旧构造：排名函数无 arg */
    public SqlWindowFunction(String funcName, SqlNodeList partitionBy, SqlNodeList orderBy) {
        this(funcName, null, partitionBy, orderBy, null);
    }

    /** 聚合窗口构造：无 extraArgs */
    public SqlWindowFunction(String funcName, SqlNode arg, SqlNodeList partitionBy, SqlNodeList orderBy) {
        this(funcName, arg, partitionBy, orderBy, null);
    }

    @Override
    public SqlKind kind() {
        return SqlKind.WINDOW_FUNCTION;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(funcName).append("(");
        if (arg != null) {
            sb.append(arg);
        }
        sb.append(") OVER (");
        if (partitionBy != null && partitionBy.size() > 0) {
            sb.append("PARTITION BY ");
            for (int i = 0; i < partitionBy.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(partitionBy.get(i));
            }
        }
        if (orderBy != null && orderBy.size() > 0) {
            if (partitionBy != null && partitionBy.size() > 0) sb.append(" ");
            sb.append("ORDER BY ");
            for (int i = 0; i < orderBy.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(orderBy.get(i));
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
