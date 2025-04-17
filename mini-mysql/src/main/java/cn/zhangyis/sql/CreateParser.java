package cn.zhangyis.sql;

import java.util.ArrayList;
import java.util.List;

/**
 CREATE TABLE `tb_test` (\n" +
 "  `id` bigint(20) NOT NULL COMMENT '1',\n" +
 "  `name` varchar(255) DEFAULT NULL COMMENT '2',\n" +
 "  `age` int(11) DEFAULT NULL COMMENT '4',\n" +
 "  `money` double(20,5) DEFAULT NULL COMMENT '5',\n" +
 "  PRIMARY KEY (`id`),\n" +
 "  KEY `name_index` (`name`,`age`),\n" +
 "  KEY `age_index` (`age`)\n" +
 ")  COMMENT='asdasdsa';
 */
public class CreateParser extends SQLParser {

    public CreateParser(List<SQLLexer.Token> tokens) {
        super(tokens);
    }

    @Override
    public SQLStatement parse() {
        match(SQLLexer.TokenType.CREATE);
        match(SQLLexer.TokenType.TABLE);
        String tableName = match(SQLLexer.TokenType.IDENTIFIER).getValue();
        match(SQLLexer.TokenType.LEFT_PAREN);
        List<ColumnDefinition> columns = parseColumns();
        List<IndexDefinition> indexes = parseIndexes();
        match(SQLLexer.TokenType.RIGHT_PAREN);
        String tableComment = parseTableComment();

        // Parse semicolon ;
        match(SQLLexer.TokenType.SEMICOLON);
        return new CreateStatement(tableName, columns, indexes, tableComment);
    }

    private List<ColumnDefinition> parseColumns() {
        List<ColumnDefinition> columns = new ArrayList<>();
        while (true) {
            if (tokens.get(pos).getType() == SQLLexer.TokenType.PRIMARY || tokens.get(pos).getType() == SQLLexer.TokenType.KEY) {
                break;
            }

            String name = match(SQLLexer.TokenType.IDENTIFIER).getValue();
            SQLLexer.TokenType typeToken = tokens.get(pos).getType();
            String type = match(typeToken).getValue();
            int length = 0;
            int precision = 0;

            if (typeToken == SQLLexer.TokenType.VARCHAR || typeToken == SQLLexer.TokenType.BIGINT || typeToken == SQLLexer.TokenType.INT || typeToken == SQLLexer.TokenType.DOUBLE || typeToken == SQLLexer.TokenType.FLOAT) {
                if (tokens.get(pos).getType() == SQLLexer.TokenType.LEFT_PAREN) {
                    match(SQLLexer.TokenType.LEFT_PAREN);
                    length = Integer.parseInt(match(SQLLexer.TokenType.NUMBER).getValue());
                    if (tokens.get(pos).getType() == SQLLexer.TokenType.COMMA) {
                        match(SQLLexer.TokenType.COMMA);
                        precision = Integer.parseInt(match(SQLLexer.TokenType.NUMBER).getValue());
                    }
                    match(SQLLexer.TokenType.RIGHT_PAREN);
                }
            }

            boolean notNull = false;
            String defaultValue = null;
            String comment = null;

            if (tokens.get(pos).getType() == SQLLexer.TokenType.NOT) {
                match(SQLLexer.TokenType.NOT);
                match(SQLLexer.TokenType.NULL);
                notNull = true;
            }

            if (tokens.get(pos).getType() == SQLLexer.TokenType.DEFAULT) {
                match(SQLLexer.TokenType.DEFAULT);
                if (tokens.get(pos).getType() == SQLLexer.TokenType.NULL) {
                    match(SQLLexer.TokenType.NULL);
                    defaultValue = null;
                } else if (typeToken == SQLLexer.TokenType.VARCHAR || typeToken == SQLLexer.TokenType.DATETIME || typeToken == SQLLexer.TokenType.DATE) {
                    defaultValue = match(SQLLexer.TokenType.STRING).getValue();
                } else {
                    defaultValue = match(SQLLexer.TokenType.NUMBER).getValue();
                }
            }

            if (tokens.get(pos).getType() == SQLLexer.TokenType.COMMENT) {
                match(SQLLexer.TokenType.COMMENT);
                comment = match(SQLLexer.TokenType.STRING).getValue();
            }

            columns.add(new ColumnDefinition(name, type, length, precision, notNull, defaultValue, comment));

            if (tokens.get(pos).getType() == SQLLexer.TokenType.COMMA) {
                pos++;
            } else {
                break;
            }
        }
        return columns;
    }

    private List<IndexDefinition> parseIndexes() {
        List<IndexDefinition> indexes = new ArrayList<>();
        while (pos < tokens.size() && tokens.get(pos).getType() != SQLLexer.TokenType.RIGHT_PAREN) {
            if (tokens.get(pos).getType() == SQLLexer.TokenType.PRIMARY) {
                match(SQLLexer.TokenType.PRIMARY);
                match(SQLLexer.TokenType.KEY);
                match(SQLLexer.TokenType.LEFT_PAREN);
                List<String> columns = parseIndexColumns();
                match(SQLLexer.TokenType.RIGHT_PAREN);
                indexes.add(new IndexDefinition("PRIMARY", columns, true));
            } else if (tokens.get(pos).getType() == SQLLexer.TokenType.KEY) {
                match(SQLLexer.TokenType.KEY);
                String name = match(SQLLexer.TokenType.IDENTIFIER).getValue();
                match(SQLLexer.TokenType.LEFT_PAREN);
                List<String> columns = parseIndexColumns();
                match(SQLLexer.TokenType.RIGHT_PAREN);
                indexes.add(new IndexDefinition(name, columns, false));
            }
            if (tokens.get(pos).getType() == SQLLexer.TokenType.COMMA) {
                pos++;
            } else {
                break;
            }
        }
        return indexes;
    }

    private List<String> parseIndexColumns() {
        List<String> columns = new ArrayList<>();
        while (true) {
            columns.add(match(SQLLexer.TokenType.IDENTIFIER).getValue());
            if (tokens.get(pos).getType() == SQLLexer.TokenType.COMMA) {
                pos++;
            } else {
                break;
            }
        }
        return columns;
    }

    private String parseTableComment() {
        if (pos < tokens.size() && tokens.get(pos).getType() == SQLLexer.TokenType.COMMENT) {
            match(SQLLexer.TokenType.COMMENT);
            match(SQLLexer.TokenType.EQUALS);
            return match(SQLLexer.TokenType.STRING).getValue();
        }
        return null;
    }
}