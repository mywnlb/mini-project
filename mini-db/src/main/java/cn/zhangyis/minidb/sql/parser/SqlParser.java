package cn.zhangyis.minidb.sql.parser;

import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.ast.*;

import java.util.ArrayList;
import java.util.List;

public class SqlParser {
    private final TokenStream tokens;
    private final SqlNodeFactory factory;

    public SqlParser(TokenStream tokens) {
        this.tokens = tokens;
        this.factory = SqlNodeFactory.DEFAULT;
    }

    public SqlSelect parseStatement() {
        if (tokens.current().type() == TokenType.SELECT) {
            return parseSelect();
        }
        throw new SqlParseException("Only SELECT supported");
    }

    private SqlSelect parseSelect() {
        tokens.expect(TokenType.SELECT);

        SqlNodeList projection = parseProjection();
        tokens.expect(TokenType.FROM);
        SqlIdentifier table = parseIdentifier();

        SqlNode where = null;
        if (tokens.match(TokenType.WHERE)) {
            where = parseSimpleWhere();
        }

        if (!tokens.isEOF()) {
            throw new SqlParseException("Extra tokens after statement");
        }

        return factory.select(projection, table, where);
    }

    private SqlNodeList parseProjection() {
        SqlNodeList projection = factory.nodeList();

        if (tokens.match(TokenType.STAR)) {
            projection.add(factory.star());
        } else {
            do {
                projection.add(parseIdentifier());
            } while (tokens.match(TokenType.COMMA));
        }

        return projection;
    }

    private SqlNode parseSimpleWhere() {
        SqlIdentifier left = parseIdentifier();
        tokens.expect(TokenType.EQ);
        SqlLiteral right = parseLiteral();
        return factory.binaryEq(left, right);
    }

    private SqlIdentifier parseIdentifier() {
        Token token = tokens.current();
        tokens.next();
        return factory.identifier(token.value());
    }

    private SqlLiteral parseLiteral() {
        Token token = tokens.current();
        tokens.next();
        return switch (token.type()) {
            case NUMBER -> factory.number(token.value());
            case STRING -> factory.string(token.value());
            default -> throw new SqlParseException("Expected literal, got " + token.type());
        };
    }
}