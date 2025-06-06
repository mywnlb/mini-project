package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.ast.*;
import cn.zhangyis.sql.catalog.CatalogManager;

import java.util.*;

/**
 * 聚合函数验证器
 * 负责验证SQL聚合函数的语义正确性
 */
public class AggregateFunctionValidator {
    private final CatalogManager catalogManager;
    private final Map<String, AggregateFunctionSignature> supportedFunctions;
    
    public AggregateFunctionValidator(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
        this.supportedFunctions = initSupportedFunctions();
    }
    
    /**
     * 验证聚合函数
     */
    public void validate(String functionName, List<Expression> arguments) throws SemanticException {
        // 验证函数是否存在
        AggregateFunctionSignature signature = supportedFunctions.get(functionName.toUpperCase());
        if (signature == null) {
            throw new SemanticException.InvalidFunctionException(functionName);
        }
        
        // 验证参数数量
        if (arguments.size() != signature.getParameterCount()) {
            throw new SemanticException("Invalid number of arguments for aggregate function " + functionName +
                ": expected " + signature.getParameterCount() + ", got " + arguments.size());
        }
        
        // 验证参数类型
        for (int i = 0; i < arguments.size(); i++) {
            Expression arg = arguments.get(i);
            String argType = arg.getType();
            String expectedType = signature.getParameterType(i);
            
            if (!isTypeCompatible(argType, expectedType)) {
                throw new SemanticException.TypeIncompatibleException(
                    "Aggregate function " + functionName + " parameter " + (i + 1) +
                    " type mismatch: expected " + expectedType + ", got " + argType);
            }
        }
    }
    
    /**
     * 检查类型是否兼容
     */
    private boolean isTypeCompatible(String sourceType, String targetType) {
        // 相同类型一定兼容
        if (sourceType.equals(targetType)) {
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
        
        return false;
    }
    
    /**
     * 检查是否是数值类型
     */
    private boolean isNumericType(String type) {
        return type.equals("INT") || type.equals("BIGINT") ||
               type.equals("FLOAT") || type.equals("DOUBLE");
    }
    
    /**
     * 检查是否是字符串类型
     */
    private boolean isStringType(String type) {
        return type.equals("CHAR") || type.equals("VARCHAR") ||
               type.equals("TEXT");
    }
    
    /**
     * 初始化支持的聚合函数
     */
    private Map<String, AggregateFunctionSignature> initSupportedFunctions() {
        Map<String, AggregateFunctionSignature> functions = new HashMap<>();
        
        // 计数函数
        functions.put("COUNT", new AggregateFunctionSignature("BIGINT", "ANY"));
        
        // 求和函数
        functions.put("SUM", new AggregateFunctionSignature("DOUBLE", "NUMERIC"));
        
        // 平均值函数
        functions.put("AVG", new AggregateFunctionSignature("DOUBLE", "NUMERIC"));
        
        // 最大值函数
        functions.put("MAX", new AggregateFunctionSignature("ANY", "ANY"));
        
        // 最小值函数
        functions.put("MIN", new AggregateFunctionSignature("ANY", "ANY"));
        
        // 字符串聚合函数
        functions.put("GROUP_CONCAT", new AggregateFunctionSignature("VARCHAR", "VARCHAR"));
        
        return functions;
    }
    
    /**
     * 聚合函数签名类
     */
    private static class AggregateFunctionSignature {
        private final String returnType;
        private final List<String> parameterTypes;
        
        public AggregateFunctionSignature(String returnType, String... parameterTypes) {
            this.returnType = returnType;
            this.parameterTypes = Arrays.asList(parameterTypes);
        }
        
        public String getReturnType() {
            return returnType;
        }
        
        public int getParameterCount() {
            return parameterTypes.size();
        }
        
        public String getParameterType(int index) {
            return parameterTypes.get(index);
        }
    }
} 