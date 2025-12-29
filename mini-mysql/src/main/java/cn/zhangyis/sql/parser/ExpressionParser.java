package cn.zhangyis.sql.parser;

import cn.zhangyis.sql.parser.enums.ArithmeticOperator;
import cn.zhangyis.sql.parser.enums.ComparisonOperator;
import cn.zhangyis.sql.parser.enums.LiteralType;
import cn.zhangyis.sql.parser.enums.LogicalOperator;
import cn.zhangyis.sql.parser.expression.*;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL表达式解析器，用于解析WHERE子句和其他表达式
 * 可被SELECT、DELETE、UPDATE等语句共用
 */
public class ExpressionParser {
    private final SQLParser parser;
    private SQLExpressionContext context;

    public ExpressionParser(SQLParser parser) {
        this(parser, SQLExpressionContext.WHERE); // 默认为 WHERE 上下文
    }

    public ExpressionParser(SQLParser parser, SQLExpressionContext context) {
        this.parser = parser;
        this.context = context;
    }

    // 设置当前解析上下文
    public void setContext(SQLExpressionContext context) {
        this.context = context;
    }

    /**
     * 根据当前上下文解析表达
     */
    public Expression parseExpression() {
        switch (context) {
            case SELECT:
                return parseSelectExpression();
            case ORDER_BY:
                return parseOrderByExpression();
            case GROUP_BY:
                return parseGroupByExpression();
            case ON:
                return parseJoinConditionExpression();
            case HAVING:
                return parseHavingExpression(); // 使用专门的HAVING解析方法
            case WHERE:
            default:
                return parseConditionExpression();
        }
    }

    /**
     * 解析HAVING子句中的表达式，支持聚合函数
     */
    private Expression parseHavingExpression() {
        // 首先尝试解析为一个可能的聚合函数表达式
        if (isAggregateFunction(parser.peek())) {
            Expression left = parseSelectExpression(); // 复用解析聚合函数的逻辑

            // 解析比较运算符
            if (parser.peek() != null) {
                SQLLexer.TokenType type = parser.peek().getType();
                ComparisonOperator op = null;

                if (type == SQLLexer.TokenType.EQUALS) {
                    parser.match(SQLLexer.TokenType.EQUALS);
                    op = ComparisonOperator.EQUALS;
                } else if (type == SQLLexer.TokenType.GREATER) {
                    parser.match(SQLLexer.TokenType.GREATER);
                    op = ComparisonOperator.GREATER;
                } // 其他比较运算符...

                if (op != null) {
                    Expression right = parseArithmeticExpression();
                    return new ComparisonExpression(left, op, right);
                }
            }

            return left;
        }

        // 否则按照普通条件表达式处理
        return parseConditionExpression();
    }

    /**
     * 解析SELECT表达式，包括各类函数和DISTINCT关键字
     */
    public Expression parseSelectExpression() {

        // 处理函数表达式
        if (isSQLFunction(parser.peek())) {
            return parseFunctionExpression();
        }

        // 解析普通表达式
        return parseArithmeticExpression();
    }

    /**
     * 检查是否是SQL函数
     */
    private boolean isSQLFunction(SQLLexer.Token token) {
        if (token == null) return false;

        SQLLexer.TokenType type = token.getType();

        if (Objects.equals(type, SQLLexer.TokenType.DISTINCT)) {
            return true;
        }

        // 聚合函数
        if (isAggregateFunction(token)) return true;

        // 字符串函数
        if (isStringFunction(type)) return true;

        // 数值函数
        if (isNumericFunction(type)) return true;

        // 日期函数
        if (isDateFunction(type)) return true;

        // 条件和转换函数
        return isConditionalFunction(type) || isConversionFunction(type) || isOtherFunction(type);
    }

