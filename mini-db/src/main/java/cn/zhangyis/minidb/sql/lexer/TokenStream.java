package cn.zhangyis.minidb.sql.lexer;

import java.util.ArrayDeque;
import java.util.Deque;

public class TokenStream {
    private final SqlLexer lexer;
    private final Deque<Token> buffer = new ArrayDeque<>(2);
    private Token current;

    public TokenStream(SqlLexer lexer) {
        this.lexer = lexer;
        advance();
    }

    private void advance() {
        if (!buffer.isEmpty()) {
            current = buffer.pollFirst();
            return;
        }
        current = lexer.nextToken();
    }

    public Token current() {
        return current;
    }

    public Token next() {
        Token next = current;
        advance();
        return next;
    }

    public Token peek() {
        if (buffer.isEmpty()) {
            buffer.add(lexer.nextToken());
        }
        return buffer.peekFirst();
    }

    public boolean match(TokenType type) {
        if (current.type() == type) {
            advance();
            return true;
        }
        return false;
    }

    public void expect(TokenType type) {
        if (!match(type)) {
            throw new SqlParseException("Expected " + type + ", found " + current);
        }
    }

    public boolean isEOF() {
        return current.type() == TokenType.EOF;
    }
}
