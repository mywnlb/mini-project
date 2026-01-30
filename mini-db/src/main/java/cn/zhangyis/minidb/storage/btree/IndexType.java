package cn.zhangyis.minidb.storage.btree;

/**
 * 索引类型
 *
 * @author MiniDB
 * @version 1.0
 */
public enum IndexType {

    /**
     * 主键索引（唯一，聚簇）
     */
    PRIMARY,

    /**
     * 唯一索引
     */
    UNIQUE,

    /**
     * 普通索引（允许重复键）
     */
    SECONDARY
}
