package cn.zhangyis.sql.parser;

import cn.zhangyis.sql.parser.enums.ArithmeticOperator;
import cn.zhangyis.sql.parser.enums.ComparisonOperator;
import cn.zhangyis.sql.parser.enums.LiteralType;
import cn.zhangyis.sql.parser.enums.LogicalOperator;
import cn.zhangyis.sql.parser.expression.*;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL表达式解析器，用于解析WHERE子句和其他表达式
 * 可被SELECT、DELETE、UPDATE等语句共用
 */
public class ExpressionParser{
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

    // Add to ExpressionParser class
    public Expression parseSelectExpression() {
        // 处理聚合函数
        if (isAggregateFunction(parser.peek())) {
            String funcName = parser.consume().getValue();
            parser.match(SQLLexer.TokenType.LEFT_PAREN);

            // 处理 COUNT(*)
            if (funcName.equalsIgnoreCase("COUNT") &&
                    parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.STAR) {
                parser.consume(); // 消费 *
                parser.match(SQLLexer.TokenType.RIGHT_PAREN);
                return new FunctionExpression("COUNT", new ColumnExpression(null, "*"));
            }
            // 处理 COUNT(a.*)
            else if (funcName.equalsIgnoreCase("COUNT") &&
                    parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.IDENTIFIER) {
                String tableAlias = parser.consume().getValue();

                if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.DOT) {
                    parser.consume(); // 消费 .

                    if (parser.peek() != null && parser.peek().getType() == SQLLexer.TokenType.STAR) {
                        parser.consume(); // 消费 *
                        parser.match(SQLLexer.TokenType.RIGHT_PAREN);
                        return new FunctionExpression("COUNT", new ColumnExpression(tableAlias, true));
                    } else {
                        // 回退到普通列引用 (COUNT(a.id))
                        parser.setPos(parser.getPos() - 2); // 回退到函数名后面
                    }
                } else {
                    // 回退到普通列引用 (COUNT(column))
                    parser.setPos(parser.getPos() - 1); // 回退到函数名后面
                }
            }

            // 解析函数参数 (处理 COUNT(a.id) 或其他普通表达式)
            Expression arg = parseExpression();
            parser.match(SQLLexer.TokenType.RIGHT_PAREN);
            return new FunctionExpression(funcName, arg);
        }

        // 解析普通表达式
        return parseArithmeticExpression();  // 修改为使用算术表达式解析
    }
    // Add helper method to detect aggregate functions
    private boolean isAggregateFunction(SQLLexer.Token token) {
        if (token == null) return false;
        SQLLexer.TokenType type = token.getType();
        return type == SQLLexer.TokenType.COUNT || type == SQLLexer.TokenType.SUM ||
               type == SQLLexer.TokenType.AVG || type == SQLLexer.TokenType.MIN || 
               type == SQLLexer.TokenType.MAX;
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
                return new InListExpression(leftExpr, valueList);
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
}