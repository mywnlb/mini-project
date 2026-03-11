package cn.zhangyis.minidb.sql.ast;

import java.util.List;

/**
 * CREATE INDEX index_name ON table (col1, col2, ...)
 */
public record SqlCreateIndex(String indexName, SqlIdentifier table, List<String> columns) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.CREATE_INDEX; }
}
