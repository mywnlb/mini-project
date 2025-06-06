package cn.zhangyis.sql.planner.semantic;

import java.util.*;

/**
 * SQL类型系统
 * 负责处理SQL类型推导和兼容性检查
 */
public class TypeSystem {
    // 数值类型
    private static final Set<String> NUMERIC_TYPES = new HashSet<>(Arrays.asList(
        "INT", "BIGINT", "FLOAT", "DOUBLE", "DECIMAL"
    ));
    
    // 字符串类型
    private static final Set<String> STRING_TYPES = new HashSet<>(Arrays.asList(
        "CHAR", "VARCHAR", "TEXT"
    ));
    
    // 日期时间类型
    private static final Set<String> DATETIME_TYPES = new HashSet<>(Arrays.asList(
        "DATE", "TIME", "DATETIME", "TIMESTAMP"
    ));
    
    // 类型转换规则
    private static final Map<String, Set<String>> TYPE_CONVERSION_RULES = new HashMap<>();
    
    static {
        // 数值类型转换规则
        TYPE_CONVERSION_RULES.put("INT", new HashSet<>(Arrays.asList("BIGINT", "FLOAT", "DOUBLE", "DECIMAL")));
        TYPE_CONVERSION_RULES.put("BIGINT", new HashSet<>(Arrays.asList("FLOAT", "DOUBLE", "DECIMAL")));
        TYPE_CONVERSION_RULES.put("FLOAT", new HashSet<>(Arrays.asList("DOUBLE", "DECIMAL")));
        TYPE_CONVERSION_RULES.put("DOUBLE", new HashSet<>(Arrays.asList("DECIMAL")));
        
        // 字符串类型转换规则
        TYPE_CONVERSION_RULES.put("CHAR", new HashSet<>(Arrays.asList("VARCHAR", "TEXT")));
        TYPE_CONVERSION_RULES.put("VARCHAR", new HashSet<>(Arrays.asList("TEXT")));
        
        // 日期时间类型转换规则
        TYPE_CONVERSION_RULES.put("DATE", new HashSet<>(Arrays.asList("DATETIME", "TIMESTAMP")));
        TYPE_CONVERSION_RULES.put("TIME", new HashSet<>(Arrays.asList("DATETIME", "TIMESTAMP")));
        TYPE_CONVERSION_RULES.put("DATETIME", new HashSet<>(Arrays.asList("TIMESTAMP")));
    }
    
    /**
     * 推导二元表达式的类型
     */
    public static String deriveBinaryExpressionType(String operator, String leftType, String rightType) {
        // 算术运算符
        if (isArithmeticOperator(operator)) {
            return deriveArithmeticType(leftType, rightType);
        }
        
        // 比较运算符
        if (isComparisonOperator(operator)) {
            return "BOOLEAN";
        }
        
        // 逻辑运算符
        if (isLogicalOperator(operator)) {
            return "BOOLEAN";
        }
        
        // 字符串连接
        if (operator.equals("||")) {
            return deriveStringConcatType(leftType, rightType);
        }
        
        throw new SemanticException("Unsupported operator: " + operator);
    }
    
    /**
     * 推导函数调用的类型
     */
    public static String deriveFunctionType(String functionName, List<String> argTypes) {
        // 聚合函数
        if (isAggregateFunction(functionName)) {
            return deriveAggregateFunctionType(functionName, argTypes);
        }
        
        // 数学函数
        if (isMathFunction(functionName)) {
            return deriveMathFunctionType(functionName, argTypes);
        }
        
        // 字符串函数
        if (isStringFunction(functionName)) {
            return deriveStringFunctionType(functionName, argTypes);
        }
        
        // 日期时间函数
        if (isDateTimeFunction(functionName)) {
            return deriveDateTimeFunctionType(functionName, argTypes);
        }
        
        throw new SemanticException("Unsupported function: " + functionName);
    }
    
