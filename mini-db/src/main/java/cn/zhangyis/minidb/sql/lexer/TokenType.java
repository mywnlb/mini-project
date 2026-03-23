package cn.zhangyis.minidb.sql.lexer;

public enum TokenType {
    SELECT, FROM, WHERE, JOIN, ON, GROUP, BY, ORDER, LIMIT, OFFSET, DISTINCT, ASC, DESC,
    CAST, CASE, WHEN, THEN, ELSE, END, UNION, EXCEPT, INTERSECT, ALL,
    LEFT, RIGHT, FULL, OUTER, CROSS, INNER, NATURAL, USING, WITH,

    // Keywords - Logic
    AND, OR, HAVING, NOT,

    // Keywords - DML
    INSERT, INTO, VALUES, UPDATE, SET, DELETE,

    // Keywords - Transaction
    BEGIN, COMMIT, ROLLBACK, TRANSACTION,

    // Keywords - DDL
    CREATE, DROP, TABLE, PRIMARY, KEY, IF, EXISTS, ALTER, ADD, COLUMN, INDEX, DEFAULT,

    // Keywords - Predicate
    LIKE, BETWEEN, IN, IS, NULL,

    // Keywords - Aggregate
    COUNT, SUM, AVG, MAX, MIN, ANALYZE,

    // Keywords - Utility
    EXPLAIN,

    // Keywords - Window
    OVER, PARTITION, ROW_NUMBER, RANK, DENSE_RANK,
    LAG, LEAD, NTILE, PERCENT_RANK, CUME_DIST,

    // Identifiers & Literals
    IDENTIFIER, NUMBER, STRING, STAR,

    // Operators
    EQ, LT, GT, LE, GE, NE, PLUS, MINUS, MUL, DIV,

    // Punctuation
    LPAREN, RPAREN, COMMA, SEMICOLON, DOT,

    // Alias
    AS,

    // Parameter placeholder
    PARAMETER,

    // Special
    EOF;

    public String getLiteral() {
        return switch (this) {
            case STAR -> "*";
            case PLUS -> "+";
            case MINUS -> "-";
            case DIV -> "/";
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