    /**
     * 解析函数表达式
     */
    private Expression parseFunctionExpression() {
        // 获取函数名
        String funcName = parser.consume().getValue();
        SQLLexer.TokenType funcType = SQLLexer.TokenType.valueOf(funcName.toUpperCase());

        // CASE 特殊处理
        if (funcType == SQLLexer.TokenType.CASE) {
            return parseCaseExpression();
        }

        //处理 distinct
        boolean isDistinct = false;
        if (funcType == SQLLexer.TokenType.DISTINCT) {
            isDistinct = true;

            return parserDistinctFunction(funcName);
        }

        // CAST 和 CONVERT 特殊处理
        if (funcType == SQLLexer.TokenType.CAST || funcType == SQLLexer.TokenType.CONVERT) {
            return parseTypeCastExpression(funcName);
        }

        // IFNULL, COALESCE 等条件函数特殊处理
        if (isConditionalFunction(funcType)) {
            return parseConditionalFunction(funcName, funcType);
        }

        // 字符串函数特殊处理
        if (isStringFunction(funcType)) {
            return parseStringFunction(funcName, funcType);
        }

        // 日期函数特殊处理
        if (isDateFunction(funcType)) {
            return parseDateFunction(funcName, funcType);
        }

        // 处理常规函数调用
        parser.match(SQLLexer.TokenType.LEFT_PAREN);

        // 处理无参数函数
        if (isNoArgFunction(funcType)) {
            parser.match(SQLLexer.TokenType.RIGHT_PAREN);
            return new FunctionExpression(funcName, new ArrayList<>(), false, false);
        }

        // 处理聚合函数特殊情况
        if (funcName.equalsIgnoreCase("COUNT") &&
                parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.STAR) {
            parser.consume(); // 消费 *
            parser.match(SQLLexer.TokenType.RIGHT_PAREN);
            List<Expression> args = new ArrayList<>();
            args.add(new ColumnExpression("*"));
            return new FunctionExpression(funcName, args, true, false);
        }
        // 解析函数参数
        List<Expression> args = parseArgumentList();

        return new FunctionExpression(
                funcName,
                args,
                isAggregateFunction(SQLLexer.TokenType.valueOf(funcName.toUpperCase())),
                isDistinct
        );
    }

