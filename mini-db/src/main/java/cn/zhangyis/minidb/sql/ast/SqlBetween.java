package cn.zhangyis.minidb.sql.ast;

/**
 * BETWEEN 谓词：expr BETWEEN low AND high
 */
public record SqlBetween(SqlNode expr, SqlNode low, SqlNode high) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.BETWEEN; }
}
