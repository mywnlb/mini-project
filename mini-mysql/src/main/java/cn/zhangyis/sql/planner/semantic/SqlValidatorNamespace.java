package cn.zhangyis.sql.planner.semantic;



import cn.zhangyis.storage.catalog.Column;

import java.util.List;

/**
 * SQL验证命名空间接口
 * 参考 Apache Calcite 的 SqlValidatorNamespace 设计
 * 
 * 命名空间代表查询中的一个数据源，可以是：
 * - 表或视图
 * - SELECT 查询的结果
 * - 子查询
 * - 表值函数
 * - 等等
 */
public interface SqlValidatorNamespace {
    
    /**
     * 获取命名空间的名称
     */
    String getName();
    
    /**
     * 获取命名空间的类型
     */
    NamespaceType getType();
    
    /**
     * 查找指定名称的列
     */
    Column findColumn(String columnName);
    
    /**
     * 获取所有列
     */
    List<Column> getColumns();
    
    /**
     * 检查是否包含指定的列
     */
    boolean hasColumn(String columnName);
    
    /**
     * 获取列的数量
     */
    int getColumnCount();
    
    /**
     * 验证命名空间
     */
    void validate() throws SemanticException;
    
    /**
     * 检查命名空间是否已验证
     */
    boolean isValidated();
    
    /**
     * 命名空间类型枚举
     */
    enum NamespaceType {
        TABLE,          // 表
        VIEW,           // 视图
        SELECT,         // SELECT 查询
        SUBQUERY,       // 子查询
        TABLE_FUNCTION, // 表值函数
        VALUES,         // VALUES 子句
        JOIN,           // JOIN 结果
        UNION,          // UNION 结果
        WITH            // WITH 子句
    }
} 