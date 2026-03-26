package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.ConnectionSession;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.TypeMapping;
import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.InformationSchemaNames;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.exec.ParameterBinder;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.types.SqlType;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;
import cn.zhangyis.minidb.sql.validation.ValidatedWithSelect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 SQL 结果列元数据，用于 text/binary protocol 和 COM_STMT_PREPARE。
 */
public final class ResultMetadataResolver {

    private ResultMetadataResolver() {
    }

    public static QueryMetadata resolve(String sql, ConnectionSession session) {
        SqlParser parser = new SqlParser(new TokenStream(new SqlLexer(sql)));
        SqlNode ast = parser.parseStatement();
        SqlNode bound = bindNullParameters(ast, parser.paramCount());

        if (bound instanceof SqlExplain explain) {
            return new QueryMetadata(true, explain.analyze()
                    ? explainAnalyzeColumns()
                    : explainColumns());
        }

        if (bound instanceof SqlSelect select) {
            SqlValidator validator = new SqlValidator(session.catalog());
            ValidatedSqlSelect validated = (ValidatedSqlSelect) validator.validate(select);
            return new QueryMetadata(true, resolveSelect(validated, session));
        }

        if (bound instanceof SqlWithSelect withSelect) {
            SqlValidator validator = new SqlValidator(session.catalog());
            ValidatedWithSelect validated = (ValidatedWithSelect) validator.validate(withSelect);
            return new QueryMetadata(true, resolveSelect(validated.mainSelect(), session));
        }

        if (bound instanceof SqlSetOperation setOp) {
            SqlValidator validator = new SqlValidator(session.catalog());
            validator.validate(setOp);
            return new QueryMetadata(true, resolveFromSetOperation(setOp, session));
        }

        return QueryMetadata.command();
    }

    private static List<ResultColumnMetadata> resolveFromSetOperation(SqlSetOperation setOp,
                                                                      ConnectionSession session) {
        SqlNode left = setOp.left();
        if (left instanceof SqlSelect select) {
            SqlValidator validator = new SqlValidator(session.catalog());
            return resolveSelect((ValidatedSqlSelect) validator.validate(select), session);
        }
        if (left instanceof SqlWithSelect withSelect) {
            SqlValidator validator = new SqlValidator(session.catalog());
            return resolveSelect(((ValidatedWithSelect) validator.validate(withSelect)).mainSelect(), session);
        }
        if (left instanceof SqlSetOperation nested) {
            return resolveFromSetOperation(nested, session);
        }
        return List.of();
    }

    private static SqlNode bindNullParameters(SqlNode ast, int paramCount) {
        if (paramCount == 0) {
            return ast;
        }
        return new ParameterBinder(java.util.Collections.nCopies(paramCount, null)).bind(ast);
    }

    private static List<ResultColumnMetadata> resolveSelect(ValidatedSqlSelect validated,
                                                            ConnectionSession session) {
        Map<String, SourceInfo> sources = new LinkedHashMap<>();
        collectSources(validated.original().from(), validated, session, sources);

        SqlNodeList projection = validated.original().projection();
        if (projection.size() == 1 && projection.get(0).kind() == SqlKind.STAR) {
            return expandStarColumns(sources);
        }

        List<ResultColumnMetadata> columns = new ArrayList<>();
        for (SqlNode node : projection.nodes()) {
            columns.add(resolveProjectionColumn(node, sources));
        }
        return columns;
    }

    private static List<ResultColumnMetadata> expandStarColumns(Map<String, SourceInfo> sources) {
        List<ResultColumnMetadata> columns = new ArrayList<>();
        for (SourceInfo source : sources.values()) {
            if (source.tableMeta() == null) {
                continue;
            }
            for (ColumnMeta column : source.tableMeta().columns()) {
                String lookupKey = source.visibleName() + "." + column.name();
                columns.add(ResultColumnMetadata.of(
                        lookupKey,
                        source.schema(),
                        displayTableName(source),
                        source.externalTableName(),
                        column.name(),
                        column.name(),
                        TypeMapping.toMysqlType(column.type()),
                        columnFlags(column)
                ));
            }
        }
        return columns;
    }

