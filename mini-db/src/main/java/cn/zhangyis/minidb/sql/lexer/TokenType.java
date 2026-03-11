package cn.zhangyis.minidb.sql.lexer;

public enum TokenType {
    // Keywords - Query
    SELECT, FROM, WHERE, JOIN, ON, GROUP, BY, ORDER, LIMIT,

    // Keywords - Logic
    AND, OR, HAVING,

    // Keywords - DML
    INSERT, INTO, VALUES, UPDATE, SET, DELETE,

    // Keywords - Aggregate
    COUNT, SUM, AVG, MAX, MIN,

    // Identifiers & Literals
    IDENTIFIER, NUMBER, STRING, STAR,

    // Operators
    EQ, LT, GT, LE, GE, NE, PLUS, MINUS, MUL, DIV,

    // Punctuation
    LPAREN, RPAREN, COMMA, SEMICOLON, DOT,

    // Special
    EOF;

    public String getLiteral() {
        return switch (this) {
            case STAR -> "*";
            case EQ -> "=";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case NE -> "<>";
            case LPAREN -> "(";
            case RPAREN -> ")";
            case COMMA -> ",";
            case SEMICOLON -> ";";
            case DOT -> ".";
            default -> name();
        };
    }
}
