package cn.zhangyis.sql.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SQLLexer {
    public enum TokenType {
        // SQL关键字
        SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE, CREATE, TABLE, DROP, ALTER, ADD, COLUMN, JOIN, ON, GROUP, BY, ORDER, ASC, DESC, 
        // 标识符和基本标记
        IDENTIFIER, COMMA, STAR, EQUALS, STRING, EOF, DOT, LIMIT,
        // 聚合函数
        COUNT, SUM, AVG, MIN, MAX, 
        // JOIN类型
        INNER, LEFT, RIGHT, 
        // DDL关键字
        PRIMARY, KEY, DEFAULT, NOT, NULL, COMMENT, 
        // 数据类型
        BIGINT, VARCHAR, INT, DOUBLE, FLOAT, DATETIME, DATE, TIMESTAMP, BOOLEAN, DECIMAL, CHAR, TEXT,
        // 分隔符
        LEFT_PAREN, RIGHT_PAREN, SEMICOLON, 
        // 操作符
        NUMBER, OR, AND, NOT_EQUALS, LESS_EQUALS, LESS, GREATER_EQUALS, GREATER, PLUS, MINUS, DIVIDE, MODULO, 
        // 其他SQL关键字
        AS, DISTINCT, IN, EXISTS, HAVING,
        
        // 字符串函数
        CONCAT, SUBSTR, SUBSTRING, UPPER, LOWER, TRIM, LTRIM, RTRIM, LENGTH,
        // 数值函数
        ROUND, CEIL, CEILING, FLOOR, ABS, RAND, POW, POWER, SQRT, MOD,
        // 日期函数
        NOW, CURDATE, CURRENT_DATE, CURTIME, DATE_FORMAT, DATEDIFF, DATE_ADD, DATE_SUB, EXTRACT, YEAR, MONTH, DAY,
        // 条件函数
        IFNULL, COALESCE, CASE, WHEN, THEN, ELSE, END, IF,
        // 转换函数
        CAST, CONVERT,
        // 其他函数
        GROUP_CONCAT, JSON_EXTRACT
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
    private static final Set<String> reservedKeywords;

    static {
        keywords = new HashMap<>();
        
        // SQL基本关键字
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
        keywords.put("LIMIT", TokenType.LIMIT);

        // 聚合函数
        keywords.put("COUNT", TokenType.COUNT);
        keywords.put("SUM", TokenType.SUM);
        keywords.put("AVG", TokenType.AVG);
        keywords.put("MIN", TokenType.MIN);
        keywords.put("MAX", TokenType.MAX);
        
        // JOIN类型
        keywords.put("INNER", TokenType.INNER);
        keywords.put("LEFT", TokenType.LEFT);
        keywords.put("RIGHT", TokenType.RIGHT);
        
        // DDL关键字
        keywords.put("PRIMARY", TokenType.PRIMARY);
        keywords.put("KEY", TokenType.KEY);
        keywords.put("DEFAULT", TokenType.DEFAULT);
        keywords.put("NOT", TokenType.NOT);
        keywords.put("NULL", TokenType.NULL);
        keywords.put("COMMENT", TokenType.COMMENT);
        
        // 数据类型
        keywords.put("BIGINT", TokenType.BIGINT);
        keywords.put("VARCHAR", TokenType.VARCHAR);
        keywords.put("INT", TokenType.INT);
        keywords.put("DOUBLE", TokenType.DOUBLE);
        keywords.put("FLOAT", TokenType.FLOAT);
        keywords.put("DATETIME", TokenType.DATETIME);
        keywords.put("DATE", TokenType.DATE);
        keywords.put("TIMESTAMP", TokenType.TIMESTAMP);
        keywords.put("BOOLEAN", TokenType.BOOLEAN);
        keywords.put("DECIMAL", TokenType.DECIMAL);
        keywords.put("CHAR", TokenType.CHAR);
        keywords.put("TEXT", TokenType.TEXT);
        
        // 分隔符
        keywords.put("(", TokenType.LEFT_PAREN);
        keywords.put(")", TokenType.RIGHT_PAREN);
        keywords.put(";", TokenType.SEMICOLON);
        keywords.put(".", TokenType.DOT);
        
        // 逻辑操作符
        keywords.put("OR", TokenType.OR);
        keywords.put("AND", TokenType.AND);
        
        // 算术操作符
        keywords.put("+", TokenType.PLUS);
        keywords.put("-", TokenType.MINUS);
        keywords.put("/", TokenType.DIVIDE);
        keywords.put("%", TokenType.MODULO);
        
        // 其他SQL关键字
        keywords.put("HAVING", TokenType.HAVING);
        keywords.put("IN", TokenType.IN);
        keywords.put("AS", TokenType.AS);
        keywords.put("DISTINCT", TokenType.DISTINCT);
        keywords.put("EXISTS", TokenType.EXISTS);
        
        // 字符串函数
        keywords.put("CONCAT", TokenType.CONCAT);
        keywords.put("SUBSTR", TokenType.SUBSTR);
        keywords.put("SUBSTRING", TokenType.SUBSTRING);
        keywords.put("UPPER", TokenType.UPPER);
        keywords.put("LOWER", TokenType.LOWER);
        keywords.put("TRIM", TokenType.TRIM);
        keywords.put("LTRIM", TokenType.LTRIM);
        keywords.put("RTRIM", TokenType.RTRIM);
        keywords.put("LENGTH", TokenType.LENGTH);
        
        // 数值函数
        keywords.put("ROUND", TokenType.ROUND);
        keywords.put("CEIL", TokenType.CEIL);
        keywords.put("CEILING", TokenType.CEILING);
        keywords.put("FLOOR", TokenType.FLOOR);
        keywords.put("ABS", TokenType.ABS);
        keywords.put("RAND", TokenType.RAND);
        keywords.put("POW", TokenType.POW);
        keywords.put("POWER", TokenType.POWER);
        keywords.put("SQRT", TokenType.SQRT);
        keywords.put("MOD", TokenType.MOD);
        
        // 日期函数
        keywords.put("NOW", TokenType.NOW);
        keywords.put("CURDATE", TokenType.CURDATE);
        keywords.put("CURRENT_DATE", TokenType.CURRENT_DATE);
        keywords.put("CURTIME", TokenType.CURTIME);
        keywords.put("DATE_FORMAT", TokenType.DATE_FORMAT);
        keywords.put("DATEDIFF", TokenType.DATEDIFF);
        keywords.put("DATE_ADD", TokenType.DATE_ADD);
        keywords.put("DATE_SUB", TokenType.DATE_SUB);
        keywords.put("EXTRACT", TokenType.EXTRACT);
        keywords.put("YEAR", TokenType.YEAR);
        keywords.put("MONTH", TokenType.MONTH);
        keywords.put("DAY", TokenType.DAY);
        
        // 条件函数
        keywords.put("IFNULL", TokenType.IFNULL);
        keywords.put("COALESCE", TokenType.COALESCE);
        keywords.put("CASE", TokenType.CASE);
        keywords.put("WHEN", TokenType.WHEN);
        keywords.put("THEN", TokenType.THEN);
        keywords.put("ELSE", TokenType.ELSE);
        keywords.put("END", TokenType.END);
        keywords.put("IF", TokenType.IF);
        
        // 转换函数
        keywords.put("CAST", TokenType.CAST);
        keywords.put("CONVERT", TokenType.CONVERT);
        
        // 其他函数
        keywords.put("GROUP_CONCAT", TokenType.GROUP_CONCAT);
        keywords.put("JSON_EXTRACT", TokenType.JSON_EXTRACT);
        
        // 初始化保留关键字集合
        reservedKeywords = new HashSet<>();
        
        // SQL基本语法关键字（不能用作标识符）
        reservedKeywords.add("SELECT");
        reservedKeywords.add("FROM");
        reservedKeywords.add("WHERE");
        reservedKeywords.add("JOIN");
        reservedKeywords.add("ON");
        reservedKeywords.add("GROUP");
        reservedKeywords.add("BY");
        reservedKeywords.add("ORDER");
        reservedKeywords.add("HAVING");
        reservedKeywords.add("LIMIT");
        reservedKeywords.add("UNION");
        reservedKeywords.add("INTERSECT");
        reservedKeywords.add("EXCEPT");
        
        // 逻辑操作符
        reservedKeywords.add("AND");
        reservedKeywords.add("OR");
        reservedKeywords.add("NOT");
        
        // 其他SQL关键字
        reservedKeywords.add("IN");
        reservedKeywords.add("EXISTS");
        reservedKeywords.add("BETWEEN");
        reservedKeywords.add("LIKE");
        reservedKeywords.add("IS");
        reservedKeywords.add("NULL");
        reservedKeywords.add("TRUE");
        reservedKeywords.add("FALSE");
        
        // 条件关键字
        reservedKeywords.add("CASE");
        reservedKeywords.add("WHEN");
        reservedKeywords.add("THEN");
        reservedKeywords.add("ELSE");
        reservedKeywords.add("END");
        
        // DML关键字
        reservedKeywords.add("INSERT");
        reservedKeywords.add("UPDATE");
        reservedKeywords.add("DELETE");
        reservedKeywords.add("SET");
        reservedKeywords.add("VALUES");
        
        // DDL关键字
        reservedKeywords.add("CREATE");
        reservedKeywords.add("ALTER");
        reservedKeywords.add("DROP");
        reservedKeywords.add("TABLE");
        reservedKeywords.add("INDEX");
        reservedKeywords.add("VIEW");
        reservedKeywords.add("TRIGGER");
    }

    public SQLLexer(String input) {
        this.input = input;
        this.pos = 0;
        this.length = input.length();
    }
    
    /**
     * 检查一个单词是否为SQL保留关键字
     * 保留关键字是不能用作标识符的关键字
     * 
     * @param word 需要检查的单词
     * @return 如果是保留关键字返回true，否则返回false
     */
    public static boolean isReservedKeyword(String word) {
        if (word == null) {
            return false;
        }
        return reservedKeywords.contains(word.toUpperCase());
    }
    
    /**
     * 获取所有SQL保留关键字的集合
     * 
     * @return 保留关键字集合
     */
    public static Set<String> getReservedKeywords() {
        return new HashSet<>(reservedKeywords);
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