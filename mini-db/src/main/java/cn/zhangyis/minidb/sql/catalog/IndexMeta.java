package cn.zhangyis.minidb.sql.catalog;

import java.util.List;

/**
 * 索引元数据
 */
public record IndexMeta(String indexName, String tableName, List<String> columns,
                        boolean primary, boolean unique) {

    /** 向后兼容构造器（CREATE INDEX 等场景，默认 secondary non-unique） */
    public IndexMeta(String indexName, String tableName, List<String> columns) {
        this(indexName, tableName, columns, false, false);
    }
}
