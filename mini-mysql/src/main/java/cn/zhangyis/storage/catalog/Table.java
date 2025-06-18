package cn.zhangyis.storage.catalog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * @Description TODO
 * @Date 2025/5/28 14:18
 * @Created by libo
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Table {
    private String name;
    private String schema;
    private String comment;
    private String createSql;
    private String engine;
    private String charset;
    private List<Column> columns;
    private List<Index> indexes;

    private Map<String,Column> columnMap;
    private Map<String,Index> indexMap;

    public Table(String comment, String name, String schema, String createSql, String engine, String charset, List<Column> columns, List<Index> indexes) {
        this.comment = comment;
        this.name = name;
        this.schema = schema;
        this.createSql = createSql;
        this.engine = engine;
        this.charset = charset;
        this.columns = columns;
        this.indexes = indexes;

        this.columnMap = columns.stream().collect(java.util.stream.Collectors.toMap(Column::getName, column -> column));
        this.indexMap = indexes.stream().collect(java.util.stream.Collectors.toMap(Index::getName, index -> index));
    }

    public Column getColumn(String columnName) {
        if (columnMap == null) {
            columnMap = columns.stream().collect(java.util.stream.Collectors.toMap(Column::getName, column -> column));
        }
        return columnMap.get(columnName);
    }

    public Index getIndex(String indexName) {
        if (indexMap == null) {
            indexMap = indexes.stream().collect(java.util.stream.Collectors.toMap(Index::getName, index -> index));
        }
        return indexMap.get(indexName);
    }
}
