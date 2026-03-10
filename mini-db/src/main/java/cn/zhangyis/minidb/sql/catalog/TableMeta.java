package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.types.SqlType;
import java.util.List;

public record TableMeta(String name, List<ColumnMeta> columns, long rowCount) {}

public record ColumnMeta(String name, SqlType type, boolean isPrimaryKey) {}