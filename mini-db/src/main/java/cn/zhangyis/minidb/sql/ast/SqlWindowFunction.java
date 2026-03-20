package cn.zhangyis.minidb.sql.ast;

/**
 * 窗口函数:
 * - 排名函数: ROW_NUMBER() / RANK() / DENSE_RANK() OVER (PARTITION BY ... ORDER BY ...)
 * - 聚合窗口: SUM(expr) / COUNT(expr|*) / AVG(expr) / MIN(expr) / MAX(expr) OVER (...)
 */
public record SqlWindowFunction(
    String funcName,
    SqlNode arg,          // 聚合窗口的参数（如 SUM(amount) 中的 amount），排名函数为 null
    SqlNodeList partitionBy,
    SqlNodeList orderBy
) implements SqlNode {

    /** 兼容旧构造：排名函数无 arg */
    public SqlWindowFunction(String funcName, SqlNodeList partitionBy, SqlNodeList orderBy) {
        this(funcName, null, partitionBy, orderBy);
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