    private static ResultColumnMetadata resolveProjectionColumn(SqlNode node,
                                                               Map<String, SourceInfo> sources) {
        SqlNode expression = unwrapAlias(node);
        String lookupKey = projectionLookupKey(node);
        String displayName = projectionDisplayName(node);
        String orgName = projectionOrgName(node);
        SqlType sqlType = inferType(expression, sources);
        int mysqlType = TypeMapping.toMysqlType(sqlType != null ? sqlType : SqlType.VARCHAR);

        if (expression instanceof SqlIdentifier id) {
            ColumnRef ref = resolveColumnReference(id.name(), sources);
            if (ref != null) {
                ColumnMeta column = ref.column();
                return ResultColumnMetadata.of(
                        lookupKey,
                        ref.source().schema(),
                        displayTableName(ref.source()),
                        ref.source().externalTableName(),
                        displayName,
                        orgName != null ? orgName : column.name(),
                        TypeMapping.toMysqlType(column.type()),
                        columnFlags(column)
                );
            }
        }

        return ResultColumnMetadata.of(
                lookupKey,
                "",
                "",
                "",
                displayName,
                orgName != null ? orgName : displayName,
                mysqlType,
                0
        );
    }

    private static void collectSources(SqlNode from, ValidatedSqlSelect validated,
                                       ConnectionSession session,
                                       Map<String, SourceInfo> sources) {
        if (from == null) {
            return;
        }
        if (from instanceof SqlJoin join) {
            collectSources(join.left(), validated, session, sources);
            collectSources(join.right(), validated, session, sources);
            return;
        }
        if (from instanceof SqlDerivedTable derived) {
            TableMeta tableMeta = validated.table(derived.alias());
            sources.put(derived.alias().toUpperCase(), new SourceInfo(
                    derived.alias(),
                    derived.alias(),
                    derived.alias(),
                    "",
                    tableMeta
            ));
            return;
        }
        if (!(from instanceof SqlTableRef ref)) {
            return;
        }

        String visibleName = ref.visibleName();
        String sourceTableName = ref.tableName();
        String externalTableName = InformationSchemaNames.externalNameForInternal(sourceTableName);
        if (externalTableName == null) {
            externalTableName = sourceTableName;
        }

        String schema = InformationSchemaNames.isVirtualInternalName(sourceTableName)
                ? InformationSchemaNames.SCHEMA_NAME
                : (session.currentDatabase() != null ? session.currentDatabase() : "");

        sources.put(visibleName.toUpperCase(), new SourceInfo(
                visibleName,
                sourceTableName,
                externalTableName,
                schema,
                validated.table(visibleName)
        ));
    }

    private static ColumnRef resolveColumnReference(String name, Map<String, SourceInfo> sources) {
        if (name.contains(".")) {
            String[] parts = name.split("\\.", 2);
            SourceInfo source = sources.get(parts[0].toUpperCase());
            if (source == null || source.tableMeta() == null) {
                return null;
            }
            ColumnMeta column = findColumn(source.tableMeta(), parts[1]);
            return column != null ? new ColumnRef(source, column) : null;
        }

        for (SourceInfo source : sources.values()) {
            if (source.tableMeta() == null) {
                continue;
            }
            ColumnMeta column = findColumn(source.tableMeta(), name);
            if (column != null) {
                return new ColumnRef(source, column);
            }
        }
        return null;
    }

    private static ColumnMeta findColumn(TableMeta tableMeta, String columnName) {
        for (ColumnMeta column : tableMeta.columns()) {
            if (column.name().equalsIgnoreCase(columnName)) {
                return column;
            }
        }
        return null;
    }