    /**
     * 检查类型是否兼容
     */
    public static boolean isTypeCompatible(String sourceType, String targetType) {
        // 相同类型一定兼容
        if (sourceType.equals(targetType)) {
            return true;
        }
        
        // 检查类型转换规则
        Set<String> convertibleTypes = TYPE_CONVERSION_RULES.get(sourceType);
        if (convertibleTypes != null && convertibleTypes.contains(targetType)) {
            return true;
        }
        
        // 数值类型兼容性
        if (isNumericType(sourceType) && isNumericType(targetType)) {
            return true;
        }
        
        // 字符串类型兼容性
        if (isStringType(sourceType) && isStringType(targetType)) {
            return true;
        }
        
        // 日期时间类型兼容性
        if (isDateTimeType(sourceType) && isDateTimeType(targetType)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 推导算术表达式的类型
     */
    private static String deriveArithmeticType(String leftType, String rightType) {
        // 如果两个操作数都是数值类型
        if (isNumericType(leftType) && isNumericType(rightType)) {
            // 如果有一个是DOUBLE，结果是DOUBLE
            if (leftType.equals("DOUBLE") || rightType.equals("DOUBLE")) {
                return "DOUBLE";
            }
            // 如果有一个是FLOAT，结果是FLOAT
            if (leftType.equals("FLOAT") || rightType.equals("FLOAT")) {
                return "FLOAT";
            }
            // 如果有一个是BIGINT，结果是BIGINT
            if (leftType.equals("BIGINT") || rightType.equals("BIGINT")) {
                return "BIGINT";
            }
            // 默认返回INT
            return "INT";
        }
        
        throw new SemanticException("Incompatible types for arithmetic operation: " + 
            leftType + " and " + rightType);
    }
    
    /**
     * 推导字符串连接的类型
     */
    private static String deriveStringConcatType(String leftType, String rightType) {
        if (isStringType(leftType) && isStringType(rightType)) {
            // 如果有一个是TEXT，结果是TEXT
            if (leftType.equals("TEXT") || rightType.equals("TEXT")) {
                return "TEXT";
            }
            // 如果有一个是VARCHAR，结果是VARCHAR
            if (leftType.equals("VARCHAR") || rightType.equals("VARCHAR")) {
                return "VARCHAR";
            }
            // 默认返回CHAR
            return "CHAR";
        }
        
        throw new SemanticException("Incompatible types for string concatenation: " + 
            leftType + " and " + rightType);
    }
    
    /**
     * 推导聚合函数的类型
     */
    private static String deriveAggregateFunctionType(String functionName, List<String> argTypes) {
        switch (functionName.toUpperCase()) {
            case "COUNT":
                return "BIGINT";
            case "SUM":
            case "AVG":
                if (isNumericType(argTypes.get(0))) {
                    return argTypes.get(0);
                }
                throw new SemanticException("SUM/AVG requires numeric argument");
            case "MAX":
            case "MIN":
                return argTypes.get(0);
            case "GROUP_CONCAT":
                return "TEXT";
            default:
                throw new SemanticException("Unsupported aggregate function: " + functionName);
        }
    }
    
    /**
     * 推导数学函数的类型
     */
    private static String deriveMathFunctionType(String functionName, List<String> argTypes) {
        switch (functionName.toUpperCase()) {
            case "ABS":
            case "CEIL":
            case "FLOOR":
            case "ROUND":
                return argTypes.get(0);
            case "POWER":
                return "DOUBLE";
            default:
                throw new SemanticException("Unsupported math function: " + functionName);
        }
    }
    
    /**
     * 推导字符串函数的类型
     */
    private static String deriveStringFunctionType(String functionName, List<String> argTypes) {
        switch (functionName.toUpperCase()) {
            case "LENGTH":
                return "INT";
            case "UPPER":
            case "LOWER":
            case "TRIM":
                return argTypes.get(0);
            case "SUBSTRING":
                return "VARCHAR";
            default:
                throw new SemanticException("Unsupported string function: " + functionName);
        }
    }
    
    /**
     * 推导日期时间函数的类型
     */
    private static String deriveDateTimeFunctionType(String functionName, List<String> argTypes) {
        switch (functionName.toUpperCase()) {
            case "YEAR":
            case "MONTH":
            case "DAY":
            case "HOUR":
            case "MINUTE":
            case "SECOND":
                return "INT";
            case "DATE_ADD":
            case "DATE_SUB":
                return argTypes.get(0);
            default:
                throw new SemanticException("Unsupported datetime function: " + functionName);
        }
    }
    
    /**
     * 检查是否是算术运算符
     */
    private static boolean isArithmeticOperator(String operator) {
        return operator.equals("+") || operator.equals("-") ||
               operator.equals("*") || operator.equals("/") ||
               operator.equals("%");
    }
    
    /**
     * 检查是否是比较运算符
     */
    private static boolean isComparisonOperator(String operator) {
        return operator.equals("=") || operator.equals("<>") ||
               operator.equals("<") || operator.equals(">") ||
               operator.equals("<=") || operator.equals(">=");
    }
    
    /**
     * 检查是否是逻辑运算符
     */
    private static boolean isLogicalOperator(String operator) {
        return operator.equals("AND") || operator.equals("OR") ||
               operator.equals("NOT");
    }
    
    /**
     * 检查是否是聚合函数
     */
    private static boolean isAggregateFunction(String functionName) {
        return functionName.equalsIgnoreCase("COUNT") ||
               functionName.equalsIgnoreCase("SUM") ||
               functionName.equalsIgnoreCase("AVG") ||
               functionName.equalsIgnoreCase("MAX") ||
               functionName.equalsIgnoreCase("MIN") ||
               functionName.equalsIgnoreCase("GROUP_CONCAT");
    }
    
    /**
     * 检查是否是数学函数
     */
    private static boolean isMathFunction(String functionName) {
        return functionName.equalsIgnoreCase("ABS") ||
               functionName.equalsIgnoreCase("CEIL") ||
               functionName.equalsIgnoreCase("FLOOR") ||
               functionName.equalsIgnoreCase("ROUND") ||
               functionName.equalsIgnoreCase("POWER");
    }
    
    /**
     * 检查是否是字符串函数
     */
    private static boolean isStringFunction(String functionName) {
        return functionName.equalsIgnoreCase("LENGTH") ||
               functionName.equalsIgnoreCase("UPPER") ||
               functionName.equalsIgnoreCase("LOWER") ||
               functionName.equalsIgnoreCase("TRIM") ||
               functionName.equalsIgnoreCase("SUBSTRING");
    }
    
    /**
     * 检查是否是日期时间函数
     */
    private static boolean isDateTimeFunction(String functionName) {
        return functionName.equalsIgnoreCase("YEAR") ||
               functionName.equalsIgnoreCase("MONTH") ||
               functionName.equalsIgnoreCase("DAY") ||
               functionName.equalsIgnoreCase("HOUR") ||
               functionName.equalsIgnoreCase("MINUTE") ||
               functionName.equalsIgnoreCase("SECOND") ||
               functionName.equalsIgnoreCase("DATE_ADD") ||
               functionName.equalsIgnoreCase("DATE_SUB");
    }
    
    /**
     * 检查是否是数值类型
     */
    public static boolean isNumericType(String type) {
        return NUMERIC_TYPES.contains(type);
    }
    
    /**
     * 检查是否是字符串类型
     */
    public static boolean isStringType(String type) {
        return STRING_TYPES.contains(type);
    }
    
    /**
     * 检查是否是日期时间类型
     */
    public static boolean isDateTimeType(String type) {
        return DATETIME_TYPES.contains(type);
    }
} 