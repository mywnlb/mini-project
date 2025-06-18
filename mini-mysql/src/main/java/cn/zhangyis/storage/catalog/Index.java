package cn.zhangyis.storage.catalog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description TODO
 * @Date 2025/6/6 14:38
 * @Created by libo
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Index {
    private String name;        // 索引名称
    private String tableName;   // 所属表名
    private String columnName;  // 索引列名
    private String indexType;   // 索引类型（如B-Tree, Hash等）
    private boolean unique;      // 是否唯一索引
    private String comment;     // 索引注释

}
