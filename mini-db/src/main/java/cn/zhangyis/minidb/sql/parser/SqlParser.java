package cn.zhangyis.minidb.sql.parser;

import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.types.SqlType;

public class SqlParser {
    private final TokenStream tokens;
    private final SqlNodeFactory factory;

    public SqlParser(TokenStream tokens) {
        this.tokens = tokens;
        this.factory = SqlNodeFactory.DEFAULT;
    }

    public SqlNode parseStatement() {
        TokenType type = tokens.current().type();
        SqlNode result = switch (type) {
            case SELECT -> {
                SqlNode s = parseSelect();
                if (tokens.current().type() == TokenType.UNION) {
                    tokens.next();
                    boolean all = tokens.match(TokenType.ALL);
                    SqlNode right = parseSelect();
                    yield factory.setOperation(s, right, all);
                }
                yield s;
            }
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            case CREATE -> parseCreate();
            case DROP -> parseDrop();
            case ALTER -> parseAlterTable();
            case BEGIN -> parseBegin();
            case COMMIT -> parseCommit();
            case ROLLBACK -> parseRollback();
            default -> throw new SqlParseException("Unsupported statement: " + type);
        };
        // 允许可选的分号结尾
        tokens.match(TokenType.SEMICOLON);
        if (!tokens.isEOF()) {
            throw new SqlParseException("Extra tokens after statement, got " + tokens.current());
        }
        return result;
    }

    // ==================== SELECT ====================

    private SqlSelect parseSelect() {
        tokens.expect(TokenType.SELECT);
        boolean distinct = tokens.match(TokenType.DISTINCT);

        SqlNodeList projection = parseProjection();

        SqlNode from = null;
        if (tokens.current().type() == TokenType.FROM) {
            tokens.next();
            from = parseFrom();
        }

        SqlNode where = null;
        if (tokens.match(TokenType.WHERE)) {
            where = parseExpression();
        }

        SqlNodeList groupBy = null;
        if (tokens.current().type() == TokenType.GROUP) {
            tokens.next();
            tokens.expect(TokenType.BY);
            groupBy = parseGroupByList();
        }

        SqlNode having = null;
        if (tokens.match(TokenType.HAVING)) {
            having = parseExpression();
        }

        SqlNodeList orderBy = null;
        if (tokens.current().type() == TokenType.ORDER) {
            tokens.next();
            tokens.expect(TokenType.BY);
            orderBy = parseOrderByList();
        }

        SqlNode limit = null;
        if (tokens.match(TokenType.LIMIT)) {
            limit = parsePrimary();
        }

        SqlNode offset = null;
        if (tokens.match(TokenType.OFFSET)) {
            offset = parsePrimary();
        }

        return factory.select(projection, from, where, distinct, groupBy, having, orderBy, limit, offset);
    }

    private SqlNode parseFrom() {
        SqlNode left = parseFromItem();
        while (tokens.current().type() == TokenType.JOIN) {
            tokens.next();
            SqlNode right = parseFromItem();
            tokens.expect(TokenType.ON);
            SqlNode condition = parseExpression();
            left = factory.join(left, right, condition);
        }
        return left;
    }

    /**
     * 解析 FROM 项：普通表引用 或 派生表 (SELECT ...) AS alias
     */
    private SqlNode parseFromItem() {
        if (tokens.current().type() == TokenType.LPAREN) {
            // 可能是派生表: (SELECT ...) AS alias
            tokens.next();
            if (tokens.current().type() == TokenType.SELECT) {
                SqlSelect subSelect = parseSelect();
                tokens.expect(TokenType.RPAREN);
                String alias = parseOptionalAlias();
                if (alias == null) {
                    throw new SqlParseException("Derived table must have an alias");
                }
                return new SqlDerivedTable(subSelect, alias);
            }
            throw new SqlParseException("Expected SELECT after '(' in FROM clause, got " + tokens.current());
        }
        return parseTableRef();
    }

    private SqlNodeList parseProjection() {
        SqlNodeList projection = factory.nodeList();
        if (tokens.match(TokenType.STAR)) {
            projection.add(factory.star());
        } else {
            do {
                projection.add(parseSelectItem());
            } while (tokens.match(TokenType.COMMA));
        }
        return projection;
    }

    private SqlNode parseSelectItem() {
        SqlNode item = parseExpression();
        String alias = parseOptionalAlias();
        return alias != null ? factory.alias(item, alias) : item;
    }

    private SqlNodeList parseGroupByList() {
        SqlNodeList groupBy = factory.nodeList();
        do {
            groupBy.add(parseExpression());
        } while (tokens.match(TokenType.COMMA));
        return groupBy;
    }

