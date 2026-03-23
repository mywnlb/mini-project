package cn.zhangyis.minidb.sql.ast;

/**
 * EXPLAIN [ANALYZE] <query> 语句的 AST 节点
 */
public record SqlExplain(SqlNode query, boolean analyze) implements SqlNode {

    public SqlExplain(SqlNode query) {
        this(query, false);
    }

    @Override
    public SqlKind kind() {
        return SqlKind.EXPLAIN_QUERY;
    }
}
