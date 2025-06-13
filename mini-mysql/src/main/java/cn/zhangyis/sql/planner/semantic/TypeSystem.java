package cn.zhangyis.sql.planner.semantic;

import java.util.*;

/**
 * 类型系统工具类
 * 负责SQL类型推导、类型兼容性检查和类型转换
 * 参考 Apache Calcite 的类型系统实现
 * 
 * 职责：
 * 1. 二元表达式的类型推导
 * 2. 函数调用的返回类型推导
 * 3. 类型兼容性检查
 * 4. 类型优先级处理
 * 
 * @Description SQL类型系统
 * @Date 2025/6/6
 * @Created by libo
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
    
    // 类型优先级定义 - 数值越大优先级越高
    private static final Map<String, Integer> TYPE_PRECEDENCE = new HashMap<>();
    
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
        
        TYPE_PRECEDENCE.put("NULL", 0);
        TYPE_PRECEDENCE.put("BOOLEAN", 1);
        TYPE_PRECEDENCE.put("TINYINT", 2);
        TYPE_PRECEDENCE.put("SMALLINT", 3);
        TYPE_PRECEDENCE.put("INT", 4);
        TYPE_PRECEDENCE.put("BIGINT", 5);
        TYPE_PRECEDENCE.put("FLOAT", 6);
        TYPE_PRECEDENCE.put("DOUBLE", 7);
        TYPE_PRECEDENCE.put("DECIMAL", 8);
        TYPE_PRECEDENCE.put("CHAR", 9);
        TYPE_PRECEDENCE.put("VARCHAR", 10);
        TYPE_PRECEDENCE.put("TEXT", 11);
        TYPE_PRECEDENCE.put("DATE", 12);
        TYPE_PRECEDENCE.put("TIME", 13);
        TYPE_PRECEDENCE.put("TIMESTAMP", 14);
    }
    
    /**
     * 推导二元表达式的类型
     * 
     * @param operator 操作符
     * @param leftType 左操作数类型
     * @param rightType 右操作数类型
     * @return 结果类型
     */
    public static String deriveBinaryExpressionType(String operator, String leftType, String rightType) {
        switch (operator.toUpperCase()) {
            // 算术运算符
            case "+":
            case "-":
            case "*":
            case "/":
                return deriveArithmeticType(leftType, rightType);
            
            // 比较运算符 - 返回布尔类型
            case "=":
            case "!=":
            case "<>":
            case "<":
            case "<=":
            case ">":
            case ">=":
                return "BOOLEAN";
            
            // 逻辑运算符 - 要求操作数是布尔类型
            case "AND":
            case "OR":
                if ("BOOLEAN".equals(leftType) && "BOOLEAN".equals(rightType)) {
                    return "BOOLEAN";
                }
                throw new SemanticException("Logical operators require boolean operands");
            
            // 字符串连接
            case "||":
            case "CONCAT":
                if (isStringType(leftType) && isStringType(rightType)) {
                    return getHigherPrecedenceStringType(leftType, rightType);
                }
                return "VARCHAR";
            
            // LIKE 操作符
            case "LIKE":
            case "NOT LIKE":
                return "BOOLEAN";
            
            // IN 操作符
            case "IN":
            case "NOT IN":
                return "BOOLEAN";
            
            default:
                // 默认使用高优先级类型
                return getHigherPrecedenceType(leftType, rightType);
        }
    }
    
    /**
     * 推导函数的返回类型
     * 
     * @param functionName 函数名
     * @param argTypes 参数类型列表
     * @return 函数返回类型
     */
    public static String deriveFunctionType(String functionName, List<String> argTypes) {
        switch (functionName.toUpperCase()) {
            // 聚合函数
            case "COUNT":
                return "BIGINT";
            case "SUM":
                if (argTypes.size() > 0) {
                    String argType = argTypes.get(0);
                    if (isNumericType(argType)) {
                        return argType;
                    }
                }
                return "DOUBLE";
            case "AVG":
                return "DOUBLE";
            case "MAX":
            case "MIN":
                if (argTypes.size() > 0) {
                    return argTypes.get(0);
                }
                return "VARCHAR";
            
            // 字符串函数
            case "LENGTH":
            case "CHAR_LENGTH":
                return "INT";
            case "UPPER":
            case "LOWER":
            case "TRIM":
            case "LTRIM":
            case "RTRIM":
                if (argTypes.size() > 0 && isStringType(argTypes.get(0))) {
                    return argTypes.get(0);
                }
                return "VARCHAR";
            case "SUBSTRING":
            case "SUBSTR":
                return "VARCHAR";
            case "CONCAT":
                return "VARCHAR";
            
            // 数学函数
            case "ABS":
                if (argTypes.size() > 0) {
                    String argType = argTypes.get(0);
                    if (isNumericType(argType)) {
                        return argType;
                    }
                }
                return "DOUBLE";
            case "ROUND":
            case "FLOOR":
            case "CEIL":
            case "CEILING":
                return "DOUBLE";
            case "SQRT":
            case "LOG":
            case "EXP":
            case "POWER":
            case "SIN":
            case "COS":
            case "TAN":
                return "DOUBLE";
            
            // 日期时间函数
            case "NOW":
            case "CURRENT_TIMESTAMP":
                return "TIMESTAMP";
            case "CURRENT_DATE":
                return "DATE";
            case "CURRENT_TIME":
                return "TIME";
            case "YEAR":
            case "MONTH":
            case "DAY":
            case "HOUR":
            case "MINUTE":
            case "SECOND":
                return "INT";
            
            // 条件函数
            case "COALESCE":
            case "ISNULL":
            case "NULLIF":
                // 返回第一个非空参数的类型
                for (String argType : argTypes) {
                    if (!"NULL".equals(argType)) {
                        return argType;
                    }
                }
                return "NULL";
            
            case "CASE":
                // CASE表达式返回所有WHEN分支的通用类型
                if (argTypes.size() > 1) {
                    return getCommonType(argTypes);
                }
                return "VARCHAR";
            
            default:
                // 未知函数默认返回VARCHAR
                return "VARCHAR";
        }
    }
    
    /**
     * 检查两个类型是否兼容
     */
    public static boolean isTypeCompatible(String type1, String type2) {
        if (type1 == null || type2 == null) {
            return false;
        }
        
        // 相同类型总是兼容的
        if (type1.equals(type2)) {
            return true;
        }
        
        // NULL类型与任何类型兼容
        if ("NULL".equals(type1) || "NULL".equals(type2)) {
            return true;
        }
        
        // 数值类型之间的兼容性
        if (isNumericType(type1) && isNumericType(type2)) {
            return true;
        }
        
        // 字符串类型之间的兼容性
        if (isStringType(type1) && isStringType(type2)) {
            return true;
        }
        
        // 日期时间类型之间的兼容性
        if (isDateTimeType(type1) && isDateTimeType(type2)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 获取两个类型中优先级更高的类型
     */
    public static String getHigherPrecedenceType(String type1, String type2) {
        Integer precedence1 = TYPE_PRECEDENCE.get(type1);
        Integer precedence2 = TYPE_PRECEDENCE.get(type2);
        
        if (precedence1 == null && precedence2 == null) {
            return type1; // 默认返回第一个类型
        }
        if (precedence1 == null) {
            return type2;
        }
        if (precedence2 == null) {
            return type1;
        }
        
        return precedence1 >= precedence2 ? type1 : type2;
    }
    
    /**
     * 获取字符串类型中优先级更高的类型
     */
    private static String getHigherPrecedenceStringType(String type1, String type2) {
        // TEXT > VARCHAR > CHAR
        if ("TEXT".equals(type1) || "TEXT".equals(type2)) {
            return "TEXT";
        }
        if ("VARCHAR".equals(type1) || "VARCHAR".equals(type2)) {
            return "VARCHAR";
        }
        return "CHAR";
    }
    
    /**
     * 获取多个类型的通用类型
     */
    public static String getCommonType(List<String> types) {
        if (types.isEmpty()) {
            return "NULL";
        }
        
        String commonType = types.get(0);
        for (int i = 1; i < types.size(); i++) {
            commonType = getHigherPrecedenceType(commonType, types.get(i));
        }
        return commonType;
    }
    
    /**
     * 检查是否为数值类型
     */
    public static boolean isNumericType(String type) {
        return "TINYINT".equals(type) || "SMALLINT".equals(type) || "INT".equals(type) ||
               "BIGINT".equals(type) || "FLOAT".equals(type) || "DOUBLE".equals(type) ||
               "DECIMAL".equals(type);
    }
    
    /**
     * 检查是否为字符串类型
     */
    public static boolean isStringType(String type) {
        return "CHAR".equals(type) || "VARCHAR".equals(type) || "TEXT".equals(type);
    }
    
    /**
     * 检查是否为日期时间类型
     */
    public static boolean isDateTimeType(String type) {
        return "DATE".equals(type) || "TIME".equals(type) || "TIMESTAMP".equals(type);
    }
    
    /**
     * 检查是否可以进行隐式类型转换
     */
    public static boolean canImplicitlyCast(String fromType, String toType) {
        if (fromType.equals(toType)) {
            return true;
        }
        
        // NULL可以转换为任何类型
        if ("NULL".equals(fromType)) {
            return true;
        }
        
        // 数值类型的向上转换
        if (isNumericType(fromType) && isNumericType(toType)) {
            Integer fromPrec = TYPE_PRECEDENCE.get(fromType);
            Integer toPrec = TYPE_PRECEDENCE.get(toType);
            return fromPrec != null && toPrec != null && fromPrec <= toPrec;
        }
        
        // 字符串类型的转换
        if (isStringType(fromType) && isStringType(toType)) {
            return true;
        }
        
        return false;
    }
} 