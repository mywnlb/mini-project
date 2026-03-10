package cn.zhangyis.minidb.sql.lexer;

import java.util.HashMap;
import java.util.Map;

public class SqlLexer {
    private final String sql;
    private int pos = 0;
    private final Map<String, TokenType> keywords = new HashMap<>();

    public SqlLexer(String sql) {
        this.sql = sql.toUpperCase();
        initKeywords();
    }

    private void initKeywords() {
        keywords.put("SELECT", TokenType.SELECT);
        keywords.put("FROM", TokenType.FROM);
        keywords.put("WHERE", TokenType.WHERE);
        keywords.put("JOIN", TokenType.JOIN);
        keywords.put("ON", TokenType.ON);
        keywords.put("STAR", TokenType.STAR);
    }

    public Token nextToken() {
        skipWhitespace();
        if (pos >= sql.length()) return Token.EOF_TOKEN;

        int start = pos;
        char ch = sql.charAt(pos);

        return switch (ch) {
            case '*' -> emit(TokenType.STAR, 1);
            case '=' -> emit(TokenType.EQ, 1);
            case '(' -> emit(TokenType.LPAREN, 1);
            case ')' -> emit(TokenType.RPAREN, 1);
            case ',' -> emit(TokenType.COMMA, 1);
            case ';' -> emit(TokenType.SEMICOLON, 1);
            case '.' -> emit(TokenType.DOT, 1);
            case '"' -> stringToken(start);
            default -> {
                if (Character.isDigit(ch)) {
                    yield numberToken(start);
                } else if (Character.isLetter(ch)) {
                    yield identifierToken(start);
                } else {
                    yield unknownToken(start);
                }
            }
        };
    }

    private Token emit(TokenType type, int length) {
        Token token = new Token(type, type.getLiteral(), pos, pos + length);
        pos += length;
        return token;
    }

    private Token identifierToken(int start) {
        while (pos < sql.length() && Character.isLetterOrDigit(sql.charAt(pos))) {
            pos++;
        }
        String word = sql.substring(start, pos);
        TokenType type = keywords.getOrDefault(word, TokenType.IDENTIFIER);
        return new Token(type, word, start, pos);
    }

    private Token numberToken(int start) {
        while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
            pos++;
        }
        String num = sql.substring(start, pos);
        return new Token(TokenType.NUMBER, num, start, pos);
    }

    private Token stringToken(int start) {
        pos++; // skip "
        int end = pos;
        while (end < sql.length() && sql.charAt(end) != '"') end++;
        String value = sql.substring(pos, end);
        pos = end + 1;
        return new Token(TokenType.STRING, value, start, pos);
    }

    private Token unknownToken(int start) {
        pos++;
        return new Token(TokenType.IDENTIFIER, sql.substring(start, pos), start, pos);
    }

    private void skipWhitespace() {
        while (pos < sql.length() && Character.isWhitespace(sql.charAt(pos))) {
            pos++;
        }
    }
}