    private SqlNodeList parseOrderByList() {
        SqlNodeList orderBy = factory.nodeList();
        do {
            SqlNode column = parseExpression();
            boolean ascending = true;
            if (tokens.match(TokenType.DESC)) {
                ascending = false;
            } else {
                tokens.match(TokenType.ASC); // 可选，默认 ASC
            }
            orderBy.add(new SqlOrderByItem(column, ascending));
        } while (tokens.match(TokenType.COMMA));
        return orderBy;
    }

    // ==================== INSERT ====================

    private SqlInsert parseInsert() {
        tokens.expect(TokenType.INSERT);
        tokens.expect(TokenType.INTO);
        SqlIdentifier table = parseIdentifier();

        // 可选列列表: (col1, col2, ...)
        SqlNodeList columns = factory.nodeList();
        if (tokens.match(TokenType.LPAREN)) {
            do {
                columns.add(parseIdentifier());
            } while (tokens.match(TokenType.COMMA));
            tokens.expect(TokenType.RPAREN);
        }

        tokens.expect(TokenType.VALUES);

        // 多行 VALUES: (v1, v2), (v3, v4)
        SqlNodeList valueRows = factory.nodeList();
        do {
            valueRows.add(parseValueRow());
        } while (tokens.match(TokenType.COMMA));

        return new SqlInsert(table, columns, valueRows);
    }

    private SqlNodeList parseValueRow() {
        tokens.expect(TokenType.LPAREN);
        SqlNodeList row = factory.nodeList();
        do {
            row.add(parseExpression());
        } while (tokens.match(TokenType.COMMA));
        tokens.expect(TokenType.RPAREN);
        return row;
    }

    // ==================== UPDATE ====================

    private SqlUpdate parseUpdate() {
        tokens.expect(TokenType.UPDATE);
        SqlIdentifier table = parseIdentifier();
        tokens.expect(TokenType.SET);

        SqlNodeList assignments = factory.nodeList();
        do {
            SqlIdentifier col = parseIdentifier();
            tokens.expect(TokenType.EQ);
            SqlNode value = parseExpression();
            assignments.add(new SqlAssignment(col, value));
        } while (tokens.match(TokenType.COMMA));

        SqlNode where = null;
        if (tokens.match(TokenType.WHERE)) {
            where = parseExpression();
        }

        return new SqlUpdate(table, assignments, where);
    }

    // ==================== DELETE ====================

    private SqlDelete parseDelete() {
        tokens.expect(TokenType.DELETE);
        tokens.expect(TokenType.FROM);
        SqlIdentifier table = parseIdentifier();

        SqlNode where = null;
        if (tokens.match(TokenType.WHERE)) {
            where = parseExpression();
        }

        return new SqlDelete(table, where);
    }

    // ==================== CREATE (TABLE / INDEX) ====================

    private SqlNode parseCreate() {
        tokens.expect(TokenType.CREATE);
        if (tokens.current().type() == TokenType.TABLE) {
            return parseCreateTable();
        }
        if (tokens.current().type() == TokenType.INDEX) {
            return parseCreateIndex();
        }
        throw new SqlParseException("Expected TABLE or INDEX after CREATE, got " + tokens.current());
    }

    private SqlCreateTable parseCreateTable() {
        tokens.expect(TokenType.TABLE);

        boolean ifNotExists = false;
        if (tokens.current().type() == TokenType.IF) {
            tokens.next();
            tokens.expect(TokenType.NOT);
            tokens.expect(TokenType.EXISTS);
            ifNotExists = true;
        }

        SqlIdentifier table = parseIdentifier();
        tokens.expect(TokenType.LPAREN);

        java.util.List<SqlCreateTable.ColumnDef> columnDefs = new java.util.ArrayList<>();
        do {
            columnDefs.add(parseColumnDef());
        } while (tokens.match(TokenType.COMMA));

        tokens.expect(TokenType.RPAREN);
        return new SqlCreateTable(table, columnDefs, ifNotExists);
    }

    private SqlCreateTable.ColumnDef parseColumnDef() {
        Token token = tokens.current();
        String name;
        if (token.type() == TokenType.IDENTIFIER || token.type() == TokenType.DESC) {
            name = token.value();
            tokens.next();
        } else {
            tokens.expect(TokenType.IDENTIFIER);
            name = token.value(); // unreachable
        }

        SqlType type = parseColumnType();

        boolean primaryKey = false;
        if (tokens.current().type() == TokenType.PRIMARY) {
            tokens.next();
            tokens.expect(TokenType.KEY);
            primaryKey = true;
        }

        return new SqlCreateTable.ColumnDef(name, type, primaryKey);
    }