    private static SqlType inferType(SqlNode node, Map<String, SourceInfo> sources) {
        if (node == null || node.kind() == SqlKind.NULL_LITERAL || node.kind() == SqlKind.STAR) {
            return SqlType.VARCHAR;
        }
        if (node instanceof SqlAlias alias) {
            return inferType(alias.expression(), sources);
        }
        if (node instanceof SqlLiteral lit) {
            return lit.type();
        }
        if (node instanceof SqlIdentifier id) {
            ColumnRef ref = resolveColumnReference(id.name(), sources);
            return ref != null ? ref.column().type() : SqlType.VARCHAR;
        }
        if (node instanceof SqlCast cast) {
            return cast.targetType();
        }
        if (node instanceof SqlAggCall agg) {
            return aggregateType(agg, sources);
        }
        if (node instanceof SqlBinaryOp binOp) {
            SqlKind kind = binOp.kind();
            if (kind == SqlKind.ADD || kind == SqlKind.SUB || kind == SqlKind.MUL || kind == SqlKind.DIV) {
                return promoteNumeric(inferType(binOp.left(), sources), inferType(binOp.right(), sources));
            }
            return SqlType.INT32;
        }
        if (node instanceof SqlFunctionCall fn) {
            return functionType(fn, sources);
        }
        if (node instanceof SqlCase caseExpr) {
            return caseType(caseExpr, sources);
        }
        if (node instanceof SqlBetween || node instanceof SqlInList) {
            return SqlType.INT32;
        }
        if (node instanceof SqlWindowFunction window) {
            return windowType(window, sources);
        }
        return SqlType.VARCHAR;
    }

    private static SqlType aggregateType(SqlAggCall agg, Map<String, SourceInfo> sources) {
        String func = agg.funcName().toUpperCase();
        return switch (func) {
            case "COUNT" -> SqlType.BIGINT;
            case "SUM" -> promoteNumeric(inferType(agg.arg(), sources), inferType(agg.arg(), sources));
            case "AVG" -> SqlType.DECIMAL;
            case "MAX", "MIN" -> inferType(agg.arg(), sources);
            default -> SqlType.VARCHAR;
        };
    }

    private static SqlType functionType(SqlFunctionCall fn, Map<String, SourceInfo> sources) {
        String func = fn.functionName().toUpperCase();
        return switch (func) {
            case "UPPER", "LOWER", "SUBSTRING", "CONCAT", "TRIM", "COALESCE" -> SqlType.VARCHAR;
            default -> fn.arguments() != null && fn.arguments().size() > 0
                    ? inferType(fn.arguments().get(0), sources)
                    : SqlType.VARCHAR;
        };
    }

    private static SqlType caseType(SqlCase caseExpr, Map<String, SourceInfo> sources) {
        for (SqlCase.WhenThen whenThen : caseExpr.whenThens()) {
            SqlType type = inferType(whenThen.result(), sources);
            if (type != null) {
                return type;
            }
        }
        if (caseExpr.elseExpr() != null) {
            return inferType(caseExpr.elseExpr(), sources);
        }
        return SqlType.VARCHAR;
    }

    private static SqlType windowType(SqlWindowFunction window, Map<String, SourceInfo> sources) {
        String func = window.funcName().toUpperCase();
        return switch (func) {
            case "ROW_NUMBER", "RANK", "DENSE_RANK", "NTILE" -> SqlType.BIGINT;
            case "PERCENT_RANK", "CUME_DIST", "AVG" -> SqlType.DECIMAL;
            case "COUNT" -> SqlType.BIGINT;
            case "SUM", "MIN", "MAX" -> inferType(window.arg(), sources);
            default -> SqlType.VARCHAR;
        };
    }

    private static SqlType promoteNumeric(SqlType left, SqlType right) {
        if (left == SqlType.DECIMAL || right == SqlType.DECIMAL) {
            return SqlType.DECIMAL;
        }
        if (left == SqlType.BIGINT || right == SqlType.BIGINT) {
            return SqlType.BIGINT;
        }
        return SqlType.INT32;
    }

    private static String projectionLookupKey(SqlNode node) {
        if (node instanceof SqlAlias alias) {
            return alias.alias();
        }
        if (node instanceof SqlIdentifier id) {
            return id.name();
        }
        if (node instanceof SqlAggCall agg) {
            return aggKey(agg);
        }
        if (node instanceof SqlCast cast) {
            return castLabel(cast);
        }
        if (node instanceof SqlWindowFunction wf) {
            return wf.toString();
        }
        return String.valueOf(node);
    }

