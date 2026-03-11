package cn.zhangyis.minidb.sql.ast;

/**
 * 聚合函数调用节点: COUNT(col), SUM(col), AVG(col), MAX(col), MIN(col), COUNT(*)
 */
public record SqlAggCall(String funcName, SqlNode arg) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.AGG_CALL; }

    @Override
    public String toString() {
        return funcName + "(" + arg + ")";
    }
}
