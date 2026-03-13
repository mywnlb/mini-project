package cn.zhangyis.minidb.sql.ast;

/**
 * IN 谓词：expr IN (v1, v2, ...)
 */
public record SqlInList(SqlNode expr, SqlNodeList values) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.IN; }
}
