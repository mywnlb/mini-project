package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.types.SqlType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SqlValidator {
    private final CatalogSpi catalog;

    public SqlValidator(CatalogSpi catalog) {
        this.catalog = catalog;
    }

    public SqlNode validate(SqlNode node) {
        return switch (node) {
            case SqlSelect select -> validateSelect(select);
            case SqlInsert insert -> validateInsert(insert);
            case SqlUpdate update -> validateUpdate(update);
            case SqlDelete delete -> validateDelete(delete);
            case SqlCreateTable create -> validateCreateTable(create);
            case SqlDropTable drop -> validateDropTable(drop);
            case SqlAlterTable alter -> validateAlterTable(alter);
            case SqlCreateIndex createIdx -> validateCreateIndex(createIdx);
            case SqlDropIndex dropIdx -> validateDropIndex(dropIdx);
            case SqlSetOperation setOp -> validateSetOperation(setOp);
            default -> throw new ValidationException("Unsupported statement: " + node.kind());
        };
    }

    private ValidatedSqlSelect validateSelect(SqlSelect select) {
        Map<String, TableMeta> tables = new LinkedHashMap<>();
        if (select.from() != null) {
            validateFrom(select.from(), tables);
        }
        List<TableScope> scopes = tableScopes(tables);
        validateCommon(select, scopes);
        return new ValidatedSqlSelect(select, tables);
    }

    private void validateFrom(SqlNode from, Map<String, TableMeta> tables) {
        if (from instanceof SqlJoin join) {
            validateFrom(join.left(), tables);
            validateFrom(join.right(), tables);
            if (join.condition() != null) {
                validateCondition(join.condition(), tableScopes(tables));
            }
            return;
        }

        if (from instanceof SqlDerivedTable derived) {
            // 递归验证内层 SELECT
            validateSelect(derived.select());
            // 从内层 projection 推导虚拟 TableMeta
            String alias = derived.alias().toUpperCase();
            if (tables.containsKey(alias)) {
                throw new ValidationException("Duplicate table alias '" + derived.alias() + "'");
            }
            TableMeta virtualMeta = derivedTableMeta(derived);
            tables.put(alias, virtualMeta);
            return;
        }

        SqlTableRef ref = asTableRef(from);
        String visibleName = ref.visibleName().toUpperCase();
        if (tables.containsKey(visibleName)) {
            throw new ValidationException("Duplicate table alias '" + ref.visibleName() + "'");
        }
        tables.put(visibleName, resolveTable(ref.tableName()));
    }

    /**
     * 从派生表的内层 SELECT projection 推导虚拟 TableMeta
     */
    private TableMeta derivedTableMeta(SqlDerivedTable derived) {
        SqlSelect inner = derived.select();
        List<ColumnMeta> columns = new ArrayList<>();

        if (inner.projection().size() == 1 && inner.projection().get(0).kind() == SqlKind.STAR) {
            // SELECT * — 从内层 FROM 的所有表获取列
            Map<String, TableMeta> innerTables = new LinkedHashMap<>();
            collectFromTables(inner.from(), innerTables);
            for (TableMeta tm : innerTables.values()) {
                for (ColumnMeta cm : tm.columns()) {
                    columns.add(new ColumnMeta(cm.name(), cm.type(), cm.isPrimaryKey()));
                }
            }
        } else {
            for (SqlNode node : inner.projection().nodes()) {
                String colName = deriveColumnName(node);
                columns.add(new ColumnMeta(colName, SqlType.VARCHAR, false));
            }
        }

        return TableMeta.of(derived.alias(), columns, 0);
    }

    /**
     * 从 FROM 子句收集所有物理表（用于 SELECT * 列推导）
     */
    private void collectFromTables(SqlNode from, Map<String, TableMeta> tables) {
        if (from instanceof SqlJoin join) {
            collectFromTables(join.left(), tables);
            collectFromTables(join.right(), tables);
            return;
        }
        if (from instanceof SqlDerivedTable derived) {
            tables.put(derived.alias().toUpperCase(), derivedTableMeta(derived));
            return;
        }
        SqlTableRef ref = asTableRef(from);
        tables.put(ref.visibleName().toUpperCase(), resolveTable(ref.tableName()));
    }

    /**
     * 推导 projection 项的输出列名
     */
    private String deriveColumnName(SqlNode node) {
        if (node instanceof SqlAlias alias) {
            return alias.alias();
        }
        if (node instanceof SqlIdentifier id) {
            String name = id.name();
            // table.column → column
            return name.contains(".") ? name.substring(name.indexOf('.') + 1) : name;
        }
        if (node instanceof SqlAggCall agg) {
            return agg.funcName() + "(" + agg.arg() + ")";
        }
        return node.toString();
    }

    private void validateCommon(SqlSelect select, List<TableScope> tables) {
        Set<String> projectionAliases = projectionAliases(select.projection());
        validateProjection(select.projection(), tables);
        if (select.where() != null) validateCondition(select.where(), tables);
        if (select.groupBy() != null) validateGroupBy(select, tables);
        if (select.having() != null) validateCondition(select.having(), tables);
        if (select.orderBy() != null) validateOrderBy(select.orderBy(), tables, projectionAliases);
    }

    private ValidatedDml validateInsert(SqlInsert insert) {
        TableMeta table = resolveTable(insert.table().name());
        List<TableScope> tables = List.of(new TableScope(table.name(), table));

        if (insert.columns().size() > 0) {
            for (SqlNode col : insert.columns().nodes()) {
                if (col instanceof SqlIdentifier id && !columnExists(id.name(), tables)) {
                    throw new ValidationException(
                        "Column '" + id.name() + "' not found in table '" + table.name() + "'");
                }
            }
        }

        int expectedCols = insert.columns().size() > 0 ? insert.columns().size() : table.columns().size();
        for (SqlNode row : insert.valueRows().nodes()) {
            SqlNodeList rowList = (SqlNodeList) row;
            if (rowList.size() != expectedCols) {
                throw new ValidationException("VALUES row has " + rowList.size()
                    + " values, expected " + expectedCols);
            }
            for (SqlNode value : rowList.nodes()) {
                validateExpression(value, tables);
            }
        }

        return new ValidatedDml(insert, table);
    }

    private ValidatedDml validateUpdate(SqlUpdate update) {
        TableMeta table = resolveTable(update.table().name());
        List<TableScope> tables = List.of(new TableScope(table.name(), table));

        for (SqlNode node : update.assignments().nodes()) {
            SqlAssignment assign = (SqlAssignment) node;
            if (!columnExists(assign.column().name(), tables)) {
                throw new ValidationException(
                    "Column '" + assign.column().name() + "' not found in table '" + table.name() + "'");
            }
            validateExpression(assign.value(), tables);
        }

        if (update.where() != null) {
            validateCondition(update.where(), tables);
        }

        return new ValidatedDml(update, table);
    }

    private ValidatedDml validateDelete(SqlDelete delete) {
        TableMeta table = resolveTable(delete.table().name());
        List<TableScope> tables = List.of(new TableScope(table.name(), table));

        if (delete.where() != null) {
            validateCondition(delete.where(), tables);
        }

        return new ValidatedDml(delete, table);
    }

    private SqlCreateTable validateCreateTable(SqlCreateTable create) {
        String tableName = create.table().name();
        if (!create.ifNotExists() && catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' already exists");
        }
        if (create.columnDefs().isEmpty()) {
            throw new ValidationException("CREATE TABLE must have at least one column");
        }
        return create;
    }

    private SqlDropTable validateDropTable(SqlDropTable drop) {
        String tableName = drop.table().name();
        if (!drop.ifExists() && !catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' does not exist");
        }
        return drop;
    }

    private SqlAlterTable validateAlterTable(SqlAlterTable alter) {
        String tableName = alter.table().name();
        if (!catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' does not exist");
        }
        TableMeta table = catalog.getTable(tableName);
        boolean exists = table.columns().stream()
            .anyMatch(c -> c.name().equalsIgnoreCase(alter.columnName()));
        if (exists) {
            throw new ValidationException(
                "Column '" + alter.columnName() + "' already exists in table '" + tableName + "'");
        }
        return alter;
    }

    private SqlCreateIndex validateCreateIndex(SqlCreateIndex createIdx) {
        String tableName = createIdx.table().name();
        if (!catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' does not exist");
        }
        TableMeta table = catalog.getTable(tableName);
        List<TableScope> tables = List.of(new TableScope(table.name(), table));
        for (String col : createIdx.columns()) {
            if (!columnExists(col, tables)) {
                throw new ValidationException("Column '" + col + "' not found in table '" + tableName + "'");
            }
        }
        return createIdx;
    }

    private SqlDropIndex validateDropIndex(SqlDropIndex dropIdx) {
        String tableName = dropIdx.table().name();
        if (!catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' does not exist");
        }
        return dropIdx;
    }

    private TableMeta resolveTable(String name) {
        TableMeta table = catalog.getTable(name);
        if (table == null) {
            throw new ValidationException("Table '" + name + "' not found");
        }
        return table;
    }

    private void validateProjection(SqlNodeList projection, List<TableScope> tables) {
        for (SqlNode node : projection.nodes()) {
            SqlNode expression = unwrapAlias(node);
            if (expression.kind() == SqlKind.STAR) continue;
            validateExpression(expression, tables);
        }
    }

    private void validateAggCall(SqlAggCall agg, List<TableScope> tables) {
        if (agg.arg().kind() == SqlKind.STAR) return;
        validateExpression(agg.arg(), tables);
    }

    private void validateGroupBy(SqlSelect select, List<TableScope> tables) {
        Set<String> groupSignatures = new LinkedHashSet<>();
        for (SqlNode node : select.groupBy().nodes()) {
            validateExpression(node, tables);
            groupSignatures.add(expressionSignature(node));
        }

        for (SqlNode node : select.projection().nodes()) {
            SqlNode expression = unwrapAlias(node);
            if (expression.kind() == SqlKind.STAR) {
                throw new ValidationException("SELECT * not allowed with GROUP BY");
            }
            if (isLiteral(expression) || containsAggCall(expression)) {
                continue;
            }
            if (!groupSignatures.contains(expressionSignature(expression))) {
                throw new ValidationException(
                    "Expression '" + expression + "' must appear in GROUP BY or be aggregated");
            }
        }
    }

    private void validateOrderBy(SqlNodeList orderBy, List<TableScope> tables, Set<String> projectionAliases) {
        for (SqlNode node : orderBy.nodes()) {
            SqlNode expression = orderByExpression(node);
            if (expression instanceof SqlIdentifier id
                && projectionAliases.contains(id.name().toUpperCase())) {
                continue;
            }
            validateExpression(expression, tables);
        }
    }

    private void validateCondition(SqlNode condition, List<TableScope> tables) {
        validateExpression(condition, tables);
    }

    private void validateExpression(SqlNode node, List<TableScope> tables) {
        SqlNode expression = unwrapAlias(node);
        if (expression == null || expression.kind() == SqlKind.NULL_LITERAL
            || expression.kind() == SqlKind.STAR || expression instanceof SqlLiteral) {
            return;
        }
        if (expression instanceof SqlIdentifier id) {
            if (!columnExists(id.name(), tables)) {
                throw new ValidationException("Column '" + id.name() + "' not found");
            }
            return;
        }
        if (expression instanceof SqlAggCall agg) {
            validateAggCall(agg, tables);
            return;
        }
        if (expression instanceof SqlOrderByItem item) {
            validateExpression(item.column(), tables);
            return;
        }
        if (expression instanceof SqlBinaryOp binOp) {
            validateExpression(binOp.left(), tables);
            validateExpression(binOp.right(), tables);
            return;
        }
        if (expression instanceof SqlBetween between) {
            validateExpression(between.expr(), tables);
            validateExpression(between.low(), tables);
            validateExpression(between.high(), tables);
            return;
        }
        if (expression instanceof SqlInList inList) {
            validateExpression(inList.expr(), tables);
            for (SqlNode value : inList.values().nodes()) {
                validateExpression(value, tables);
            }
            return;
        }
        if (expression instanceof SqlSubquery subquery) {
            ValidatedSqlSelect validated = validateSelect(subquery.select());
            // 标量子查询必须只有一列
            SqlNodeList proj = subquery.select().projection();
            if (proj.size() != 1 || proj.get(0).kind() == SqlKind.STAR) {
                throw new ValidationException(
                    "Scalar subquery must return exactly one column");
            }
            return;
        }
        if (expression instanceof SqlExists exists) {
            validateSelect(exists.select());
            return;
        }
        if (expression instanceof SqlInSubquery inSub) {
            validateExpression(inSub.expr(), tables);
            validateSelect(inSub.select());
            // IN 子查询必须返回单列
            SqlNodeList proj = inSub.select().projection();
            if (proj.size() != 1 || proj.get(0).kind() == SqlKind.STAR) {
                throw new ValidationException(
                    "IN subquery must return exactly one column");
            }
            return;
        }
    }

    private boolean containsAggCall(SqlNode node) {
        SqlNode expression = unwrapAlias(node);
        if (expression instanceof SqlSubquery) return false; // 子查询内部的 agg 不属于外层
        if (expression instanceof SqlAggCall) {
            return true;
        }
        if (expression instanceof SqlBinaryOp binOp) {
            return containsAggCall(binOp.left()) || containsAggCall(binOp.right());
        }
        if (expression instanceof SqlBetween between) {
            return containsAggCall(between.expr())
                || containsAggCall(between.low())
                || containsAggCall(between.high());
        }
        if (expression instanceof SqlInList inList) {
            if (containsAggCall(inList.expr())) {
                return true;
            }
            for (SqlNode value : inList.values().nodes()) {
                if (containsAggCall(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLiteral(SqlNode node) {
        SqlNode expression = unwrapAlias(node);
        return expression instanceof SqlLiteral || expression.kind() == SqlKind.NULL_LITERAL;
    }

    private boolean columnExists(String colName, List<TableScope> tables) {
        if (colName.contains(".")) {
            String[] parts = colName.split("\\.", 2);
            String visibleTableName = parts[0];
            String col = parts[1];
            boolean found = tables.stream()
                .filter(t -> t.visibleName().equalsIgnoreCase(visibleTableName))
                .anyMatch(t -> t.table().columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(col)));
            if (found) return true;
            // fallback for correlated subquery: treat as unqualified column name
            colName = col;
        }
        String lookupCol = colName;
        boolean foundInScope = tables.stream()
            .anyMatch(t -> t.table().columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(lookupCol)));
        if (foundInScope) return true;
        // global catalog fallback for correlated subqueries (users.id in orders subquery)
        for (String tname : catalog.listTables(null)) {
            TableMeta tm = catalog.getTable(tname);
            if (tm != null && tm.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(lookupCol))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> projectionAliases(SqlNodeList projection) {
        Set<String> aliases = new LinkedHashSet<>();
        for (SqlNode node : projection.nodes()) {
            if (node instanceof SqlAlias alias) {
                aliases.add(alias.alias().toUpperCase());
            }
        }
        return aliases;
    }

    private List<TableScope> tableScopes(Map<String, TableMeta> tables) {
        return tables.entrySet().stream()
            .map(entry -> new TableScope(entry.getKey(), entry.getValue()))
            .toList();
    }

    private SqlTableRef asTableRef(SqlNode node) {
        if (node instanceof SqlTableRef ref) {
            return ref;
        }
        if (node instanceof SqlIdentifier id) {
            return new SqlTableRef(id.name(), null);
        }
        throw new ValidationException("Expected table reference, got " + node);
    }

    private SqlNode unwrapAlias(SqlNode node) {
        return node instanceof SqlAlias alias ? alias.expression() : node;
    }

    private SqlNode orderByExpression(SqlNode node) {
        if (node instanceof SqlOrderByItem item) {
            return item.column();
        }
        return unwrapAlias(node);
    }

    private SqlSetOperation validateSetOperation(SqlSetOperation setOp) {
        validate(setOp.left());
        validate(setOp.right());
        return setOp;
    }

    private String expressionSignature(SqlNode node) {
        return String.valueOf(unwrapAlias(node));
    }
}

class ValidationException extends RuntimeException {
    public ValidationException(String message) { super(message); }
}

record TableScope(String visibleName, TableMeta table) {}
