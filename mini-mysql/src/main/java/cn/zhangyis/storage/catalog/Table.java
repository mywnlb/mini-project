package cn.zhangyis.storage.catalog;

import lombok.Data;

import java.util.List;

/**
 * @Description TODO
 * @Date 2025/5/28 14:18
 * @Created by libo
 */
@Data
public class Table {
    private String name;
    private String schema;
    private String comment;
    private String createSql;
    private String engine;
    private String charset;
    private List<Column> columns;
    private List<Index> indexes;

    public Table() {
    }
    public Table(String name, String schema, String comment, String createSql, String engine, String charset, List<Column> columns, List<Index> indexes) {
        this.name = name;
        this.schema = schema;
        this.comment = comment;
        this.createSql = createSql;
        this.engine = engine;
        this.charset = charset;
        this.columns = columns;
        this.indexes = indexes;
    }
}
