package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.ast.*;
import cn.zhangyis.sql.catalog.CatalogManager;
import cn.zhangyis.sql.catalog.Table;
import cn.zhangyis.sql.catalog.Column;

import java.util.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 表达式解析器
 * 负责将SQL表达式字符串解析为表达式对象
 */
public class ExpressionParser {
    private final CatalogManager catalogManager;
    private final Map<String, Integer> operatorPrecedence;
    
    public ExpressionParser(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
        this.operatorPrecedence = initOperatorPrecedence();
    }
    
    /**
     * 解析表达式
     */
    public Expression parse(String expression) throws SemanticException {
        if (expression == null || expression.trim().isEmpty()) {
            throw new SemanticException("Empty expression");
        }
        
        // 移除多余的空格
        expression = expression.trim();
        
        // 解析表达式
        return parseExpression(expression);
    }
    
    /**
     * 解析表达式
     */
    private Expression parseExpression(String expression) throws SemanticException {
        // 检查是否是字面量
        if (isLiteral(expression)) {
            return parseLiteral(expression);
        }
        
        // 检查是否是列引用
        if (isColumnReference(expression)) {
            return parseColumnReference(expression);
        }
        
        // 检查是否是函数调用
        if (isFunctionCall(expression)) {
            return parseFunctionCall(expression);
        }
        
        // 检查是否是子查询
        if (isSubquery(expression)) {
            return parseSubquery(expression);
        }
        
        // 解析二元表达式
        return parseBinaryExpression(expression);
    }
    
