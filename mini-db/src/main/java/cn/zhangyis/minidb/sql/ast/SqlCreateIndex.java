package cn.zhangyis.minidb.sql.ast;

import java.util.List;

/**
 * CREATE INDEX index_name ON table (col1, col2, ...)
 */
public record SqlCreateIndex(String indexName, SqlIdentifier table, List<String> columns, boolean unique) implements SqlNode {
    public SqlCreateIndex(String indexName, SqlIdentifier table, List<String> columns) {
        this(indexName, table, columns, false);
    }

    @Override
    public SqlKind kind() { return SqlKind.CREATE_INDEX; }
}
