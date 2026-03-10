package cn.zhangyis.minidb.sql.lexer;

public record Token(TokenType type, String value, int startPos, int endPos) {
    public static final Token EOF_TOKEN = new Token(TokenType.EOF, "", -1, -1);

    public int length() {
        return endPos - startPos;
    }

    @Override
    public String toString() {
        return String.format("%s('%s') [%d-%d]", type, value, startPos, endPos);
    }
}