    private SqlType parseColumnType() {
        String typeName = tokens.current().value();
        tokens.expect(TokenType.IDENTIFIER);
        return switch (typeName.toUpperCase()) {
            case "INT", "INTEGER" -> SqlType.INT32;
            case "BIGINT", "LONG" -> SqlType.BIGINT;
            case "VARCHAR", "TEXT", "STRING" -> SqlType.VARCHAR;
            case "DECIMAL", "DOUBLE", "FLOAT" -> SqlType.DECIMAL;
            case "DATETIME", "TIMESTAMP" -> SqlType.DATETIME;
            default -> throw new SqlParseException("Unknown column type: " + typeName);
        };
    }

    private SqlCreateIndex parseCreateIndex() {
        tokens.expect(TokenType.INDEX);
        String indexName = tokens.current().value();
        tokens.expect(TokenType.IDENTIFIER);
        tokens.expect(TokenType.ON);
        SqlIdentifier table = parseIdentifier();
        tokens.expect(TokenType.LPAREN);
        java.util.List<String> columns = new java.util.ArrayList<>();
        do {
            columns.add(tokens.current().value());
            tokens.expect(TokenType.IDENTIFIER);
        } while (tokens.match(TokenType.COMMA));
        tokens.expect(TokenType.RPAREN);
        return new SqlCreateIndex(indexName, table, columns);
    }

    // ==================== DROP (TABLE / INDEX) ====================

    private SqlNode parseDrop() {
        tokens.expect(TokenType.DROP);
        if (tokens.current().type() == TokenType.TABLE) {
            return parseDropTable();
        }
        if (tokens.current().type() == TokenType.INDEX) {
            return parseDropIndex();
        }
        throw new SqlParseException("Expected TABLE or INDEX after DROP, got " + tokens.current());
    }

    private SqlDropTable parseDropTable() {
        tokens.expect(TokenType.TABLE);

        boolean ifExists = false;
        if (tokens.current().type() == TokenType.IF) {
            tokens.next();
            tokens.expect(TokenType.EXISTS);
            ifExists = true;
        }

        SqlIdentifier table = parseIdentifier();
        return new SqlDropTable(table, ifExists);
    }

    private SqlDropIndex parseDropIndex() {
        tokens.expect(TokenType.INDEX);
        String indexName = tokens.current().value();
        tokens.expect(TokenType.IDENTIFIER);
        tokens.expect(TokenType.ON);
        SqlIdentifier table = parseIdentifier();
        return new SqlDropIndex(indexName, table);
    }

    // ==================== ALTER TABLE ====================

    private SqlAlterTable parseAlterTable() {
        tokens.expect(TokenType.ALTER);
        tokens.expect(TokenType.TABLE);
        SqlIdentifier table = parseIdentifier();
        tokens.expect(TokenType.ADD);
        // COLUMN 关键字可选
        tokens.match(TokenType.COLUMN);
        String colName = tokens.current().value();
        tokens.expect(TokenType.IDENTIFIER);
        SqlType colType = parseColumnType();
        return new SqlAlterTable(table, colName, colType);
    }

    // ==================== 事务控制 ====================

    private SqlTransaction parseBegin() {
        tokens.expect(TokenType.BEGIN);
        tokens.match(TokenType.TRANSACTION); // 可选的 TRANSACTION 关键字
        return new SqlTransaction(SqlKind.BEGIN_TXN);
    }

    private SqlTransaction parseCommit() {
        tokens.expect(TokenType.COMMIT);
        return new SqlTransaction(SqlKind.COMMIT_TXN);
    }

    private SqlTransaction parseRollback() {
        tokens.expect(TokenType.ROLLBACK);
        return new SqlTransaction(SqlKind.ROLLBACK_TXN);
    }

    // ==================== 表达式解析 ====================

    private SqlNode parseAggCall() {
        String funcName = tokens.current().value();
        tokens.next();
        tokens.expect(TokenType.LPAREN);
        SqlNode arg;
        if (tokens.match(TokenType.STAR)) {
            arg = factory.star();
        } else {
            arg = parsePrimary();
        }
        tokens.expect(TokenType.RPAREN);
        return factory.aggCall(funcName, arg);
    }