    private static String projectionDisplayName(SqlNode node) {
        SqlNode expression = unwrapAlias(node);
        if (node instanceof SqlAlias alias) {
            return alias.alias();
        }
        if (expression instanceof SqlIdentifier id) {
            return unqualify(id.name());
        }
        if (expression instanceof SqlAggCall agg) {
            return aggKey(agg);
        }
        if (expression instanceof SqlCast cast) {
            return castLabel(cast);
        }
        if (expression instanceof SqlWindowFunction wf) {
            return wf.toString();
        }
        return String.valueOf(expression);
    }

    private static String projectionOrgName(SqlNode node) {
        SqlNode expression = unwrapAlias(node);
        if (expression instanceof SqlIdentifier id) {
            return unqualify(id.name());
        }
        if (expression instanceof SqlAggCall agg) {
            return aggKey(agg);
        }
        if (expression instanceof SqlCast cast) {
            return castLabel(cast);
        }
        return projectionDisplayName(node);
    }

    private static int columnFlags(ColumnMeta column) {
        int flags = 0;
        if (column.isPrimaryKey()) {
            flags |= MysqlConstants.COLUMN_FLAG_PRI_KEY;
        }
        if (!column.nullable()) {
            flags |= MysqlConstants.COLUMN_FLAG_NOT_NULL;
        }
        return flags;
    }

    private static String displayTableName(SourceInfo source) {
        return source.visibleName().equalsIgnoreCase(source.sourceTableName())
                ? source.externalTableName()
                : source.visibleName();
    }

    private static SqlNode unwrapAlias(SqlNode node) {
        return node instanceof SqlAlias alias ? alias.expression() : node;
    }

    private static String unqualify(String name) {
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static String castLabel(SqlCast cast) {
        return "CAST(" + cast.expr() + " AS " + cast.targetType() + ")";
    }

    private static String aggKey(SqlAggCall agg) {
        if (agg.arg().kind() == SqlKind.STAR) {
            return agg.funcName().toUpperCase() + "(*)";
        }
        String argStr = agg.arg().toString().replaceAll("\\s+", "");
        return agg.funcName().toUpperCase() + "(" + argStr + ")";
    }

    private static List<ResultColumnMetadata> explainColumns() {
        return List.of(
                ResultColumnMetadata.of("ID", "", "", "", "ID", "ID", MysqlConstants.MYSQL_TYPE_LONG, 0),
                ResultColumnMetadata.of("OPERATOR", "", "", "", "OPERATOR", "OPERATOR",
                        MysqlConstants.MYSQL_TYPE_VAR_STRING, 0),
                ResultColumnMetadata.of("EST_ROWS", "", "", "", "EST_ROWS", "EST_ROWS",
                        MysqlConstants.MYSQL_TYPE_VAR_STRING, 0),
                ResultColumnMetadata.of("DETAILS", "", "", "", "DETAILS", "DETAILS",
                        MysqlConstants.MYSQL_TYPE_VAR_STRING, 0)
        );
    }

    private static List<ResultColumnMetadata> explainAnalyzeColumns() {
        return List.of(
                ResultColumnMetadata.of("ID", "", "", "", "ID", "ID", MysqlConstants.MYSQL_TYPE_LONG, 0),
                ResultColumnMetadata.of("OPERATOR", "", "", "", "OPERATOR", "OPERATOR",
                        MysqlConstants.MYSQL_TYPE_VAR_STRING, 0),
                ResultColumnMetadata.of("EST_ROWS", "", "", "", "EST_ROWS", "EST_ROWS",
                        MysqlConstants.MYSQL_TYPE_VAR_STRING, 0),
                ResultColumnMetadata.of("ACT_ROWS", "", "", "", "ACT_ROWS", "ACT_ROWS",
                        MysqlConstants.MYSQL_TYPE_LONGLONG, 0),
                ResultColumnMetadata.of("TIME_MS", "", "", "", "TIME_MS", "TIME_MS",
                        MysqlConstants.MYSQL_TYPE_VAR_STRING, 0)
        );
    }

    public record QueryMetadata(boolean resultSet, List<ResultColumnMetadata> columns) {
        public static QueryMetadata command() {
            return new QueryMetadata(false, List.of());
        }
    }

    private record SourceInfo(String visibleName, String sourceTableName,
                              String externalTableName, String schema,
                              TableMeta tableMeta) {
    }

    private record ColumnRef(SourceInfo source, ColumnMeta column) {
    }
}