    /**
     * 检查是否是字面量
     */
    private boolean isLiteral(String expression) {
        // 检查是否是数字
        if (expression.matches("-?\\d+(\\.\\d+)?")) {
            return true;
        }
        
        // 检查是否是字符串
        if (expression.startsWith("'") && expression.endsWith("'")) {
            return true;
        }
        
        // 检查是否是布尔值
        if (expression.equalsIgnoreCase("TRUE") || expression.equalsIgnoreCase("FALSE")) {
            return true;
        }
        
        // 检查是否是NULL
        if (expression.equalsIgnoreCase("NULL")) {
            return true;
        }
        
        // 检查是否是日期时间
        if (isDateTimeLiteral(expression)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查是否是日期时间字面量
     */
    private boolean isDateTimeLiteral(String expression) {
        // 检查是否是日期
        if (expression.matches("DATE\\s+'\\d{4}-\\d{2}-\\d{2}'")) {
            return true;
        }
        
        // 检查是否是时间
        if (expression.matches("TIME\\s+'\\d{2}:\\d{2}:\\d{2}'")) {
            return true;
        }
        
        // 检查是否是时间戳
        if (expression.matches("TIMESTAMP\\s+'\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}'")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 解析字面量
     */
    private Literal parseLiteral(String expression) throws SemanticException {
        // 解析数字
        if (expression.matches("-?\\d+(\\.\\d+)?")) {
            if (expression.contains(".")) {
                return new Literal("DOUBLE", Double.parseDouble(expression));
            } else {
                return new Literal("INT", Long.parseLong(expression));
            }
        }
        
        // 解析字符串
        if (expression.startsWith("'") && expression.endsWith("'")) {
            String value = expression.substring(1, expression.length() - 1);
            return new Literal("VARCHAR", value);
        }
        
        // 解析布尔值
        if (expression.equalsIgnoreCase("TRUE")) {
            return new Literal("BOOLEAN", true);
        }
        if (expression.equalsIgnoreCase("FALSE")) {
            return new Literal("BOOLEAN", false);
        }
        
        // 解析NULL
        if (expression.equalsIgnoreCase("NULL")) {
            return new Literal("NULL", null);
        }
        
        // 解析日期时间
        if (isDateTimeLiteral(expression)) {
            return parseDateTimeLiteral(expression);
        }
        
        throw new SemanticException("Invalid literal: " + expression);
    }
    
    /**
     * 解析日期时间字面量
     */
    private Literal parseDateTimeLiteral(String expression) throws SemanticException {
        try {
            if (expression.startsWith("DATE")) {
                String dateStr = expression.substring(6, expression.length() - 1);
                LocalDate date = LocalDate.parse(dateStr);
                return new Literal("DATE", date);
            }
            
            if (expression.startsWith("TIME")) {
                String timeStr = expression.substring(6, expression.length() - 1);
                LocalTime time = LocalTime.parse(timeStr);
                return new Literal("TIME", time);
            }
            
            if (expression.startsWith("TIMESTAMP")) {
                String timestampStr = expression.substring(11, expression.length() - 1);
                LocalDateTime timestamp = LocalDateTime.parse(timestampStr, 
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return new Literal("TIMESTAMP", timestamp);
            }
        } catch (DateTimeParseException e) {
            throw new SemanticException("Invalid datetime literal: " + expression);
        }
        
        throw new SemanticException("Invalid datetime literal: " + expression);
    }
    
    /**
     * 检查是否是列引用
     */
    private boolean isColumnReference(String expression) {
        // 检查是否是简单的列名
        if (expression.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return true;
        }
        
        // 检查是否是带表名的列引用
        if (expression.matches("[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_][a-zA-Z0-9_]*")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 解析列引用
     */
    private ColumnReference parseColumnReference(String expression) throws SemanticException {
        String[] parts = expression.split("\\.");
        if (parts.length == 1) {
            return new ColumnReference(null, parts[0]);
        } else {
            return new ColumnReference(parts[0], parts[1]);
        }
    }
    
    /**
     * 检查是否是函数调用
     */
    private boolean isFunctionCall(String expression) {
        return expression.matches("[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(.*\\)");
    }
    
    /**
     * 解析函数调用
     */
    private FunctionCall parseFunctionCall(String expression) throws SemanticException {
        int openParen = expression.indexOf('(');
        String functionName = expression.substring(0, openParen).trim();
        String argsStr = expression.substring(openParen + 1, expression.length() - 1).trim();
        
        List<Expression> arguments = new ArrayList<>();
        if (!argsStr.isEmpty()) {
            arguments = parseArguments(argsStr);
        }
        
        return new FunctionCall(functionName, arguments);
    }
    
    /**
     * 解析函数参数
     */
    private List<Expression> parseArguments(String argsStr) throws SemanticException {
        List<Expression> arguments = new ArrayList<>();
        int depth = 0;
        StringBuilder currentArg = new StringBuilder();
        
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            
            if (c == '(') {
                depth++;
                currentArg.append(c);
            } else if (c == ')') {
                depth--;
                currentArg.append(c);
            } else if (c == ',' && depth == 0) {
                arguments.add(parseExpression(currentArg.toString().trim()));
                currentArg = new StringBuilder();
            } else {
                currentArg.append(c);
            }
        }
        
        if (currentArg.length() > 0) {
            arguments.add(parseExpression(currentArg.toString().trim()));
        }
        
        return arguments;
    }
    
    /**
     * 检查是否是子查询
     */
    private boolean isSubquery(String expression) {
        return expression.trim().startsWith("(") && expression.trim().endsWith(")");
    }
    
    /**
     * 解析子查询
     */
    private Subquery parseSubquery(String expression) throws SemanticException {
        // TODO: 实现子查询解析
        throw new SemanticException("Subquery parsing not implemented yet");
    }
    
    /**
     * 解析二元表达式
     */
    private BinaryExpression parseBinaryExpression(String expression) throws SemanticException {
        // 找到优先级最低的操作符
        int lowestPrecedence = Integer.MAX_VALUE;
        int operatorIndex = -1;
        String operator = null;
        
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                // 检查是否是操作符
                for (String op : operatorPrecedence.keySet()) {
                    if (expression.substring(i).startsWith(op)) {
                        int precedence = operatorPrecedence.get(op);
                        if (precedence < lowestPrecedence) {
                            lowestPrecedence = precedence;
                            operatorIndex = i;
                            operator = op;
                        }
                        break;
                    }
                }
            }
        }
        
        if (operatorIndex == -1) {
            throw new SemanticException("Invalid expression: " + expression);
        }
        
        // 解析左右操作数
        String leftExpr = expression.substring(0, operatorIndex).trim();
        String rightExpr = expression.substring(operatorIndex + operator.length()).trim();
        
        Expression left = parseExpression(leftExpr);
        Expression right = parseExpression(rightExpr);
        
        return new BinaryExpression(operator, left, right);
    }
    
    /**
     * 初始化操作符优先级
     */
    private Map<String, Integer> initOperatorPrecedence() {
        Map<String, Integer> precedence = new HashMap<>();
        
        // 逻辑操作符
        precedence.put("OR", 1);
        precedence.put("AND", 2);
        precedence.put("NOT", 3);
        
        // 比较操作符
        precedence.put("=", 4);
        precedence.put("<>", 4);
        precedence.put("<", 4);
        precedence.put(">", 4);
        precedence.put("<=", 4);
        precedence.put(">=", 4);
        
        // 算术操作符
        precedence.put("+", 5);
        precedence.put("-", 5);
        precedence.put("*", 6);
        precedence.put("/", 6);
        precedence.put("%", 6);
        
        return precedence;
    }
} 