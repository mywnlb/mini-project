package cn.zhangyis.minidb.sql.parser;

import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.InformationSchemaNames;
import cn.zhangyis.minidb.sql.functions.FunctionRegistry;
import cn.zhangyis.minidb.sql.types.SqlType;
import java.util.List;

import static cn.zhangyis.minidb.sql.lexer.TokenType.*;

public class SqlParser {
    private final TokenStream tokens;
    private final SqlNodeFactory factory;
    private int paramIndex = 0;

    public SqlParser(TokenStream tokens) {
        this.tokens = tokens;
        this.factory = SqlNodeFactory.DEFAULT;
    }

    public int paramCount() {
        return paramIndex;
    }

    public SqlNode parseStatement() {
        TokenType type = tokens.current().type();
        SqlNode result = switch (type) {
            case SELECT -> {
                SqlNode s = parseSelect();
                TokenType setOpToken = tokens.current().type();
                while (setOpToken == TokenType.UNION || setOpToken == TokenType.EXCEPT || setOpToken == TokenType.INTERSECT) {
                    tokens.next();
                    boolean all = tokens.match(TokenType.ALL);
                    SqlNode right = parseSelect();
                    SqlSetOperation.SetOpType opType = switch (setOpToken) {
                        case UNION -> SqlSetOperation.SetOpType.UNION;
                        case EXCEPT -> SqlSetOperation.SetOpType.EXCEPT;
                        case INTERSECT -> SqlSetOperation.SetOpType.INTERSECT;
                        default -> throw new SqlParseException("Unexpected: " + setOpToken);
                    };
                    s = factory.setOperation(s, right, all, opType);
                    setOpToken = tokens.current().type();
                }
                yield s;
            }
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            case CREATE -> parseCreate();
            case DROP -> parseDrop();
            case ALTER -> parseAlterTable();
            case WITH -> parseWith();
            case ANALYZE -> parseAnalyze();
            case EXPLAIN -> parseExplain();
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
        while (isJoinStart(tokens.current().type())) {
            boolean natural = tokens.match(TokenType.NATURAL);
            JoinType joinType = parseJoinType();
            SqlNode right = parseFromItem();

            if (natural) {
                left = factory.naturalJoin(joinType, left, right);
            } else if (joinType != JoinType.CROSS && tokens.current().type() == TokenType.USING) {
                tokens.next(); // consume USING
                tokens.expect(TokenType.LPAREN);
                java.util.List<String> cols = parseUsingColumnList();
                tokens.expect(TokenType.RPAREN);
                left = factory.usingJoin(joinType, left, right, cols);
            } else {
                SqlNode condition = null;
                if (joinType != JoinType.CROSS) {
                    tokens.expect(TokenType.ON);
                    condition = parseExpression();
                }
                left = factory.join(joinType, left, right, condition);
            }
        }
        return left;
    }

    private java.util.List<String> parseUsingColumnList() {
        java.util.List<String> cols = new java.util.ArrayList<>();
        do {
            cols.add(parseIdentifier().name());
        } while (tokens.match(TokenType.COMMA));
        return cols;
    }

    private boolean isJoinStart(TokenType type) {
        return type == TokenType.JOIN || type == LEFT || type == RIGHT
            || type == FULL || type == CROSS || type == INNER || type == TokenType.NATURAL;
    }

    private JoinType parseJoinType() {
        TokenType type = tokens.current().type();
        if (type == LEFT) {
            tokens.next();
            tokens.match(OUTER);
            tokens.expect(TokenType.JOIN);
            return JoinType.LEFT;
        }
        if (type == RIGHT) {
            tokens.next();
            tokens.match(OUTER);
            tokens.expect(TokenType.JOIN);
            return JoinType.RIGHT;
        }
        if (type == FULL) {
            tokens.next();
            tokens.match(OUTER);
            tokens.expect(TokenType.JOIN);
            return JoinType.FULL;
        }
        if (type == CROSS) {
            tokens.next();
            tokens.expect(TokenType.JOIN);
            return JoinType.CROSS;
        }
        if (type == INNER) {
            tokens.next();
            tokens.expect(TokenType.JOIN);
            return JoinType.INNER;
        }
        // 纯 JOIN
        tokens.expect(TokenType.JOIN);
        return JoinType.INNER;
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

    private SqlNode parseInsert() {
        tokens.expect(TokenType.INSERT);
        tokens.expect(TokenType.INTO);
        SqlIdentifier table = parseQualifiedTableName();

        // 可选列列表: (col1, col2, ...)
        SqlNodeList columns = factory.nodeList();
        if (tokens.match(TokenType.LPAREN)) {
            do {
                columns.add(parseIdentifier());
            } while (tokens.match(TokenType.COMMA));
            tokens.expect(TokenType.RPAREN);
        }

        // INSERT INTO ... SELECT ...
        if (tokens.current().type() == TokenType.SELECT) {
            SqlSelect select = parseSelect();
            return new SqlInsertSelect(table, columns, select);
        }

        // INSERT INTO ... VALUES ...
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
        SqlIdentifier table = parseQualifiedTableName();
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
        SqlIdentifier table = parseQualifiedTableName();

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

        SqlIdentifier table = parseQualifiedTableName();
        tokens.expect(TokenType.LPAREN);

        java.util.List<SqlCreateTable.ColumnDef> columnDefs = new java.util.ArrayList<>();
        java.util.List<SqlCreateTable.TableIndexDef> indexes = new java.util.ArrayList<>();
        java.util.Set<String> tableLevelPkColumns = new java.util.LinkedHashSet<>();
        while (tokens.current().type() != TokenType.RPAREN && tokens.current().type() != TokenType.EOF) {
            if (tokens.current().type() == TokenType.PRIMARY) {
                SqlCreateTable.TableIndexDef primary = parsePrimaryKeyConstraint();
                indexes.add(primary);
                for (String column : primary.columns()) {
                    tableLevelPkColumns.add(column.toUpperCase());
                }
            } else if (isUniqueConstraintStart()) {
                indexes.add(parseTableIndexConstraint(true));
            } else if (tokens.current().type() == TokenType.INDEX
                    || tokens.current().type() == TokenType.KEY) {
                indexes.add(parseTableIndexConstraint(false));
            } else if (isForeignKeyConstraintStart()) {
                skipTableConstraintDefinition();
            } else {
                columnDefs.add(parseColumnDef());
            }

            if (!tokens.match(TokenType.COMMA)) {
                break;
            }
        }

        // 将表级 PRIMARY KEY 标记合并到列定义
        if (!tableLevelPkColumns.isEmpty()) {
            java.util.List<SqlCreateTable.ColumnDef> merged = new java.util.ArrayList<>(columnDefs.size());
            for (SqlCreateTable.ColumnDef def : columnDefs) {
                if (tableLevelPkColumns.contains(def.name().toUpperCase())) {
                    // 标记为主键，且主键列隐含 NOT NULL
                    merged.add(new SqlCreateTable.ColumnDef(
                            def.name(), def.type(), true, false, def.length()));
                } else {
                    merged.add(def);
                }
            }
            columnDefs = merged;
        }

        tokens.expect(TokenType.RPAREN);
        skipCreateTableOptions();
        return new SqlCreateTable(table, columnDefs, indexes, ifNotExists);
    }

    /**
     * 解析表级 PRIMARY KEY (...) 约束。
     */
    private SqlCreateTable.TableIndexDef parsePrimaryKeyConstraint() {
        tokens.expect(TokenType.PRIMARY);
        tokens.expect(TokenType.KEY);
        java.util.List<String> columns = parseIndexColumnList();
        skipIndexOptions();
        return new SqlCreateTable.TableIndexDef("PRIMARY", columns, true, true);
    }

    /**
     * 跳过表级约束定义（CONSTRAINT ... FOREIGN KEY ... REFERENCES ...）。
     */
    private void skipTableConstraintDefinition() {
        int depth = 0;
        while (tokens.current().type() != TokenType.EOF) {
            if (tokens.current().type() == TokenType.LPAREN) {
                depth++;
            } else if (tokens.current().type() == TokenType.RPAREN) {
                if (depth == 0) {
                    return;
                }
                depth--;
            } else if (tokens.current().type() == TokenType.COMMA && depth == 0) {
                return;
            }
            tokens.next();
        }
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

        ParsedColumnType type = parseColumnType();

        // 解析列修饰符：NOT NULL、NULL、DEFAULT、COMMENT、PRIMARY KEY、AUTO_INCREMENT 等
        boolean primaryKey = false;
        boolean nullable = true; // 默认允许 NULL
        boolean done = false;
        while (!done) {
            switch (tokens.current().type()) {
                case NOT -> {
                    tokens.next();
                    tokens.expect(TokenType.NULL); // NOT NULL
                    nullable = false;
                }
                case NULL -> tokens.next(); // NULL (nullable)
                case DEFAULT -> {
                    tokens.next();
                    skipColumnValueExpression();
                }
                case PRIMARY -> {
                    tokens.next();
                    tokens.expect(TokenType.KEY);
                    primaryKey = true;
                }
                case IDENTIFIER -> {
                    String kw = tokens.current().value();
                    if ("COMMENT".equals(kw)) {
                        tokens.next();
                        skipColumnValueExpression();
                    } else if ("CHARACTER".equals(kw)) {
                        tokens.next();
                        if (tokens.current().type() == TokenType.SET || isIdentifierValue("SET")) {
                            tokens.next();
                            parseIdentifier();
                        }
                    } else if ("COLLATE".equals(kw)) {
                        tokens.next();
                        parseIdentifier();
                    } else if ("AUTO_INCREMENT".equals(kw) || "UNSIGNED".equals(kw)
                            || "UNIQUE".equals(kw)) {
                        tokens.next();
                    } else {
                        done = true;
                    }
                }
                case ON -> {
                    tokens.next();
                    tokens.expect(TokenType.UPDATE);
                    skipColumnValueExpression();
                }
                default -> done = true;
            }
        }

        return new SqlCreateTable.ColumnDef(name, type.type(), primaryKey, nullable, type.length());
    }

    private ParsedColumnType parseColumnType() {
        String typeName = tokens.current().value();
        tokens.expect(TokenType.IDENTIFIER);

        Integer length = null;
        if (tokens.current().type() == TokenType.LPAREN) {
            tokens.next();
            if (tokens.current().type() == TokenType.NUMBER) {
                length = Integer.parseInt(tokens.current().value());
            }
            while (tokens.current().type() != TokenType.RPAREN
                    && tokens.current().type() != TokenType.EOF) {
                tokens.next();
            }
            if (tokens.current().type() == TokenType.RPAREN) {
                tokens.next();
            }
        }

        SqlType sqlType = switch (typeName.toUpperCase()) {
            case "TINYINT" -> SqlType.TINYINT;
            case "SMALLINT" -> SqlType.SMALLINT;
            case "INT", "INTEGER" -> SqlType.INT32;
            case "BIGINT", "LONG" -> SqlType.BIGINT;
            case "CHAR" -> SqlType.CHAR;
            case "VARCHAR", "STRING" -> SqlType.VARCHAR;
            case "TEXT", "MEDIUMTEXT" -> SqlType.TEXT;
            case "JSON" -> SqlType.JSON;
            case "BLOB", "MEDIUMBLOB" -> SqlType.BLOB;
            case "DECIMAL", "DOUBLE", "FLOAT" -> SqlType.DECIMAL;
            case "DATE" -> SqlType.DATE;
            case "TIME" -> SqlType.TIME;
            case "DATETIME", "TIMESTAMP" -> SqlType.DATETIME;
            default -> throw new SqlParseException("Unknown column type: " + typeName);
        };
        return new ParsedColumnType(sqlType, length);
    }

    private SqlType parseCastType() {
        String typeName = tokens.current().value();
        tokens.expect(TokenType.IDENTIFIER);
        return switch (typeName.toUpperCase()) {
            case "TINYINT" -> SqlType.TINYINT;
            case "SMALLINT" -> SqlType.SMALLINT;
            case "INT", "INTEGER" -> SqlType.INT32;
            case "BIGINT", "LONG" -> SqlType.BIGINT;
            case "CHAR" -> SqlType.CHAR;
            case "VARCHAR", "STRING" -> SqlType.VARCHAR;
            case "TEXT", "MEDIUMTEXT" -> SqlType.TEXT;
            case "JSON" -> SqlType.JSON;
            case "BLOB", "MEDIUMBLOB" -> SqlType.BLOB;
            case "DECIMAL", "DOUBLE", "FLOAT" -> SqlType.DECIMAL;
            case "DATE" -> SqlType.DATE;
            case "TIME" -> SqlType.TIME;
            case "DATETIME", "TIMESTAMP" -> SqlType.DATETIME;
            default -> throw new SqlParseException("Unknown CAST type: " + typeName);
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

        SqlIdentifier table = parseQualifiedTableName();
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
        SqlIdentifier table = parseQualifiedTableName();
        tokens.expect(TokenType.ADD);
        // COLUMN 关键字可选
        tokens.match(TokenType.COLUMN);
        String colName = tokens.current().value();
        tokens.expect(TokenType.IDENTIFIER);
        ParsedColumnType parsed = parseColumnType();
        SqlType colType = parsed.type();

        // 可选 NOT NULL
        boolean nullable = true;
        if (tokens.current().type() == TokenType.NOT) {
            tokens.next();
            tokens.expect(TokenType.NULL);
            nullable = false;
        }

        // 可选 DEFAULT <literal>
        SqlNode defaultValue = null;
        if (tokens.match(TokenType.DEFAULT)) {
            defaultValue = parsePrimary();
        }

        return new SqlAlterTable(table, colName, colType, nullable, defaultValue);
    }

    // ==================== WITH (CTE) ====================

    private SqlNode parseWith() {
        tokens.expect(TokenType.WITH);
        java.util.List<SqlCte> ctes = new java.util.ArrayList<>();
        do {
            String name = parseIdentifier().name();

            // 解析可选的列名列表: WITH cte(col1, col2) AS (...)
            List<String> columnNames = null;
            if (tokens.current().type() == TokenType.LPAREN) {
                Token nextToken = tokens.peek();
                if (nextToken.type() == TokenType.IDENTIFIER) {
                    // 是列名列表，而不是 (SELECT ...)
                    tokens.next(); // consume LPAREN
                    columnNames = parseColumnNameList();
                    tokens.expect(TokenType.RPAREN);
                }
            }

            tokens.expect(TokenType.AS);
            tokens.expect(TokenType.LPAREN);
            SqlSelect cteQuery = parseSelect();
            tokens.expect(TokenType.RPAREN);

            ctes.add(new SqlCte(name, columnNames, cteQuery));
        } while (tokens.match(TokenType.COMMA));
        SqlSelect mainSelect = parseSelect();
        return new SqlWithSelect(ctes, mainSelect);
    }

    private List<String> parseColumnNameList() {
        List<String> cols = new java.util.ArrayList<>();
        do {
            cols.add(parseIdentifier().name());
        } while (tokens.match(TokenType.COMMA));
        return cols;
    }

    // ==================== ANALYZE ====================

    private SqlNode parseAnalyze() {
        tokens.expect(TokenType.ANALYZE);
        tokens.expect(TokenType.TABLE);
        String tableName = parseQualifiedTableName().name();
        return new SqlAnalyzeTable(tableName);
    }

    private SqlNode parseExplain() {
        tokens.expect(TokenType.EXPLAIN);
        boolean analyze = tokens.current().type() == TokenType.ANALYZE;
        if (analyze) {
            tokens.next(); // consume ANALYZE
        }
        SqlNode query = parseStatement();
        return new SqlExplain(query, analyze);
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

    private boolean isWindowFunction(TokenType type) {
        return type == TokenType.ROW_NUMBER || type == TokenType.RANK || type == TokenType.DENSE_RANK
            || type == TokenType.LAG || type == TokenType.LEAD
            || type == TokenType.NTILE || type == TokenType.PERCENT_RANK || type == TokenType.CUME_DIST;
    }

    private SqlNode parseWindowFunction() {
        String funcName = tokens.current().value();
        tokens.next();
        tokens.expect(TokenType.LPAREN);

        // LAG/LEAD: (col [, offset [, default]])
        // NTILE: (n)
        // ROW_NUMBER/RANK/DENSE_RANK/PERCENT_RANK/CUME_DIST: ()
        SqlNodeList args = null;
        if (tokens.current().type() != TokenType.RPAREN) {
            args = factory.nodeList();
            do {
                args.add(parseExpression());
            } while (tokens.match(TokenType.COMMA));
        }
        tokens.expect(TokenType.RPAREN);
        tokens.expect(TokenType.OVER);
        tokens.expect(TokenType.LPAREN);

        SqlNodeList partitionBy = null;
        if (tokens.current().type() == TokenType.PARTITION) {
            tokens.next();
            tokens.expect(TokenType.BY);
            partitionBy = factory.nodeList();
            do {
                partitionBy.add(parseExpression());
            } while (tokens.match(TokenType.COMMA));
        }

        SqlNodeList orderBy = null;
        if (tokens.current().type() == TokenType.ORDER) {
            tokens.next();
            tokens.expect(TokenType.BY);
            orderBy = parseOrderByList();
        }

        tokens.expect(TokenType.RPAREN);

        // 将参数编码到 SqlWindowFunction
        SqlNode arg = args != null && args.size() > 0 ? args.get(0) : null;
        SqlWindowFunction wf = new SqlWindowFunction(funcName, arg, partitionBy, orderBy);
        // 存储额外参数（offset, default for LAG/LEAD; n for NTILE）
        if (args != null && args.size() > 1) {
            wf = new SqlWindowFunction(funcName, arg, partitionBy, orderBy, args);
        }
        return wf;
    }

    private SqlNode parseAggWindowFunction(SqlAggCall aggCall) {
        tokens.expect(TokenType.OVER);
        tokens.expect(TokenType.LPAREN);

        SqlNodeList partitionBy = null;
        if (tokens.current().type() == TokenType.PARTITION) {
            tokens.next();
            tokens.expect(TokenType.BY);
            partitionBy = factory.nodeList();
            do {
                partitionBy.add(parseExpression());
            } while (tokens.match(TokenType.COMMA));
        }

        SqlNodeList orderBy = null;
        if (tokens.current().type() == TokenType.ORDER) {
            tokens.next();
            tokens.expect(TokenType.BY);
            orderBy = parseOrderByList();
        }

        tokens.expect(TokenType.RPAREN);
        return new SqlWindowFunction(aggCall.funcName(), aggCall.arg(), partitionBy, orderBy);
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
        // CAST(expr AS type)
        if (token.type() == TokenType.CAST) {
            tokens.next();
            tokens.expect(TokenType.LPAREN);
            SqlNode expr = parseExpression();
            tokens.expect(TokenType.AS);
            SqlType targetType = parseCastType();
            tokens.expect(TokenType.RPAREN);
            return new SqlCast(expr, targetType);
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
        if (token.type() == TokenType.PARAMETER) {
            tokens.next();
            return new SqlParameter(paramIndex++);
        }
        if (token.type() == TokenType.NUMBER) {
            tokens.next();
            return factory.number(token.value());
        }
        if (token.type() == TokenType.HEX) {
            tokens.next();
            return factory.hex(token.value());
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
        // 窗口函数: ROW_NUMBER() / RANK() / DENSE_RANK() OVER (...)
        if (isWindowFunction(token.type())) {
            return parseWindowFunction();
        }
        if (isAggFunction(token.type())) {
            SqlNode aggCall = parseAggCall();
            // 聚合函数后跟 OVER → 聚合窗口函数
            if (tokens.current().type() == TokenType.OVER) {
                SqlAggCall agg = (SqlAggCall) aggCall;
                return parseAggWindowFunction(agg);
            }
            return aggCall;
        }

        // 支持标量函数（动态查询 FunctionRegistry）
        Token currentToken = tokens.current();
        if (currentToken.type() == TokenType.IDENTIFIER) {
            String funcName = currentToken.value().toUpperCase();
            if (FunctionRegistry.contains(funcName)) {
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

    /**
     * 解析可能带 schema 前缀的表名（schema.table），忽略 schema 部分只取表名。
     * 用于 DDL / DML 中表名可能被 Navicat 等客户端加上数据库前缀的场景。
     */
    private SqlIdentifier parseQualifiedTableName() {
        SqlIdentifier first = parseIdentifier();
        if (tokens.current().type() != TokenType.DOT) {
            return first;
        }

        tokens.next();
        SqlIdentifier second = parseIdentifier();

        if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(first.name())) {
            String internalName = InformationSchemaNames.internalNameFor(second.name());
            if (internalName != null) {
                return factory.identifier(internalName);
            }
            return factory.identifier(first.name() + "." + second.name());
        }

        // 当前项目仍保持普通 db.table → table 的最小语义
        return second;
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
        SqlIdentifier table = parseQualifiedTableName();
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

    private SqlCreateTable.TableIndexDef parseTableIndexConstraint(boolean unique) {
        if (unique) {
            tokens.next(); // UNIQUE
            if (tokens.current().type() == TokenType.KEY || tokens.current().type() == TokenType.INDEX) {
                tokens.next();
            }
        } else {
            tokens.next(); // KEY / INDEX
        }

        String indexName = null;
        if (tokens.current().type() == TokenType.LPAREN) {
        } else {
            indexName = parseIdentifier().name();
        }

        java.util.List<String> columns = parseIndexColumnList();
        if (indexName == null || indexName.isBlank()) {
            indexName = (unique ? "UNQ_" : "IDX_") + String.join("_", columns);
        }
        skipIndexOptions();
        return new SqlCreateTable.TableIndexDef(indexName, columns, false, unique);
    }

    private java.util.List<String> parseIndexColumnList() {
        tokens.expect(TokenType.LPAREN);
        java.util.List<String> columns = new java.util.ArrayList<>();
        do {
            columns.add(parseIdentifier().name());
            if (tokens.current().type() == TokenType.LPAREN) {
                skipParenthesizedClause();
            }
            if (tokens.current().type() == TokenType.ASC || tokens.current().type() == TokenType.DESC) {
                tokens.next();
            }
        } while (tokens.match(TokenType.COMMA));
        tokens.expect(TokenType.RPAREN);
        return columns;
    }

    private void skipIndexOptions() {
        while (tokens.current().type() != TokenType.COMMA
                && tokens.current().type() != TokenType.RPAREN
                && tokens.current().type() != TokenType.EOF) {
            if (tokens.current().type() == TokenType.LPAREN) {
                skipParenthesizedClause();
            } else {
                tokens.next();
            }
        }
    }

    private void skipCreateTableOptions() {
        while (tokens.current().type() != TokenType.EOF && tokens.current().type() != TokenType.SEMICOLON) {
            if (tokens.current().type() == TokenType.COMMA) {
                tokens.next();
                continue;
            }
            if (tokens.current().type() == TokenType.LPAREN) {
                skipParenthesizedClause();
                continue;
            }
            tokens.next();
        }
    }

    private void skipParenthesizedClause() {
        tokens.expect(TokenType.LPAREN);
        int depth = 1;
        while (depth > 0 && tokens.current().type() != TokenType.EOF) {
            if (tokens.current().type() == TokenType.LPAREN) {
                depth++;
            } else if (tokens.current().type() == TokenType.RPAREN) {
                depth--;
            }
            tokens.next();
        }
    }

    private void skipColumnValueExpression() {
        if (tokens.current().type() == TokenType.MINUS || tokens.current().type() == TokenType.PLUS) {
            tokens.next();
        }
        if (tokens.current().type() == TokenType.LPAREN) {
            skipParenthesizedClause();
            return;
        }

        tokens.next();
        if (tokens.current().type() == TokenType.LPAREN) {
            skipParenthesizedClause();
        }
    }

    private boolean isUniqueConstraintStart() {
        return tokens.current().type() == TokenType.IDENTIFIER
                && "UNIQUE".equals(tokens.current().value());
    }

    private boolean isForeignKeyConstraintStart() {
        return (tokens.current().type() == TokenType.IDENTIFIER
                && "CONSTRAINT".equals(tokens.current().value()))
                || (tokens.current().type() == TokenType.IDENTIFIER
                && "FOREIGN".equals(tokens.current().value()));
    }

    private boolean isIdentifierValue(String value) {
        return tokens.current().type() == TokenType.IDENTIFIER
                && value.equals(tokens.current().value());
    }

    private record ParsedColumnType(SqlType type, Integer length) {
    }
}
