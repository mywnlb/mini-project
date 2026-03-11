package cn.zhangyis.minidb.sql.ast;

/**
 * DROP INDEX index_name ON table
 */
public record SqlDropIndex(String indexName, SqlIdentifier table) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.DROP_INDEX; }
}
