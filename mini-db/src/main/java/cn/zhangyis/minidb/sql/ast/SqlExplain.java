package cn.zhangyis.minidb.sql.ast;

/**
 * EXPLAIN <query> 语句的 AST 节点
 */
public record SqlExplain(SqlNode query) implements SqlNode {
    @Override
    public SqlKind kind() {
        return SqlKind.EXPLAIN_QUERY;
    }
}
