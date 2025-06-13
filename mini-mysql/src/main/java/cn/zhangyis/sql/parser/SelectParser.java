package cn.zhangyis.sql.parser;

import cn.zhangyis.exceptions.SQLParseException;
import cn.zhangyis.sql.parser.expression.ColumnExpression;
import cn.zhangyis.sql.parser.expression.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * SELECT语句解析器
 */
public class SelectParser extends SQLParser {
    private final ExpressionParser expressionParser;

    public SelectParser(List<SQLLexer.Token> tokens) {
        super(tokens);
        this.expressionParser = new ExpressionParser(this);
    }

    @Override
    public SQLStatement parse() {
        // 解析SELECT关键字
        match(SQLLexer.TokenType.SELECT);

        // 检查是否有DISTINCT
        boolean distinct = false;
        if (peek() != null && peek().getValue().equalsIgnoreCase("DISTINCT")) {
            consume();
            distinct = true;
        }

        // 解析选择项
        List<SelectStatement.SelectItem> selectItems = parseSelectItems();

        // 解析FROM子句
        List<SelectStatement.TableReference> fromTables = new ArrayList<>();
        if (peek() != null && peek().getType() == SQLLexer.TokenType.FROM) {
            match(SQLLexer.TokenType.FROM);
            fromTables = parseTableReferences();
        }

        // 解析JOIN子句
        List<SelectStatement.JoinClause> joins = new ArrayList<>();
        while (peek() != null && isJoinKeyword(peek())) {
            joins.add(parseJoin());
        }

        // 解析WHERE子句
        Expression whereCondition = null;
        if (peek() != null && peek().getType() == SQLLexer.TokenType.WHERE) {
            match(SQLLexer.TokenType.WHERE);
            // 设置上下文以支持子查询
            expressionParser.setContext(SQLExpressionContext.WHERE);
            whereCondition = expressionParser.parseExpression();
        }

        // 解析GROUP BY子句
        List<Expression> groupByColumns = new ArrayList<>();
        if (peek() != null && peek().getType() == SQLLexer.TokenType.GROUP) {
            match(SQLLexer.TokenType.GROUP);
            match(SQLLexer.TokenType.BY);
            expressionParser.setContext(SQLExpressionContext.GROUP_BY);
            groupByColumns = parseExpressionList();
        }

        // 解析HAVING子句
        Expression havingCondition = null;
        if (peek() != null && peek().getType() == SQLLexer.TokenType.HAVING) {
            match(SQLLexer.TokenType.HAVING);
            expressionParser.setContext(SQLExpressionContext.HAVING);
            havingCondition = expressionParser.parseExpression();
        }

        // 解析ORDER BY子句
        List<SelectStatement.OrderByItem> orderByItems = new ArrayList<>();
        if (peek() != null && peek().getType() == SQLLexer.TokenType.ORDER) {
            match(SQLLexer.TokenType.ORDER);
            match(SQLLexer.TokenType.BY);
            expressionParser.setContext(SQLExpressionContext.ORDER_BY);
            orderByItems = parseOrderByItems();
        }

        // 解析LIMIT子句  limt 5 or limt 5,10
        Integer offset = null;
        Integer limit = null;
        if (peek() != null && peek().getValue().equalsIgnoreCase("LIMIT")) {
            consume();
            // 检查是否有可选的LIMIT值
            if (peek() != null && peek().getType() == SQLLexer.TokenType.NUMBER) {
                offset = Integer.parseInt(consume().getValue());
            }
            // 检查是否有可选的OFFSET值 limt 5,10
            if (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA) {
                consume(); // 消费逗号
                if (peek() != null && peek().getType() == SQLLexer.TokenType.NUMBER) {
                    limit = Integer.parseInt(consume().getValue());
                } else {
                    throw new SQLParseException("Expected number after LIMIT comma");
                }
            }else if (offset != null) {
                limit = offset; // 如果只有一个数字，则作为LIMIT值
                offset = null; // 清除偏移量
            }

        }

        // 解析可选的分号
        if (peek() != null && peek().getType() == SQLLexer.TokenType.SEMICOLON) {
            match(SQLLexer.TokenType.SEMICOLON);
        }

        return new SelectStatement(
                selectItems, fromTables, joins, whereCondition,
                groupByColumns, havingCondition, orderByItems, limit,offset, distinct
        );
    }

