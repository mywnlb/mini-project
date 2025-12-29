//package cn.zhangyis.sql.planner.semantic;
//
//import cn.zhangyis.sql.planner.logical.RelNode;
//import cn.zhangyis.storage.catalog.CatalogManager;
//
//import java.util.List;
//import java.util.Map;
//import java.util.HashMap;
//import java.util.ArrayList;
//
///**
// * 默认语义验证器实现
// * 负责验证SQL语句的语义正确性
// */
//public class DefaultSemanticValidator implements SemanticValidator {
//    private final CatalogManager catalogManager;
//    private final Map<String, String> typeCompatibilityMap;
//    private final ExpressionValidator expressionValidator;
//    private final AggregateFunctionValidator aggregateFunctionValidator;
//    private final ExpressionParser expressionParser;
//
//    public DefaultSemanticValidator(CatalogManager catalogManager) {
//        this.catalogManager = catalogManager;
//        this.typeCompatibilityMap = initTypeCompatibilityMap();
//        this.expressionValidator = new ExpressionValidator(catalogManager);
//        this.aggregateFunctionValidator = new AggregateFunctionValidator(catalogManager);
//        this.expressionParser = new ExpressionParser(catalogManager);
//    }
//
//    @Override
//    public void validate(RelNode plan) throws SemanticException {
//        // 基本的逻辑计划验证
//        if (plan == null) {
//            throw new SemanticException("Plan cannot be null");
//        }
//
//        // 递归验证所有输入节点
//        for (RelNode input : plan.getInputs()) {
//            validate(input);
//        }
//
//        // TODO: 添加更多验证逻辑，如：
//        // - 列引用有效性检查
//        // - 类型兼容性检查
//        // - 聚合函数使用规则检查
//        // 等等
//    }
//
//    @Override
//    public void validateTable(String tableName) throws SemanticException {
//        if (tableName == null || tableName.trim().isEmpty()) {
//            throw new SemanticException("Table name cannot be null or empty");
//        }
//
//        if (!catalogManager.tableExists(tableName)) {
//            throw new SemanticException.TableNotFoundException(tableName);
//        }
//    }
//
//    @Override
//    public void validateColumn(String tableName, String columnName) throws SemanticException {
//        if (columnName == null || columnName.trim().isEmpty()) {
//            throw new SemanticException("Column name cannot be null or empty");
//        }
//
//        // 首先验证表是否存在
//        validateTable(tableName);
//
//        // 然后验证列是否存在
//        if (!catalogManager.columnExists(tableName, columnName)) {
//            throw new SemanticException.ColumnNotFoundException(tableName, columnName);
//        }
//    }
//
//    @Override
//    public void validateTypeCompatibility(String sourceType, String targetType) throws SemanticException {
//        if (sourceType == null || targetType == null) {
//            return; // 允许空类型，在后续阶段处理
//        }
//
//        // 简化的类型兼容性检查
//        if (!isTypeCompatible(sourceType, targetType)) {
//            throw new SemanticException("Type mismatch: " + sourceType + " and " + targetType + " are not compatible");
//        }
//    }
//
//    @Override
//    public void validateExpression(String expression) throws SemanticException {
//        if (expression == null || expression.trim().isEmpty()) {
//            throw new SemanticException("Expression cannot be null or empty");
//        }
//
//        // TODO: 实现表达式验证逻辑
//        // 这里应该解析表达式，检查语法和语义正确性
//    }
//
//    @Override
//    public void validateAggregateFunction(String functionName, List<String> arguments) throws SemanticException {
//        if (functionName == null || functionName.trim().isEmpty()) {
//            throw new SemanticException("Function name cannot be null or empty");
//        }
//
//        // 验证聚合函数名称
//        if (!isValidAggregateFunction(functionName)) {
//            throw new SemanticException("Unknown aggregate function: " + functionName);
//        }
//
//        // 验证参数数量
//        validateAggregateFunctionArguments(functionName, arguments);
//    }
//
//    /**
//     * 验证扫描节点
//     */
//    private void validateScanNode(LogicalScan scan) throws SemanticException {
//        validateTable(scan.getTable().getName());
//    }
//
//    /**
//     * 验证过滤节点
//     */
//    private void validateFilterNode(LogicalFilter filter) throws SemanticException {
//        // 验证条件表达式
//        expressionValidator.validate(filter.getCondition());
//    }
//
//    /**
//     * 验证投影节点
//     */
//    private void validateProjectNode(LogicalProject project) throws SemanticException {
//        // 验证输出列
//        for (Column column : project.getOutputColumns()) {
//            // 检查列名是否合法
//            if (column.getName() == null || column.getName().isEmpty()) {
//                throw new SemanticException("Invalid column name in projection");
//            }
//
//            // 检查类型是否合法
//            if (column.getType() == null || column.getType().isEmpty()) {
//                throw new SemanticException("Invalid column type in projection");
//            }
//        }
//    }
//
//    /**
//     * 验证连接节点
//     */
//    private void validateJoinNode(LogicalJoin join) throws SemanticException {
//        // 验证连接条件
//        expressionValidator.validate(join.getCondition());
//
//        // 验证连接类型
//        if (join.getJoinType() == null) {
//            throw new SemanticException("Invalid join type");
//        }
//    }
//
//    /**
//     * 验证聚合节点
//     */
//    private void validateAggregateNode(LogicalAggregate aggregate) throws SemanticException {
//        // 验证分组列
//        for (Column groupColumn : aggregate.getGroupColumns()) {
//            validateColumn(groupColumn.getTableName(), groupColumn.getName());
//        }
//
//        // 验证聚合列
//        for (Column aggColumn : aggregate.getOutputColumns()) {
//            validateColumn(aggColumn.getTableName(), aggColumn.getName());
//        }
//    }
//
//    /**
//     * 检查两个类型是否兼容
//     */
//    private boolean isTypeCompatible(String type1, String type2) {
//        // 相同类型直接兼容
//        if (type1.equals(type2)) {
//            return true;
//        }
//
//        // 数值类型之间的兼容性
//        if (isNumericType(type1) && isNumericType(type2)) {
//            return true;
//        }
//
//        // 字符串类型之间的兼容性
//        if (isStringType(type1) && isStringType(type2)) {
//            return true;
//        }
//
//        return false;
//    }
//
//    /**
//     * 检查是否为数值类型
//     */
//    private boolean isNumericType(String type) {
//        return "INT".equalsIgnoreCase(type) ||
//               "INTEGER".equalsIgnoreCase(type) ||
//               "BIGINT".equalsIgnoreCase(type) ||
//               "FLOAT".equalsIgnoreCase(type) ||
//               "DOUBLE".equalsIgnoreCase(type) ||
//               "DECIMAL".equalsIgnoreCase(type);
//    }
//
//    /**
//     * 检查是否为字符串类型
//     */
//    private boolean isStringType(String type) {
//        return "VARCHAR".equalsIgnoreCase(type) ||
//               "CHAR".equalsIgnoreCase(type) ||
//               "TEXT".equalsIgnoreCase(type);
//    }
//
//    /**
//     * 检查是否为有效的聚合函数
//     */
//    private boolean isValidAggregateFunction(String functionName) {
//        return "COUNT".equalsIgnoreCase(functionName) ||
//               "SUM".equalsIgnoreCase(functionName) ||
//               "AVG".equalsIgnoreCase(functionName) ||
//               "MIN".equalsIgnoreCase(functionName) ||
//               "MAX".equalsIgnoreCase(functionName);
//    }
//
//    /**
//     * 验证聚合函数的参数
//     */
//    private void validateAggregateFunctionArguments(String functionName, List<String> arguments) throws SemanticException {
//        if (arguments == null) {
//            arguments = List.of();
//        }
//
//        switch (functionName.toUpperCase()) {
//            case "COUNT":
//                // COUNT 可以接受 0 个或 1 个参数
//                if (arguments.size() > 1) {
//                    throw new SemanticException("COUNT function accepts at most 1 argument");
//                }
//                break;
//            case "SUM":
//            case "AVG":
//            case "MIN":
//            case "MAX":
//                // 这些函数需要且仅需要 1 个参数
//                if (arguments.size() != 1) {
//                    throw new SemanticException(functionName + " function requires exactly 1 argument");
//                }
//                break;
//            default:
//                throw new SemanticException("Unknown aggregate function: " + functionName);
//        }
//    }
//
//    /**
//     * 初始化类型兼容性映射
//     */
//    private Map<String, String> initTypeCompatibilityMap() {
//        Map<String, String> map = new HashMap<>();
//
//        // 数值类型兼容性
//        map.put("INT", "BIGINT,DOUBLE");
//        map.put("BIGINT", "DOUBLE");
//        map.put("FLOAT", "DOUBLE");
//
//        // 字符串类型兼容性
//        map.put("CHAR", "VARCHAR");
//        map.put("VARCHAR", "TEXT");
//
//        // 日期类型兼容性
//        map.put("DATE", "DATETIME");
//        map.put("TIME", "DATETIME");
//
//        return map;
//    }
//
//
//}