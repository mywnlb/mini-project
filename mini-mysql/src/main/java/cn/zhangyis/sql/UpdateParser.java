package cn.zhangyis.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * UPDATE table_name SET column1 = value1, column2 = value2, ... WHERE condition;
 */
public class UpdateParser extends SQLParser {
    private final ExpressionParser expressionParser;

    public UpdateParser(List<SQLLexer.Token> tokens) {
        super(tokens);
        this.expressionParser = new ExpressionParser(this);
    }

    @Override
    public SQLStatement parse() {
        // Parse UPDATE keyword
        match(SQLLexer.TokenType.UPDATE);

        // Parse table name
        String tableName = match(SQLLexer.TokenType.IDENTIFIER).getValue();

        // Parse SET keyword
        match(SQLLexer.TokenType.SET);

        // Parse assignments
        List<UpdateStatement.Assignment> assignments = parseAssignments();

        // Parse optional WHERE clause
        Expression whereCondition = null;
        if (peek() != null && peek().getType() == SQLLexer.TokenType.WHERE) {
            match(SQLLexer.TokenType.WHERE);
            whereCondition = expressionParser.parseExpression();
        }

        // Parse semicolon ;
        match(SQLLexer.TokenType.SEMICOLON);

        return new UpdateStatement(tableName, assignments, whereCondition);
    }

    private List<UpdateStatement.Assignment> parseAssignments() {
        List<UpdateStatement.Assignment> assignments = new ArrayList<>();

        // Parse first assignment
        assignments.add(parseAssignment());

        // Parse remaining comma-separated assignments
        while (peek() != null && peek().getType() == SQLLexer.TokenType.COMMA) {
            match(SQLLexer.TokenType.COMMA);
            assignments.add(parseAssignment());
        }

        return assignments;
    }

    private UpdateStatement.Assignment parseAssignment() {
        // Parse column name
        String columnName = match(SQLLexer.TokenType.IDENTIFIER).getValue();

        // Match equals sign
        match(SQLLexer.TokenType.EQUALS);

        // Parse value expression
        Expression valueExpression = expressionParser.parseExpression();

        return new UpdateStatement.Assignment(columnName, valueExpression);
    }
}