    private SqlNode parseCaseExpression() {
        // CASE 已经被 parsePrimary() 消费，这里不再 expect
        java.util.List<SqlCase.WhenThen> whenThens = new java.util.ArrayList<>();
        while (tokens.current().type() == TokenType.WHEN) {
            tokens.next();
            SqlNode cond = parseExpression();
            tokens.expect(TokenType.THEN);
            SqlNode result = parseExpression();
            whenThens.add(new SqlCase.WhenThen(cond, result));
        }
        SqlNode elseExpr = null;
        if (tokens.match(TokenType.ELSE)) {
            elseExpr = parseExpression();
        }
        tokens.expect(TokenType.END);
        return factory.caseWhen(whenThens, elseExpr);
    }

    private boolean isAggFunction(TokenType type) {
        return type == TokenType.COUNT || type == TokenType.SUM
            || type == TokenType.AVG || type == TokenType.MAX
            || type == TokenType.MIN;
    }

    // 优先级: OR < AND < 比较运算符
    private SqlNode parseExpression() {
        return parseOr();
    }

    private SqlNode parseOr() {
        SqlNode left = parseAnd();
        while (tokens.current().type() == TokenType.OR) {
            tokens.next();
            SqlNode right = parseAnd();
            left = factory.binary(SqlKind.OR, left, right);
        }
        return left;
    }

    private SqlNode parseAnd() {
        SqlNode left = parseComparison();
        while (tokens.current().type() == TokenType.AND) {
            tokens.next();
            SqlNode right = parseComparison();
            left = factory.binary(SqlKind.AND, left, right);
        }
        return left;
    }

    private SqlNode parseComparison() {
        SqlNode left = parseAddSub();

        // NOT 前缀（用于 NOT LIKE / NOT BETWEEN / NOT IN）
        boolean negated = false;
        if (tokens.current().type() == TokenType.NOT) {
            negated = true;
            tokens.next();
        }

        TokenType t = tokens.current().type();

        // LIKE
        if (t == TokenType.LIKE) {
            tokens.next();
            SqlNode pattern = parseAddSub();
            SqlNode like = factory.binary(SqlKind.LIKE, left, pattern);
            return negated ? factory.binary(SqlKind.NOT_LIKE, left, pattern) : like;
        }

        // BETWEEN expr AND expr
        if (t == TokenType.BETWEEN) {
            tokens.next();
            SqlNode low = parseAddSub();
            tokens.expect(TokenType.AND);
            SqlNode high = parseAddSub();
            SqlNode between = new SqlBetween(left, low, high);
            return negated ? factory.binary(SqlKind.NOT_BETWEEN, left, between) : between;
        }

        // IN (v1, v2, ...) 或 IN (SELECT ...)
        if (t == TokenType.IN) {
            tokens.next();
            tokens.expect(TokenType.LPAREN);
            if (tokens.current().type() == TokenType.SELECT) {
                SqlSelect sub = parseSelect();
                tokens.expect(TokenType.RPAREN);
                return new SqlInSubquery(left, sub, negated);
            }
            SqlNodeList values = factory.nodeList();
            do {
                values.add(parseAddSub());
            } while (tokens.match(TokenType.COMMA));
            tokens.expect(TokenType.RPAREN);
            SqlNode in = new SqlInList(left, values);
            return negated ? factory.binary(SqlKind.NOT_IN, left, in) : in;
        }

        // IS [NOT] NULL
        if (t == TokenType.IS) {
            tokens.next();
            boolean isNot = tokens.match(TokenType.NOT);
            tokens.expect(TokenType.NULL);
            boolean resultNot = negated != isNot; // NOT IS NULL = IS NOT NULL, NOT IS NOT NULL = IS NULL
            return factory.binary(resultNot ? SqlKind.IS_NOT_NULL : SqlKind.IS_NULL, left, factory.nullLiteral());
        }

        // 如果有 NOT 但后面不是谓词关键字，回退不了，报错
        if (negated) {
            throw new SqlParseException("Expected LIKE, BETWEEN, IN, or IS after NOT, got " + tokens.current());
        }

        SqlKind kind = matchComparisonOp();
        if (kind != null) {
            SqlNode right = parseAddSub();
            return factory.binary(kind, left, right);
        }
        return left;
    }

    private SqlKind matchComparisonOp() {
        TokenType t = tokens.current().type();
        SqlKind kind = switch (t) {
            case EQ -> SqlKind.BINARY_EQ;
            case LT -> SqlKind.BINARY_LT;
            case GT -> SqlKind.BINARY_GT;
            case LE -> SqlKind.BINARY_LE;
            case GE -> SqlKind.BINARY_GE;
            case NE -> SqlKind.BINARY_NE;
            default -> null;
        };
        if (kind != null) tokens.next();
        return kind;
    }

