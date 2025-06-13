package cn.zhangyis.sql.parser;

import cn.zhangyis.sql.parser.expression.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description 支持 insert tb_test (a,b)? values (1,2) | select * from tb_test;
 * @Date 2025/3/17 21:02
 * @Created by libo
 */
public class InsertParser extends SQLParser{

    public InsertParser(List<SQLLexer.Token> tokens) {
        super(tokens);

    }

    @Override
    public SQLStatement parse() {
        // 解析 INSERT 关键字
        match(SQLLexer.TokenType.INSERT);
        match(SQLLexer.TokenType.INTO);

        // 解析表名
        String tableName = match(SQLLexer.TokenType.IDENTIFIER).getValue();

        // 解析可选的列名列表
        List<String> columnNames = new ArrayList<>();
        if (peek() != null && peek().getType() == SQLLexer.TokenType.LEFT_PAREN) {
            consume(); // 消费左括号

            // 解析列名列表
            do {
                columnNames.add(match(SQLLexer.TokenType.IDENTIFIER).getValue());
            } while (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA && consume() != null);

            match(SQLLexer.TokenType.RIGHT_PAREN);
        }

        // 处理 VALUES 子句或 SELECT 子句
        if (peek() != null && peek().getValue().equalsIgnoreCase("VALUES")) {
            consume(); // 消费 VALUES 关键字

            // 解析值列表
            List<List<Expression>> valuesList = new ArrayList<>();

            do {
                match(SQLLexer.TokenType.LEFT_PAREN);

                List<Expression> rowValues = new ArrayList<>();
                ExpressionParser expressionParser = new ExpressionParser(this);

                do {
                    rowValues.add(expressionParser.parseExpression());
                } while (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA && consume() != null);

                match(SQLLexer.TokenType.RIGHT_PAREN);
                valuesList.add(rowValues);

            } while (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA && consume() != null);

            // 解析可选的分号
            if (peek() != null && peek().getType() == SQLLexer.TokenType.SEMICOLON) {
                consume();
            }

            return new InsertStatement(tableName, columnNames, valuesList);
        }
        else if (peek() != null && peek().getType() == SQLLexer.TokenType.SELECT) {
            // 处理 INSERT ... SELECT ... 形式
            SelectParser selectParser = new SelectParser(tokens.subList(pos, tokens.size()));
            SelectStatement selectStatement = (SelectStatement) selectParser.parse();

            // 调整父解析器中的位置
            pos += selectParser.getPos();

            return new InsertStatement(tableName, columnNames, selectStatement);
        }
        else {
            throw new RuntimeException("Expected VALUES or SELECT after INSERT");
        }
    }
}
