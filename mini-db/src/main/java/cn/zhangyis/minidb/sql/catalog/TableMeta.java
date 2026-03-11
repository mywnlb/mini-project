package cn.zhangyis.minidb.sql.catalog;

import java.util.List;

public record TableMeta(String name, List<ColumnMeta> columns, long rowCount) {
    public static TableMeta of(String name, List<ColumnMeta> columns, long rowCount) {
        return new TableMeta(name, List.copyOf(columns), rowCount);
    }
}
