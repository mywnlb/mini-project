package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.types.SqlType;

/**
 * SQL 层列元数据
 *
 * @param name         列名
 * @param type         SQL 类型
 * @param isPrimaryKey 是否主键
 * @param nullable     是否可空（Instant DDL 默认 true）
 * @param defaultValue 默认值字面量（null 表示无显式默认值）
 */
public record ColumnMeta(
    String name,
    SqlType type,
    boolean isPrimaryKey,
    boolean nullable,
    Object defaultValue
) {

    /**
     * 兼容构造：默认 nullable=true, defaultValue=null
     */
    public ColumnMeta(String name, SqlType type, boolean isPrimaryKey) {
        this(name, type, isPrimaryKey, true, null);
    }
}
