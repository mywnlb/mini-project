package cn.zhangyis.sql;

import java.util.List;

public class MainParser {
    private List<SQLLexer.Token> tokens;
    private int pos;
    private SQLLexer lexer;

    public MainParser(String input) {
        lexer = new SQLLexer(input);
        this.pos = 0;
    }

    public SQLStatement parse() {
        this.tokens = lexer.tokenize();

        SQLLexer.Token token = tokens.get(pos);
        switch (token.getType()) {
            case SELECT:
                return new SelectParser(tokens).parse();
            case INSERT:
                return new InsertParser(tokens).parse();
            case DELETE:
                return new DeleteParser(tokens).parse();
            case CREATE:
                return new CreateParser(tokens).parse();
            default:
                throw new RuntimeException("Unexpected token: " + token);
        }
    }
}