    /**
     * 解析DISTINCT函数
     * 目前仅处理 DISTINCT col,DISTINCT a.id
     */
    private Expression parserDistinctFunction(String funcName) {
        List<Expression> args = Lists.newArrayList();

        // 解析DISTINCT后面的列表达式
        if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.IDENTIFIER) {
            String identifier = parser.peek().getValue();
            parser.match(SQLLexer.TokenType.IDENTIFIER);

            // 检查是否是表名.列名格式
            if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.DOT) {
                parser.match(SQLLexer.TokenType.DOT);
                if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.IDENTIFIER) {
                    String columnName = parser.peek().getValue();
                    parser.match(SQLLexer.TokenType.IDENTIFIER);
                    // 添加表名.列名表达式
                    args.add(new ColumnExpression(identifier, columnName));
                } else {
                    throw new RuntimeException("Expected column name after table alias");
                }
            } else {
                // 添加单列表达式
                args.add(new ColumnExpression(identifier));
            }
        } else {
            throw new RuntimeException("Expected column name after DISTINCT");
        }

        // 创建FunctionExpression并设置参数、DISTINCT标志
        return new FunctionExpression(funcName, args, false, true);
    }

    /**
     * 解析函数参数列表
     */
    private List<Expression> parseArgumentList() {
        List<Expression> args = new ArrayList<>();

        // 空参数列表
        if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.RIGHT_PAREN) {
            parser.match(SQLLexer.TokenType.RIGHT_PAREN);
            return args;
        }

        // 解析第一个参数
        args.add(parseExpression());

        // 解析剩余参数
        while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.COMMA) {
            parser.consume(); // 消费逗号
            args.add(parseExpression());
        }

        parser.match(SQLLexer.TokenType.RIGHT_PAREN);
        return args;
    }

    /**
     * 解析CASE表达式
     */
    private Expression parseCaseExpression() {
        List<Expression> args = new ArrayList<>();
        Expression caseValue = null;

        // 检查是否是简单CASE表达式（带有CASE后的表达式）
        if (parser.peek() != null && parser.peek().getType() != SQLLexer.TokenType.WHEN) {
            caseValue = parseExpression();
            args.add(caseValue); // 第一个参数是CASE后的表达式
        }

        // 解析WHEN-THEN对
        while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.WHEN) {
            parser.match(SQLLexer.TokenType.WHEN);
            Expression whenExpr = parseExpression();
            args.add(whenExpr);

            parser.match(SQLLexer.TokenType.THEN);
            Expression thenExpr = parseExpression();
            args.add(thenExpr);
        }

        // 解析可选的ELSE子句
        if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.ELSE) {
            parser.match(SQLLexer.TokenType.ELSE);
            Expression elseExpr = parseExpression();
            args.add(elseExpr);
        }

        // 必须以END结束
        parser.match(SQLLexer.TokenType.END);

        return new FunctionExpression("CASE", args, false, false);
    }

    /**
     * 解析CAST或CONVERT表达式
     */
    private Expression parseTypeCastExpression(String funcName) {
        parser.match(SQLLexer.TokenType.LEFT_PAREN);
        Expression expr = parseExpression();

        // 为CAST处理AS关键字
        if (funcName.equalsIgnoreCase("CAST") &&
                parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.AS) {
            parser.consume(); // 消费AS
            // 消费数据类型标识符
            if (parser.peek() != null) {
                parser.consume();
            }
        }

        parser.match(SQLLexer.TokenType.RIGHT_PAREN);

        List<Expression> args = new ArrayList<>();
        args.add(expr);
        return new FunctionExpression(funcName, args, false, false);
    }

    /**
     * 检查是否是聚合函数
     */
    private boolean isAggregateFunction(SQLLexer.Token token) {
        if (token == null) return false;
        return isAggregateFunction(token.getType());
    }

    private boolean isAggregateFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.COUNT || type == SQLLexer.TokenType.SUM ||
                type == SQLLexer.TokenType.AVG || type == SQLLexer.TokenType.MIN ||
                type == SQLLexer.TokenType.MAX || type == SQLLexer.TokenType.GROUP_CONCAT;
    }

    /**
     * 检查是否是字符串函数
     */
    private boolean isStringFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.CONCAT || type == SQLLexer.TokenType.SUBSTR ||
                type == SQLLexer.TokenType.SUBSTRING || type == SQLLexer.TokenType.UPPER ||
                type == SQLLexer.TokenType.LOWER || type == SQLLexer.TokenType.TRIM ||
                type == SQLLexer.TokenType.LTRIM || type == SQLLexer.TokenType.RTRIM ||
                type == SQLLexer.TokenType.LENGTH;
    }

    /**
     * 检查是否是数值函数
     */
    private boolean isNumericFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.ROUND || type == SQLLexer.TokenType.CEIL ||
                type == SQLLexer.TokenType.CEILING || type == SQLLexer.TokenType.FLOOR ||
                type == SQLLexer.TokenType.ABS || type == SQLLexer.TokenType.RAND ||
                type == SQLLexer.TokenType.POW || type == SQLLexer.TokenType.POWER ||
                type == SQLLexer.TokenType.SQRT || type == SQLLexer.TokenType.MOD;
    }

    /**
     * 检查是否是日期函数
     */
    private boolean isDateFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.NOW || type == SQLLexer.TokenType.CURDATE ||
                type == SQLLexer.TokenType.CURRENT_DATE || type == SQLLexer.TokenType.CURTIME ||
                type == SQLLexer.TokenType.DATE_FORMAT ||
                type == SQLLexer.TokenType.DATEDIFF || type == SQLLexer.TokenType.DATE_ADD ||
                type == SQLLexer.TokenType.DATE_SUB || type == SQLLexer.TokenType.EXTRACT ||
                type == SQLLexer.TokenType.YEAR || type == SQLLexer.TokenType.MONTH ||
                type == SQLLexer.TokenType.DAY;
    }

    /**
     * 检查是否是条件函数
     */
    private boolean isConditionalFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.IFNULL || type == SQLLexer.TokenType.COALESCE ||
                type == SQLLexer.TokenType.CASE || type == SQLLexer.TokenType.IF;
    }

    /**
     * 检查是否是转换函数
     */
    private boolean isConversionFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.CAST || type == SQLLexer.TokenType.CONVERT;
    }

    /**
     * 检查是否是其他函数
     */
    private boolean isOtherFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.JSON_EXTRACT;
    }

    /**
     * 检查是否是无参数函数
     */
    private boolean isNoArgFunction(SQLLexer.TokenType type) {
        return type == SQLLexer.TokenType.NOW || type == SQLLexer.TokenType.CURDATE ||
                type == SQLLexer.TokenType.CURRENT_DATE || type == SQLLexer.TokenType.CURTIME ||
                type == SQLLexer.TokenType.RAND;
    }

    // 原有的表达式解析方法改名为条件表达式解析
    private Expression parseConditionExpression() {
        Expression left = parseAndExpression();

        while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.OR) {
            parser.match(SQLLexer.TokenType.OR);
            Expression right = parseAndExpression();
            left = new LogicalExpression(left, LogicalOperator.OR, right);
        }

        return left;
    }

    // 解析 ORDER BY 特有表达式
    private Expression parseOrderByExpression() {
        // 只返回列表达式，不处理 ASC/DESC
        return parsePrimaryExpression();
    }

    // JOIN ON 条件解析
    private Expression parseJoinConditionExpression() {
        // JOIN ON 条件与 WHERE 条件语法相似，可以复用
        return parseConditionExpression();
    }

    // GROUP BY 表达式解析
    private Expression parseGroupByExpression() {
        return parsePrimaryExpression();
    }

    private Expression parseAndExpression() {
        Expression left = parsePredicateExpression(); // 使用新方法替代parseEqualityExpression

        while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.AND) {
            parser.match(SQLLexer.TokenType.AND);
            Expression right = parsePredicateExpression(); // 使用新方法替代parseEqualityExpression
            left = new LogicalExpression(left, LogicalOperator.AND, right);
        }

        return left;
    }

    /**
     * 解析谓词表达式，包括等式比较和IN操作符
     *
     * @return 解析后的表达式对象
     */
    private Expression parsePredicateExpression() {
        // 解析算术表达式，然后再处理比较运算符
        Expression left = parseArithmeticExpression();

        // 尝试解析子查询表达式（EXISTS、IN等）
        Expression subqueryExpr = parseSubqueryExpression(left);
        if (subqueryExpr != null) {
            return subqueryExpr;
        }

        if (parser.peek() != null) {
            SQLLexer.TokenType type = parser.peek().getType();

            // 处理标准比较运算符
            if (type == SQLLexer.TokenType.EQUALS) {
                parser.match(SQLLexer.TokenType.EQUALS);
                Expression right = parseArithmeticExpression();
                return new ComparisonExpression(left, ComparisonOperator.EQUALS, right);
            } else if (type == SQLLexer.TokenType.NOT_EQUALS) {
                parser.match(SQLLexer.TokenType.NOT_EQUALS);
                Expression right = parseArithmeticExpression();
                return new ComparisonExpression(left, ComparisonOperator.NOT_EQUALS, right);
            } else if (type == SQLLexer.TokenType.GREATER) {
                parser.match(SQLLexer.TokenType.GREATER);
                Expression right = parseArithmeticExpression();
                return new ComparisonExpression(left, ComparisonOperator.GREATER, right);
            } else if (type == SQLLexer.TokenType.GREATER_EQUALS) {
                parser.match(SQLLexer.TokenType.GREATER_EQUALS);
                Expression right = parseArithmeticExpression();
                return new ComparisonExpression(left, ComparisonOperator.GREATER_EQUALS, right);
            } else if (type == SQLLexer.TokenType.LESS) {
                parser.match(SQLLexer.TokenType.LESS);
                Expression right = parseArithmeticExpression();
                return new ComparisonExpression(left, ComparisonOperator.LESS, right);
            } else if (type == SQLLexer.TokenType.LESS_EQUALS) {
                parser.match(SQLLexer.TokenType.LESS_EQUALS);
                Expression right = parseArithmeticExpression();
                return new ComparisonExpression(left, ComparisonOperator.LESS_EQUALS, right);
            }

            // 处理IN操作符 - 已移至parseSubqueryExpression方法中处理
        }

        return left;
    }

    /**
     * 解析算术表达式，处理加法和减法
     * 遵循运算符优先级：先乘除，后加减
     */
    private Expression parseArithmeticExpression() {
        Expression left = parseMultiplicativeExpression();

        while (parser.peek() != null) {
            SQLLexer.TokenType type = parser.peek().getType();
            if (type == SQLLexer.TokenType.PLUS) {
                parser.consume();
                Expression right = parseMultiplicativeExpression();
                left = new ArithmeticExpression(left, ArithmeticOperator.ADD, right);
            } else if (type == SQLLexer.TokenType.MINUS) {
                parser.consume();
                Expression right = parseMultiplicativeExpression();
                left = new ArithmeticExpression(left, ArithmeticOperator.SUBTRACT, right);
            } else {
                break;
            }
        }

        return left;
    }

    /**
     * 解析乘法、除法和取模表达式
     */
    private Expression parseMultiplicativeExpression() {
        Expression left = parsePrimaryExpression();

        while (parser.peek() != null) {
            SQLLexer.TokenType type = parser.peek().getType();
            if (type == SQLLexer.TokenType.STAR) {
                parser.consume();
                Expression right = parsePrimaryExpression();
                left = new ArithmeticExpression(left, ArithmeticOperator.MULTIPLY, right);
            } else if (type == SQLLexer.TokenType.DIVIDE) {
                parser.consume();
                Expression right = parsePrimaryExpression();
                left = new ArithmeticExpression(left, ArithmeticOperator.DIVIDE, right);
            } else if (type == SQLLexer.TokenType.MODULO) {
                parser.consume();
                Expression right = parsePrimaryExpression();
                left = new ArithmeticExpression(left, ArithmeticOperator.MODULO, right);
            } else {
                break;
            }
        }

        return left;
    }

    /**
     * 解析基本表达式（标识符、字面量等）
     *
     * @return 解析后的表达式对象
     */
    private Expression parsePrimaryExpression() {
        SQLLexer.Token token = parser.peek();
        if (token == null) {
            throw new RuntimeException("Unexpected end of input");
        }

        switch (token.getType()) {
            case IDENTIFIER:
                parser.match(SQLLexer.TokenType.IDENTIFIER);
                String identifier = token.getValue();

                // Check if the next token is a dot, indicating a table.column pattern
                if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.DOT) {
                    parser.match(SQLLexer.TokenType.DOT);
                    // The next token must be another identifier for the column name
                    if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.IDENTIFIER) {
                        SQLLexer.Token columnToken = parser.match(SQLLexer.TokenType.IDENTIFIER);
                        return new ColumnExpression(identifier, columnToken.getValue());
                    } else {
                        throw new RuntimeException("Expected column name after table alias");
                    }
                }

                // Simple column without table alias
                return new ColumnExpression(identifier);
            case NUMBER:
                parser.match(SQLLexer.TokenType.NUMBER);
                return new LiteralExpression(token.getValue(), LiteralType.NUMBER);
            case STRING:
                parser.match(SQLLexer.TokenType.STRING);
                return new LiteralExpression(token.getValue(), LiteralType.STRING);
            case NULL:
                parser.match(SQLLexer.TokenType.NULL);
                return new LiteralExpression("null", LiteralType.NULL);
            case LEFT_PAREN:
                parser.match(SQLLexer.TokenType.LEFT_PAREN);
                Expression expr = parseExpression();
                parser.match(SQLLexer.TokenType.RIGHT_PAREN);
                return expr;
            default:
                throw new RuntimeException("Unexpected token: " + token);
        }
    }

    /**
     * 解析子查询表达式，包括EXISTS、IN等
     *
     * @param leftExpr 子查询左侧表达式（如IN操作符左侧的表达式）
     * @return 解析后的子查询表达式，如果不是子查询则返回null
     */
    private Expression parseSubqueryExpression(Expression leftExpr) {
        // 处理EXISTS谓词
        if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.EXISTS) {
            parser.consume();
            parser.match(SQLLexer.TokenType.LEFT_PAREN);

            // 解析子查询
            SelectParser subqueryParser = new SelectParser(
                    parser.tokens.subList(parser.pos, parser.tokens.size()));
            SelectStatement subquery = (SelectStatement) subqueryParser.parse();

            // 调整父解析器中的位置
            parser.setPos(parser.pos + subqueryParser.pos);

            parser.match(SQLLexer.TokenType.RIGHT_PAREN);
            return new SubqueryExpression(subquery, SubqueryExpression.SubqueryType.EXISTS);
        }

        // 处理IN谓词
        if (leftExpr != null && parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.IN) {
            parser.consume();
            parser.match(SQLLexer.TokenType.LEFT_PAREN);

            // 检查是子查询还是值列表
            if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.SELECT) {
                // 解析子查询
                SelectParser subqueryParser = new SelectParser(
                        parser.getTokens().subList(parser.getPos(), parser.getTokens().size()));
                SelectStatement subquery = (SelectStatement) subqueryParser.parse();

                // 调整父解析器中的位置
                parser.setPos(parser.getPos() + subqueryParser.getPos());

                parser.match(SQLLexer.TokenType.RIGHT_PAREN);
                return new SubqueryExpression(leftExpr, subquery, SubqueryExpression.SubqueryType.IN);
            } else {
                // 处理IN和值列表
                List<Expression> valueList = new ArrayList<>();
                do {
                    valueList.add(parseArithmeticExpression());
                } while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.COMMA
                        && parser.consume() != null);

                parser.match(SQLLexer.TokenType.RIGHT_PAREN);
                return new InListExpression(leftExpr, valueList, false);
            }
        }

        // 处理标量子查询
        if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.LEFT_PAREN) {
            int savedPos = parser.getPos(); // 保存当前位置，以便回退
            parser.match(SQLLexer.TokenType.LEFT_PAREN);

            // 向前看是否是子查询
            if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.SELECT) {
                // 解析子查询
                SelectParser subqueryParser = new SelectParser(
                        parser.getTokens().subList(parser.getPos(), parser.getTokens().size()));
                SelectStatement subquery = (SelectStatement) subqueryParser.parse();

                // 调整父解析器中的位置
                parser.setPos(parser.getPos() + subqueryParser.getPos());

                parser.match(SQLLexer.TokenType.RIGHT_PAREN);
                return new SubqueryExpression(subquery, SubqueryExpression.SubqueryType.SCALAR);
            } else {
                // 这不是子查询，回退并让其他解析方法处理
                parser.setPos(savedPos);
                return null;
            }
        }

        return null;
    }

    /**
     * 解析条件函数，如IFNULL, COALESCE, IF等
     */
    private Expression parseConditionalFunction(String funcName, SQLLexer.TokenType funcType) {
        parser.match(SQLLexer.TokenType.LEFT_PAREN);
        List<Expression> args = new ArrayList<>();
        
        // 对于IF函数，第一个参数是条件表达式
        if (funcType == SQLLexer.TokenType.IF) {
            // 临时切换到条件表达式上下文
            SQLExpressionContext originalContext = this.context;
            this.context = SQLExpressionContext.WHERE;
            
            // 解析条件表达式
            args.add(parseConditionExpression());
            
            // 恢复原始上下文
            this.context = originalContext;
        } else {
            // 其他函数的第一个参数
            args.add(parseExpression());
        }
        
        // 解析剩余参数
        while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.COMMA) {
            parser.consume(); // 消费逗号
            args.add(parseExpression());
        }
        
        parser.match(SQLLexer.TokenType.RIGHT_PAREN);
        
        // 验证参数数量
        if (funcType == SQLLexer.TokenType.IFNULL && args.size() != 2) {
            throw new RuntimeException("IFNULL function requires exactly 2 arguments");
        } else if (funcType == SQLLexer.TokenType.IF && args.size() != 3) {
            throw new RuntimeException("IF function requires exactly 3 arguments");
        } else if (funcType == SQLLexer.TokenType.COALESCE && args.isEmpty()) {
            throw new RuntimeException("COALESCE function requires at least 1 argument");
        }
        
        return new FunctionExpression(funcName, args, false, false);
    }

    /**
     * 解析字符串函数
     */
    private Expression parseStringFunction(String funcName, SQLLexer.TokenType funcType) {
        parser.match(SQLLexer.TokenType.LEFT_PAREN);
        List<Expression> args = new ArrayList<>();

        // 解析第一个参数
        args.add(parseExpression());

        // 解析剩余参数
        while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.COMMA) {
            parser.consume(); // 消费逗号
            args.add(parseExpression());
        }

        parser.match(SQLLexer.TokenType.RIGHT_PAREN);

        // 验证参数数量
        switch (funcType) {
            case CONCAT:
                if (args.size() < 2) {
                    throw new RuntimeException("CONCAT function requires at least 2 arguments");
                }
                break;
            case SUBSTR:
            case SUBSTRING:
                if (args.size() < 2 || args.size() > 3) {
                    throw new RuntimeException("SUBSTR/SUBSTRING function requires 2 or 3 arguments");
                }
                break;
            case UPPER:
            case LOWER:
            case TRIM:
            case LTRIM:
            case RTRIM:
            case LENGTH:
                if (args.size() != 1) {
                    throw new RuntimeException(funcName + " function requires exactly 1 argument");
                }
                break;
        }

        return new FunctionExpression(funcName, args, false, false);
    }

    /**
     * 解析日期函数
     */
    private Expression parseDateFunction(String funcName, SQLLexer.TokenType funcType) {
        // 处理无参数日期函数
        if (isNoArgFunction(funcType)) {
            parser.match(SQLLexer.TokenType.LEFT_PAREN);
            parser.match(SQLLexer.TokenType.RIGHT_PAREN);
            return new FunctionExpression(funcName, new ArrayList<>(), false, false);
        }

        parser.match(SQLLexer.TokenType.LEFT_PAREN);
        List<Expression> args = new ArrayList<>();

        // 解析第一个参数
        args.add(parseExpression());

        // 解析剩余参数
        while (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.COMMA) {
            parser.consume(); // 消费逗号
            args.add(parseExpression());
        }

        parser.match(SQLLexer.TokenType.RIGHT_PAREN);

        // 验证参数数量
        switch (funcType) {
            case DATE_FORMAT:
                if (args.size() != 2) {
                    throw new RuntimeException("DATE_FORMAT function requires exactly 2 arguments");
                }
                break;
            case DATEDIFF:
                if (args.size() != 2) {
                    throw new RuntimeException("DATEDIFF function requires exactly 2 arguments");
                }
                break;
            case DATE_ADD:
            case DATE_SUB:
                if (args.size() != 2) {
                    throw new RuntimeException(funcName + " function requires exactly 2 arguments");
                }
                break;
            case EXTRACT:
                if (args.size() != 1) {
                    throw new RuntimeException("EXTRACT function requires exactly 1 argument");
                }
                break;
            case YEAR:
            case MONTH:
            case DAY:
                if (args.size() != 1) {
                    throw new RuntimeException(funcName + " function requires exactly 1 argument");
                }
                break;
        }

        return new FunctionExpression(funcName, args, false, false);
    }
}