    private boolean isJoinKeyword(SQLLexer.Token token) {
        if (token == null) return false;
        return token.getType() == SQLLexer.TokenType.JOIN ||
                token.getType() == SQLLexer.TokenType.INNER ||
                token.getType() == SQLLexer.TokenType.LEFT ||
                token.getType() == SQLLexer.TokenType.RIGHT;
    }

    // 检查当前位置是否是表限定的通配符 (如 "a.*")
    private boolean isTableQualifiedWildcard() {
        return peek() != null && peek().getType() == SQLLexer.TokenType.IDENTIFIER &&
                peekNext() != null && peekNext().getType() == SQLLexer.TokenType.DOT &&
                peekNext(2) != null && peekNext(2).getType() == SQLLexer.TokenType.STAR;
    }

    // 获取标识符或字符串作为别名
    private String expectIdentifierOrString() {
        if (peek() == null) {
            throw new SQLParseException("Expected identifier or string for alias");
        }

        if (peek().getType() == SQLLexer.TokenType.IDENTIFIER) {
            return consume().getValue();
        } else if (peek().getType() == SQLLexer.TokenType.STRING) {
            return consume().getValue();
        }

        throw new SQLParseException("Expected identifier or string for alias");
    }


    /**
     * 解析查询结果
     * @return
     */
    private List<SelectStatement.SelectItem> parseSelectItems() {
        List<SelectStatement.SelectItem> items = new ArrayList<>();

        do {
            // 检查是否遇到了 FROM 关键字，这表示选择项列表已结束
            if (isClauseKeyword(peek())) {
                break;
            }

            // 处理表限定的通配符 (如 "a.*")
            if (isTableQualifiedWildcard()) {
                String tableAlias = consume().getValue(); // 获取表别名
                consume(); // 消费点号
                consume(); // 消费星号
                Expression starExpr = new ColumnExpression(tableAlias, true);
                items.add(new SelectStatement.SelectItem(starExpr, tableAlias));
            }
            // 处理简单的 "*" 通配符
            else if (peek() != null && peek().getType() == SQLLexer.TokenType.STAR) {
                consume();
                Expression starExpr = new ColumnExpression("*", true);
                items.add(new SelectStatement.SelectItem(starExpr, null));
            }
            // 处理常规表达式 (列、函数等)
            else {
                expressionParser.setContext(SQLExpressionContext.SELECT);
                Expression expr = expressionParser.parseExpression();
                String alias = null;

                // 处理别名 - 支持有或没有 AS 关键字
                if (peek() != null && !isClauseKeyword(peek())) {
                    if (peek().getValue().equalsIgnoreCase("AS")) {
                        consume(); // 跳过 AS 关键字
                        alias = expectIdentifierOrString();
                    } else if (peek().getType() == SQLLexer.TokenType.IDENTIFIER &&
                            !isReservedKeyword(peek().getValue())) {
                        // 直接别名标识符 (无 AS)
                        alias = consume().getValue();
                    }
                }

                items.add(new SelectStatement.SelectItem(expr, alias));
            }
        } while (consumeIfMatch(SQLLexer.TokenType.COMMA));

        return items;
    }
    // 检查是否是SQL保留关键字
    private boolean isReservedKeyword(String word) {
        String upper = word.toUpperCase();
        return upper.equals("FROM") || upper.equals("WHERE") || upper.equals("GROUP") ||
                upper.equals("ORDER") || upper.equals("HAVING") || upper.equals("LIMIT") ||
                upper.equals("JOIN") || upper.equals("ON") || upper.equals("AND") ||
                upper.equals("OR") || upper.equals("IN") || upper.equals("NOT") ||
                upper.equals("BETWEEN") || upper.equals("LIKE") || upper.equals("IS") ||
                upper.equals("NULL") || upper.equals("TRUE") || upper.equals("FALSE") ||
                upper.equals("ASC") || upper.equals("DESC") || upper.equals("UNION") ||
                upper.equals("INTERSECT") || upper.equals("EXCEPT") || upper.equals("CASE") ||
                upper.equals("WHEN") || upper.equals("THEN") || upper.equals("ELSE") ||
                upper.equals("END") || upper.equals("EXISTS");
    }

