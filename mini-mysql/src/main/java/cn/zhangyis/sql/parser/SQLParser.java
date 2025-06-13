package cn.zhangyis.sql.parser;

import java.util.List;

/**
 * SQL解析器基类
 */
public abstract class SQLParser {
    protected final List<SQLLexer.Token> tokens;
    protected int pos;

    public SQLParser(List<SQLLexer.Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /**
     * 解析SQL语句
     * @return 解析后的SQL语句对象
     */
    public abstract SQLStatement parse();

    /**
     * 查看当前token但不消费
     * @return 当前token，如果已到末尾则返回null
     */
    public SQLLexer.Token peek() {
        if (pos >= tokens.size()) {
            return null;
        }
        return tokens.get(pos);
    }

    /**
     * 匹配并消费指定类型的token
     * @param type 期望的token类型
     * @return 匹配的token
     * @throws RuntimeException 如果token类型不匹配
     */
    public SQLLexer.Token match(SQLLexer.TokenType type) {
        SQLLexer.Token token = peek();
        if (token == null) {
            throw new RuntimeException("Unexpected end of input, expected: " + type);
        }
        if (token.getType() != type) {
            throw new RuntimeException("Expected token type: " + type + ", but got: " + token.getType());
        }
        pos++;
        return token;
    }

    protected SQLLexer.Token consume() {
        if (pos >= tokens.size()) {
            return null; // 或抛出异常
        }
        SQLLexer.Token currentToken = tokens.get(pos);
        pos++;
        return currentToken;
    }

    public List<SQLLexer.Token> getTokens() {
        return tokens;
    }

    public int getPos() {
        return pos;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }

    // 辅助方法：如果当前令牌匹配给定类型，则消费并返回true
    protected boolean consumeIfMatch(SQLLexer.TokenType type) {
        if (peek() != null && peek().getType() == type) {
            consume();
            return true;
        }
        return false;
    }

    // 辅助方法：查看前方的令牌
    protected SQLLexer.Token peekNext() {
        return peekNext(1);
    }

    protected SQLLexer.Token peekNext(int n) {
        return pos + n < tokens.size() ? tokens.get(pos + n) : null;
    }
}