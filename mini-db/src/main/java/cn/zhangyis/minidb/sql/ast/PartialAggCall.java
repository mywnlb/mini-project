package cn.zhangyis.minidb.sql.ast;

/**
 * 描述 Partial 阶段的聚合函数调用。
 *
 * @param funcName  partial 阶段的函数名 (COUNT/SUM/MAX/MIN)
 * @param arg       原始聚合参数
 * @param outputAlias  partial 输出列名 (如 _partial_sum_0)
 * @param originalFunc 原始聚合函数名 (用于 Final 阶段还原语义，如 AVG)
 */
public record PartialAggCall(
        String funcName,
        SqlNode arg,
        String outputAlias,
        String originalFunc
) implements SqlNode {
    @Override
    public SqlKind kind() {
        return SqlKind.AGG_CALL;
    }

    @Override
    public String toString() {
        return funcName + "(" + arg + ") AS " + outputAlias;
    }
}