    // 检查是否是子句开始关键字（FROM、WHERE 等）
    private boolean isClauseKeyword(SQLLexer.Token token) {
        if (token == null) return false;
        String upper = token.getValue().toUpperCase();
        return upper.equals("FROM") ;
    }


    private List<SelectStatement.TableReference> parseTableReferences() {
        List<SelectStatement.TableReference> tables = new ArrayList<>();

        do {
            tables.add(parseTableReference());

            // 如果有逗号，继续解析表引用
        } while (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA && consume() != null);

        return tables;
    }

    private SelectStatement.TableReference parseTableReference() {
        // 处理子查询
        if (peek() != null && peek().getType() == SQLLexer.TokenType.LEFT_PAREN) {
            consume(); // 消费(

            // 解析子查询
            SelectParser subqueryParser = new SelectParser(tokens.subList(pos, tokens.size()));
            SelectStatement subquery = (SelectStatement) subqueryParser.parse();

            // 调整父解析器的位置
            pos += subqueryParser.pos;

            match(SQLLexer.TokenType.RIGHT_PAREN);

            // 检查别名（子查询必须有别名）
            String alias = null;
            if (peek() != null && peek().getValue().equalsIgnoreCase("AS")) {
                consume();
                alias = match(SQLLexer.TokenType.IDENTIFIER).getValue();
            } else if (peek() != null && peek().getType() == SQLLexer.TokenType.IDENTIFIER) {
                alias = consume().getValue();
            } else {
                throw new RuntimeException("子查询必须有别名");
            }

            return new SelectStatement.TableReference(subquery, alias);
        }

        // 常规表引用
        String tableName = match(SQLLexer.TokenType.IDENTIFIER).getValue();
        String alias = null;

        // 检查可选的别名
        if (peek() != null &&
                (peek().getValue().equalsIgnoreCase("AS") || peek().getType() == SQLLexer.TokenType.IDENTIFIER)) {

            // 处理可选的AS关键字
            if (peek().getValue().equalsIgnoreCase("AS")) {
                consume();
            }

            if (peek() != null && peek().getType() == SQLLexer.TokenType.IDENTIFIER) {
                alias = consume().getValue();
            }
        }

        return new SelectStatement.TableReference(tableName, alias);
    }

    private SelectStatement.JoinClause parseJoin() {
        // 确定连接类型
        SelectStatement.JoinClause.JoinType joinType = SelectStatement.JoinClause.JoinType.INNER;

        if (peek().getType() == SQLLexer.TokenType.LEFT) {
            consume();
            joinType = SelectStatement.JoinClause.JoinType.LEFT;
        } else if (peek().getType() == SQLLexer.TokenType.RIGHT) {
            consume();
            joinType = SelectStatement.JoinClause.JoinType.RIGHT;
        } else if (peek().getType() == SQLLexer.TokenType.INNER) {
            consume();
        }

        // 匹配JOIN关键字
        match(SQLLexer.TokenType.JOIN);

        // 解析被连接的表
        SelectStatement.TableReference joinTable = parseTableReference();

        // 解析ON条件
        match(SQLLexer.TokenType.ON);
        expressionParser.setContext(SQLExpressionContext.ON);
        Expression joinCondition = expressionParser.parseExpression();

        return new SelectStatement.JoinClause(joinType, joinTable, joinCondition);
    }

    private List<Expression> parseExpressionList() {
        List<Expression> expressions = new ArrayList<>();

        do {
            expressions.add(expressionParser.parseExpression());

            // 如果有逗号，继续解析表达式
        } while (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA && consume() != null);

        return expressions;
    }

    private List<SelectStatement.OrderByItem> parseOrderByItems() {
        List<SelectStatement.OrderByItem> items = new ArrayList<>();

        do {
            Expression expr = expressionParser.parseExpression();
            boolean ascending = true;

            // 检查可选的ASC/DESC
            if (peek() != null && peek().getType() == SQLLexer.TokenType.DESC) {
                consume();
                ascending = false;
            } else if (peek() != null && peek().getType() == SQLLexer.TokenType.ASC) {
                consume();
            }

            items.add(new SelectStatement.OrderByItem(expr, ascending));

            // 如果有逗号，继续解析ORDER BY项
        } while (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA && consume() != null);

        return items;
    }
}