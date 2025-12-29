package cn.zhangyis.sql.parser.enums;

/**
 * 连接类型枚举
 */
public enum JoinType {
    INNER, // 内连接
    LEFT, // 左外连接
    RIGHT, // 右外连接
    FULL, // 全外连接
    CROSS, // 交叉连接（笛卡尔积）
    SEMI, // 半连接
    ANTI // 反连接
}
