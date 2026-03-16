package cn.zhangyis.minidb.sql.lexer;

import java.util.HashMap;
import java.util.Map;

public class SqlLexer {
    private final String sql;
    private int pos = 0;
    private final Map<String, TokenType> keywords = new HashMap<>();

    public SqlLexer(String sql) {
        this.sql = sql;
        initKeywords();
    }

    private void initKeywords() {
        keywords.put("SELECT", TokenType.SELECT);
        keywords.put("FROM", TokenType.FROM);
        keywords.put("WHERE", TokenType.WHERE);
        keywords.put("JOIN", TokenType.JOIN);
        keywords.put("ON", TokenType.ON);
        keywords.put("AND", TokenType.AND);
        keywords.put("OR", TokenType.OR);
        keywords.put("GROUP", TokenType.GROUP);
        keywords.put("BY", TokenType.BY);
        keywords.put("HAVING", TokenType.HAVING);
        keywords.put("ORDER", TokenType.ORDER);
        keywords.put("LIMIT", TokenType.LIMIT);
        keywords.put("OFFSET", TokenType.OFFSET);
        keywords.put("COUNT", TokenType.COUNT);
        keywords.put("SUM", TokenType.SUM);
        keywords.put("AVG", TokenType.AVG);
        keywords.put("MAX", TokenType.MAX);
        keywords.put("MIN", TokenType.MIN);
        keywords.put("INSERT", TokenType.INSERT);
        keywords.put("INTO", TokenType.INTO);
        keywords.put("VALUES", TokenType.VALUES);
        keywords.put("UPDATE", TokenType.UPDATE);
        keywords.put("SET", TokenType.SET);
        keywords.put("DELETE", TokenType.DELETE);
        keywords.put("CREATE", TokenType.CREATE);
        keywords.put("DROP", TokenType.DROP);
        keywords.put("TABLE", TokenType.TABLE);
        keywords.put("PRIMARY", TokenType.PRIMARY);
        keywords.put("KEY", TokenType.KEY);
        keywords.put("IF", TokenType.IF);
        keywords.put("EXISTS", TokenType.EXISTS);
        keywords.put("NOT", TokenType.NOT);
        keywords.put("ALTER", TokenType.ALTER);
        keywords.put("ADD", TokenType.ADD);
        keywords.put("COLUMN", TokenType.COLUMN);
        keywords.put("INDEX", TokenType.INDEX);
        keywords.put("DISTINCT", TokenType.DISTINCT);
        keywords.put("ASC", TokenType.ASC);
        keywords.put("DESC", TokenType.DESC);
        keywords.put("LIKE", TokenType.LIKE);
        keywords.put("BETWEEN", TokenType.BETWEEN);
        keywords.put("IN", TokenType.IN);
        keywords.put("IS", TokenType.IS);
        keywords.put("NULL", TokenType.NULL);
        keywords.put("AS", TokenType.AS);
        keywords.put("BEGIN", TokenType.BEGIN);
        keywords.put("COMMIT", TokenType.COMMIT);
        keywords.put("ROLLBACK", TokenType.ROLLBACK);
        keywords.put("TRANSACTION", TokenType.TRANSACTION);
    }

    public Token nextToken() {
        skipWhitespace();
        if (pos >= sql.length()) return Token.EOF_TOKEN;

        int start = pos;
        char c = sql.charAt(pos);

        // 单引号字符串
        if (c == '\'') {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < sql.length() && sql.charAt(pos) != '\'') {
                sb.append(sql.charAt(pos));
                pos++;
            }
            if (pos < sql.length()) pos++; // skip closing quote
            return new Token(TokenType.STRING, sb.toString(), start, pos);
        }

        // 双字符运算符
        if (pos + 1 < sql.length()) {
            String two = sql.substring(pos, pos + 2);
            if (two.equals("<=")) { pos += 2; return new Token(TokenType.LE, "<=", start, pos); }
            if (two.equals(">=")) { pos += 2; return new Token(TokenType.GE, ">=", start, pos); }
            if (two.equals("<>")) { pos += 2; return new Token(TokenType.NE, "<>", start, pos); }
        }

        // 单字符运算符和标点
        switch (c) {
            case '*': pos++; return new Token(TokenType.STAR, "*", start, pos);
            case '+': pos++; return new Token(TokenType.PLUS, "+", start, pos);
            case '-': pos++; return new Token(TokenType.MINUS, "-", start, pos);
            case '/': pos++; return new Token(TokenType.DIV, "/", start, pos);
            case '=': pos++; return new Token(TokenType.EQ, "=", start, pos);
            case '<': pos++; return new Token(TokenType.LT, "<", start, pos);
            case '>': pos++; return new Token(TokenType.GT, ">", start, pos);
            case '(': pos++; return new Token(TokenType.LPAREN, "(", start, pos);
            case ')': pos++; return new Token(TokenType.RPAREN, ")", start, pos);
            case ',': pos++; return new Token(TokenType.COMMA, ",", start, pos);
            case ';': pos++; return new Token(TokenType.SEMICOLON, ";", start, pos);
            case '.': pos++; return new Token(TokenType.DOT, ".", start, pos);
        }

        // 数字
        if (Character.isDigit(c)) {
            while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) pos++;
            if (pos < sql.length() && sql.charAt(pos) == '.') {
                pos++;
                while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) pos++;
            }
            return new Token(TokenType.NUMBER, sql.substring(start, pos), start, pos);
        }

        // 标识符或关键字
        if (Character.isLetter(c) || c == '_') {
            while (pos < sql.length() && (Character.isLetterOrDigit(sql.charAt(pos)) || sql.charAt(pos) == '_')) pos++;
            String word = sql.substring(start, pos);
            TokenType type = keywords.getOrDefault(word.toUpperCase(), TokenType.IDENTIFIER);
            String value = word.toUpperCase();
            return new Token(type, value, start, pos);
        }

        throw new SqlParseException("Unexpected character '" + c + "' at position " + pos);
    }

    private void skipWhitespace() {
        while (pos < sql.length() && Character.isWhitespace(sql.charAt(pos))) pos++;
    }
}
