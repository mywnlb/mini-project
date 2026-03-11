package cn.zhangyis.minidb.sql.catalog;

import java.util.List;

/**
 * 索引元数据
 */
public record IndexMeta(String indexName, String tableName, List<String> columns) {}
