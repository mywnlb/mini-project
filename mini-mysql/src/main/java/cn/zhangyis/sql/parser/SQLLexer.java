package cn.zhangyis.sql.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SQLLexer {
    public enum TokenType {
        SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE, CREATE, TABLE, DROP, ALTER, ADD, COLUMN, JOIN, ON, GROUP, BY, ORDER, ASC, DESC, IDENTIFIER, COMMA, STAR, EQUALS, STRING, EOF,
        DOT,COUNT, SUM, AVG, MIN, MAX, INNER, LEFT, RIGHT, PRIMARY, KEY, DEFAULT, NOT, NULL, COMMENT, BIGINT, VARCHAR, INT, DOUBLE, FLOAT, DATETIME, DATE, LEFT_PAREN, RIGHT_PAREN, NUMBER, OR,
        AND, NOT_EQUALS, LESS_EQUALS, LESS, GREATER_EQUALS, GREATER, SEMICOLON, HAVING,
        PLUS, MINUS, DIVIDE, MODULO,
        IN, EXISTS
    }

    public static class Token {
        private TokenType type;
        private String value;

        public Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }

        public TokenType getType() {
            return type;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "Token{" +
                    "type=" + type +
                    ", value='" + value + '\'' +
                    '}';
        }
    }

    private String input;
    private int pos;
    private int length;
    private static final Map<String, TokenType> keywords;

    static {
        keywords = new HashMap<>();
        keywords.put("SELECT", TokenType.SELECT);
        keywords.put("FROM", TokenType.FROM);
        keywords.put("WHERE", TokenType.WHERE);
        keywords.put("INSERT", TokenType.INSERT);
        keywords.put("INTO", TokenType.INTO);
        keywords.put("VALUES", TokenType.VALUES);
        keywords.put("UPDATE", TokenType.UPDATE);
        keywords.put("SET", TokenType.SET);
        keywords.put("DELETE", TokenType.DELETE);
        keywords.put("CREATE", TokenType.CREATE);
        keywords.put("TABLE", TokenType.TABLE);
        keywords.put("DROP", TokenType.DROP);
        keywords.put("ALTER", TokenType.ALTER);
        keywords.put("ADD", TokenType.ADD);
        keywords.put("COLUMN", TokenType.COLUMN);
        keywords.put("JOIN", TokenType.JOIN);
        keywords.put("ON", TokenType.ON);
        keywords.put("GROUP", TokenType.GROUP);
        keywords.put("BY", TokenType.BY);
        keywords.put("ORDER", TokenType.ORDER);
        keywords.put("ASC", TokenType.ASC);
        keywords.put("DESC", TokenType.DESC);
        keywords.put("COUNT", TokenType.COUNT);
        keywords.put("SUM", TokenType.SUM);
        keywords.put("AVG", TokenType.AVG);
        keywords.put("MIN", TokenType.MIN);
        keywords.put("MAX", TokenType.MAX);
        keywords.put("INNER", TokenType.INNER);
        keywords.put("LEFT", TokenType.LEFT);
        keywords.put("RIGHT", TokenType.RIGHT);
        keywords.put("PRIMARY", TokenType.PRIMARY);
        keywords.put("KEY", TokenType.KEY);
        keywords.put("DEFAULT", TokenType.DEFAULT);
        keywords.put("NOT", TokenType.NOT);
        keywords.put("NULL", TokenType.NULL);
        keywords.put("COMMENT", TokenType.COMMENT);
        keywords.put("BIGINT", TokenType.BIGINT);
        keywords.put("VARCHAR", TokenType.VARCHAR);
        keywords.put("INT", TokenType.INT);
        keywords.put("DOUBLE", TokenType.DOUBLE);
        keywords.put("FLOAT", TokenType.FLOAT);
        keywords.put("DATETIME", TokenType.DATETIME);
        keywords.put("DATE", TokenType.DATE);
        keywords.put("(", TokenType.LEFT_PAREN);
        keywords.put(")", TokenType.RIGHT_PAREN);
        keywords.put(";", TokenType.SEMICOLON);
        keywords.put("OR", TokenType.OR);
        keywords.put(".", TokenType.DOT);
        keywords.put("AND", TokenType.AND);
        keywords.put("HAVING", TokenType.HAVING);
        keywords.put("+", TokenType.PLUS);
        keywords.put("-", TokenType.MINUS);
        keywords.put("/", TokenType.DIVIDE);
        keywords.put("%", TokenType.MODULO);
        keywords.put("IN", TokenType.IN);
        keywords.put("EXISTS", TokenType.EXISTS);
    }

    public SQLLexer(String input) {
        this.input = input;
        this.pos = 0;
        this.length = input.length();
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < length) {
            char current = input.charAt(pos);
            if (Character.isWhitespace(current)) {
                pos++;
            } else if (current == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                pos++;
            } else if (current == '*') {
                tokens.add(new Token(TokenType.STAR, "*"));
                pos++;
            } else if (current == '=') {
                tokens.add(new Token(TokenType.EQUALS, "="));
                pos++;
            } else if (current == '.') {
                tokens.add(new Token(TokenType.DOT, "."));
                pos++;
            } else if (current == '+') {
                tokens.add(new Token(TokenType.PLUS, "+"));
                pos++;
            } else if (current == '-') {
                tokens.add(new Token(TokenType.MINUS, "-"));
                pos++;
            } else if (current == '/') {
                tokens.add(new Token(TokenType.DIVIDE, "/"));
                pos++;
            } else if (current == '%') {
                tokens.add(new Token(TokenType.MODULO, "%"));
                pos++;
            } else if (current == '!') {
                if (peek() == '=') {
                    pos += 2;
                    tokens.add(new Token(TokenType.NOT_EQUALS, "!="));
                } else {
                    throw new RuntimeException("Unexpected character after '!': " + peek());
                }
            } else if (current == '<') {
                if (peek() == '=') {
                    pos += 2;
                    tokens.add(new Token(TokenType.LESS_EQUALS, "<="));
                } else {
                    pos++;
                    tokens.add(new Token(TokenType.LESS, "<"));
                }
            } else if (current == '>') {
                if (peek() == '=') {
                    pos += 2;
                    tokens.add(new Token(TokenType.GREATER_EQUALS, ">="));
                } else {
                    pos++;
                    tokens.add(new Token(TokenType.GREATER, ">"));
                }
            } else if (current == '\'') {
                tokens.add(new Token(TokenType.STRING, readString()));
            } else if (current == '(') {
                tokens.add(new Token(TokenType.LEFT_PAREN, "("));
                pos++;
            } else if (current == ')') {
                tokens.add(new Token(TokenType.RIGHT_PAREN, ")"));
                pos++;
            } else if (current == ';') {
                tokens.add(new Token(TokenType.SEMICOLON, ";"));
                pos++;
            } else if (current == '`') {
                tokens.add(new Token(TokenType.IDENTIFIER, readBacktickIdentifier()));
            } else if (Character.isDigit(current)) {
                tokens.add(new Token(TokenType.NUMBER, readNumber()));
            } else if (Character.isLetter(current)) {
                String identifier = readIdentifier();
                tokens.add(new Token(getKeywordType(identifier), identifier));
            } else {
                throw new RuntimeException("Unexpected character: " + current);
            }
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    /**
     * 查看下一个字符但不消费
     * @return 下一个字符，如果已到末尾则返回'\0'
     */
    private char peek() {
        if (pos + 1 >= length) {
            return '\0';
        }
        return input.charAt(pos + 1);
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // skip opening quote
        while (pos < length && input.charAt(pos) != '\'') {
            sb.append(input.charAt(pos));
            pos++;
        }
        pos++; // skip closing quote
        return sb.toString();
    }

    private String readBacktickIdentifier() {
        StringBuilder sb = new StringBuilder();
        pos++; // skip opening backtick
        while (pos < length && input.charAt(pos) != '`') {
            sb.append(input.charAt(pos));
            pos++;
        }
        pos++; // skip closing backtick
        return sb.toString();
    }

    private String readNumber() {
        StringBuilder sb = new StringBuilder();
        while (pos < length && Character.isDigit(input.charAt(pos))) {
            sb.append(input.charAt(pos));
            pos++;
        }
        return sb.toString();
    }

    private String readIdentifier() {
        StringBuilder sb = new StringBuilder();
        while (pos < length && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
            sb.append(input.charAt(pos));
            pos++;
        }
        return sb.toString();
    }

    private TokenType getKeywordType(String identifier) {
        return keywords.getOrDefault(identifier.toUpperCase(), TokenType.IDENTIFIER);
    }
}