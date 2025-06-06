package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.planner.logical.RelNode;
import java.util.List;

/**
 * 语义验证器接口
 * 负责验证SQL语句的语义正确性
 */
public interface SemanticValidator {
    /**
     * 验证逻辑计划
     * @param plan 逻辑计划
     * @throws SemanticException 如果验证失败
     */
    void validate(RelNode plan) throws SemanticException;
    
    /**
     * 验证表是否存在
     * @param tableName 表名
     * @throws SemanticException 如果表不存在
     */
    void validateTable(String tableName) throws SemanticException;
    
    /**
     * 验证列是否存在
     * @param tableName 表名
     * @param columnName 列名
     * @throws SemanticException 如果列不存在
     */
    void validateColumn(String tableName, String columnName) throws SemanticException;
    
    /**
     * 验证数据类型是否兼容
     * @param sourceType 源类型
     * @param targetType 目标类型
     * @throws SemanticException 如果类型不兼容
     */
    void validateTypeCompatibility(String sourceType, String targetType) throws SemanticException;
    
    /**
     * 验证表达式是否合法
     * @param expression 表达式
     * @throws SemanticException 如果表达式不合法
     */
    void validateExpression(String expression) throws SemanticException;
    
    /**
     * 验证聚合函数是否合法
     * @param functionName 函数名
     * @param arguments 参数
     * @throws SemanticException 如果函数不合法
     */
    void validateAggregateFunction(String functionName, List<String> arguments) throws SemanticException;
} 