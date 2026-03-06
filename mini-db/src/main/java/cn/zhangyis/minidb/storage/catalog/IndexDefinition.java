package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.storage.btree.IndexType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 建表时使用的索引定义。
 */
public final class IndexDefinition {

    private final String indexName;
    private final IndexType indexType;
    private final List<IndexColumn> columns;

    public IndexDefinition(String indexName, IndexType indexType, List<IndexColumn> columns) {
        this.indexName = Objects.requireNonNull(indexName, "indexName");
        this.indexType = Objects.requireNonNull(indexType, "indexType");
        this.columns = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(columns, "columns")));
    }

    public static IndexDefinition primary(String... columnNames) {
        return new IndexDefinition("PRIMARY", IndexType.PRIMARY, columnsOf(columnNames));
    }

    public static IndexDefinition secondary(String indexName, String... columnNames) {
        return new IndexDefinition(indexName, IndexType.SECONDARY, columnsOf(columnNames));
    }

    public static IndexDefinition unique(String indexName, String... columnNames) {
        return new IndexDefinition(indexName, IndexType.UNIQUE, columnsOf(columnNames));
    }

    private static List<IndexColumn> columnsOf(String... columnNames) {
        List<IndexColumn> result = new ArrayList<>();
        if (columnNames != null) {
            for (String columnName : columnNames) {
                result.add(IndexColumn.asc(columnName));
            }
        }
        return result;
    }

    public String getIndexName() {
        return indexName;
    }

    public IndexType getIndexType() {
        return indexType;
    }

    public List<IndexColumn> getColumns() {
        return columns;
    }

    public static final class IndexColumn {
        private final String columnName;
        private final boolean descending;

        public IndexColumn(String columnName, boolean descending) {
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("columnName must not be blank");
            }
            this.columnName = columnName;
            this.descending = descending;
        }

        public static IndexColumn asc(String columnName) {
            return new IndexColumn(columnName, false);
        }

        public static IndexColumn desc(String columnName) {
            return new IndexColumn(columnName, true);
        }

        public String getColumnName() {
            return columnName;
        }

        public boolean isDescending() {
            return descending;
        }
    }
}
