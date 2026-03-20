package cn.zhangyis.minidb.sql.ast;

/**
 * 窗口函数: ROW_NUMBER() / RANK() / DENSE_RANK() OVER (PARTITION BY ... ORDER BY ...)
 */
public record SqlWindowFunction(
    String funcName,
    SqlNodeList partitionBy,
    SqlNodeList orderBy
) implements SqlNode {

    @Override
    public SqlKind kind() {
        return SqlKind.WINDOW_FUNCTION;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(funcName).append("() OVER (");
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
