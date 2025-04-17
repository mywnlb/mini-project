package cn.zhangyis.sql;

import java.util.List;

/**
 * delete from tb_test where 1=1 and a = 1 and b ='c';
 */
public class DeleteParser extends SQLParser {
    private final ExpressionParser expressionParser;

    public DeleteParser(List<SQLLexer.Token> tokens) {
        super(tokens);
        this.expressionParser = new ExpressionParser(this);
    }

    @Override
    public SQLStatement parse() {
        match(SQLLexer.TokenType.DELETE);
        match(SQLLexer.TokenType.FROM);
        String tableName = match(SQLLexer.TokenType.IDENTIFIER).getValue();
        
        // 检查是否有WHERE子句
        Expression whereCondition = null;
        if (peek() != null && peek().getType() == SQLLexer.TokenType.WHERE) {
            match(SQLLexer.TokenType.WHERE);
            whereCondition = expressionParser.parseExpression();
        }

        // Parse semicolon ;
        match(SQLLexer.TokenType.SEMICOLON);

        return new DeleteStatement(tableName, whereCondition);
    }
}