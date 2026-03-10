package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.types.SqlType;

public record ColumnMeta(String name, SqlType type, boolean isPrimaryKey) {}