    // 优先级: 加减 < 乘除 < Primary
    private SqlNode parseAddSub() {
        SqlNode left = parseMulDiv();
        while (true) {
            TokenType t = tokens.current().type();
            if (t == TokenType.PLUS) {
                tokens.next();
                left = factory.binary(SqlKind.ADD, left, parseMulDiv());
            } else if (t == TokenType.MINUS) {
                tokens.next();
                left = factory.binary(SqlKind.SUB, left, parseMulDiv());
            } else {
                break;
            }
        }
        return left;
    }

    private SqlNode parseMulDiv() {
        SqlNode left = parsePrimary();
        while (true) {
            TokenType t = tokens.current().type();
            if (t == TokenType.STAR) {
                tokens.next();
                left = factory.binary(SqlKind.MUL, left, parsePrimary());
            } else if (t == TokenType.DIV) {
                tokens.next();
                left = factory.binary(SqlKind.DIV, left, parsePrimary());
            } else {
                break;
            }
        }
        return left;
    }

    private SqlNode parsePrimary() {
        Token token = tokens.current();
        if (token.type() == TokenType.NULL) {
            tokens.next();
            return factory.nullLiteral();
        }
        if (token.type() == TokenType.CASE) {
            tokens.next();
            return parseCaseExpression();
        }
        // NOT EXISTS (SELECT ...)
        if (token.type() == TokenType.NOT && tokens.peek().type() == TokenType.EXISTS) {
            tokens.next(); // consume NOT
            tokens.next(); // consume EXISTS
            tokens.expect(TokenType.LPAREN);
            SqlSelect sub = parseSelect();
            tokens.expect(TokenType.RPAREN);
            return new SqlExists(sub, true);
        }
        // EXISTS (SELECT ...)
        if (token.type() == TokenType.EXISTS) {
            tokens.next(); // consume EXISTS
            tokens.expect(TokenType.LPAREN);
            SqlSelect sub = parseSelect();
            tokens.expect(TokenType.RPAREN);
            return new SqlExists(sub, false);
        }
        if (token.type() == TokenType.NUMBER) {
            tokens.next();
            return factory.number(token.value());
        }
        if (token.type() == TokenType.STRING) {
            tokens.next();
            return factory.string(token.value());
        }
        if (token.type() == TokenType.LPAREN) {
            if (tokens.peek().type() == TokenType.SELECT) {
                tokens.next(); // consume '('
                SqlSelect sub = parseSelect();
                tokens.expect(TokenType.RPAREN);
                return new SqlSubquery(sub);
            }
            tokens.next();
            SqlNode expr = parseExpression();
            tokens.expect(TokenType.RPAREN);
            return expr;
        }
        if (isAggFunction(token.type())) {
            return parseAggCall();
        }

        // 支持标量函数 UPPER/LOWER/COALESCE(...)
        Token currentToken = tokens.current();
        if (currentToken.type() == TokenType.IDENTIFIER) {
            String funcName = currentToken.value().toUpperCase();
            if (funcName.equals("UPPER") || funcName.equals("LOWER") || funcName.equals("COALESCE")) {
                tokens.next(); // consume function name
                tokens.expect(TokenType.LPAREN);
                SqlNodeList args = factory.nodeList();
                if (!tokens.current().type().equals(TokenType.RPAREN)) {
                    do {
                        args.add(parseExpression());
                    } while (tokens.match(TokenType.COMMA));
                }
                tokens.expect(TokenType.RPAREN);
                return factory.functionCall(funcName, args);
            }
        }

        // IDENTIFIER or IDENTIFIER.IDENTIFIER
        SqlIdentifier id = parseIdentifier();
        if (tokens.current().type() == TokenType.DOT) {
            tokens.next();
            SqlIdentifier col = parseIdentifier();
            return factory.identifier(id.name() + "." + col.name());
        }
        return id;
    }

    private SqlIdentifier parseIdentifier() {
        Token token = tokens.current();
        if (token.type() != TokenType.IDENTIFIER && token.type() != TokenType.DESC) {
            throw new SqlParseException("Expected identifier, got " + token);
        }
        tokens.next();
        return factory.identifier(token.value());
    }

    private SqlTableRef parseTableRef() {
        SqlIdentifier table = parseIdentifier();
        return factory.tableRef(table.name(), parseOptionalAlias());
    }

    private String parseOptionalAlias() {
        if (tokens.match(TokenType.AS)) {
            return parseIdentifier().name();
        }
        if (tokens.current().type() == TokenType.IDENTIFIER) {
            return parseIdentifier().name();
        }
        return null;
    }
}
