package cn.zhangyis.minidb.sql.lexer;

public enum TokenType {
    // Keywords
    SELECT, FROM, WHERE, JOIN, ON, GROUP, BY, ORDER, LIMIT,

    // Identifiers & Literals
    IDENTIFIER, NUMBER, STRING, STAR,

    // Operators
    EQ, LT, GT, LE, GE, PLUS, MINUS, MUL, DIV,

    // Punctuation
    LPAREN, RPAREN, COMMA, SEMICOLON, DOT,

    // Special
    EOF;

    public String getLiteral() {
        return switch (this) {
            case STAR -> "*";
            case EQ -> "=";
            case LPAREN -> "(";
            case RPAREN -> ")";
            case COMMA -> ",";
            case SEMICOLON -> ";";
            case DOT -> ".";
            default -> name();
        };
    }
}