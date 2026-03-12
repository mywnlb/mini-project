package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

import java.util.LinkedHashSet;
import java.util.List;
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
            default -> throw new ValidationException("Unsupported statement: " + node.kind());
        };
    }

    // ==================== SELECT ====================

    private ValidatedSqlSelect validateSelect(SqlSelect select) {
        if (select.from() instanceof SqlJoin join) {
            return validateJoin(select, join);
        }

        SqlTableRef tableRef = asTableRef(select.from());
        TableMeta table = resolveTable(tableRef.tableName());
        List<TableScope> tables = List.of(new TableScope(tableRef.visibleName(), table));

        validateCommon(select, tables);
        return new ValidatedSqlSelect(select, table);
    }

    private ValidatedSqlSelect validateJoin(SqlSelect select, SqlJoin join) {
        SqlTableRef leftRef = asTableRef(join.left());
        SqlTableRef rightRef = asTableRef(join.right());
        TableMeta leftTable = resolveTable(leftRef.tableName());
        TableMeta rightTable = resolveTable(rightRef.tableName());

        List<TableScope> tables = List.of(
            new TableScope(leftRef.visibleName(), leftTable),
            new TableScope(rightRef.visibleName(), rightTable)
        );
        if (join.condition() != null) {
            validateCondition(join.condition(), tables);
        }
        validateCommon(select, tables);
        return new ValidatedSqlSelect(select, leftTable, rightTable);
    }

    private void validateCommon(SqlSelect select, List<TableScope> tables) {
        Set<String> projectionAliases = projectionAliases(select.projection());
        validateProjection(select.projection(), tables);
        if (select.where() != null) validateCondition(select.where(), tables);
        if (select.groupBy() != null) validateGroupBy(select, tables);
        if (select.having() != null) validateCondition(select.having(), tables);
        if (select.orderBy() != null) validateOrderBy(select.orderBy(), tables, projectionAliases);
    }

    // ==================== INSERT ====================

    private ValidatedDml validateInsert(SqlInsert insert) {
        TableMeta table = resolveTable(insert.table().name());
        List<TableScope> tables = List.of(new TableScope(table.name(), table));

        // 校验列名
        if (insert.columns().size() > 0) {
            for (SqlNode col : insert.columns().nodes()) {
                if (col instanceof SqlIdentifier id) {
                    if (!columnExists(id.name(), tables)) {
                        throw new ValidationException("Column '" + id.name() + "' not found in table '" + table.name() + "'");
                    }
                }
            }
        }

        // 校验每行值的数量
        int expectedCols = insert.columns().size() > 0 ? insert.columns().size() : table.columns().size();
        for (SqlNode row : insert.valueRows().nodes()) {
            if (row instanceof SqlNodeList rowList) {
                if (rowList.size() != expectedCols) {
                    throw new ValidationException("VALUES row has " + rowList.size()
                        + " values, expected " + expectedCols);
                }
            }
        }

        return new ValidatedDml(insert, table);
    }

    // ==================== UPDATE ====================

    private ValidatedDml validateUpdate(SqlUpdate update) {
        TableMeta table = resolveTable(update.table().name());
        List<TableScope> tables = List.of(new TableScope(table.name(), table));

        // 校验 SET 子句中的列名
        for (SqlNode node : update.assignments().nodes()) {
            if (node instanceof SqlAssignment assign) {
                if (!columnExists(assign.column().name(), tables)) {
                    throw new ValidationException("Column '" + assign.column().name() + "' not found in table '" + table.name() + "'");
                }
            }
        }

        if (update.where() != null) {
            validateCondition(update.where(), tables);
        }

        return new ValidatedDml(update, table);
    }

    // ==================== DELETE ====================

    private ValidatedDml validateDelete(SqlDelete delete) {
        TableMeta table = resolveTable(delete.table().name());
        List<TableScope> tables = List.of(new TableScope(table.name(), table));

        if (delete.where() != null) {
            validateCondition(delete.where(), tables);
        }

        return new ValidatedDml(delete, table);
    }

    // ==================== CREATE TABLE ====================

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

    // ==================== DROP TABLE ====================

    private SqlDropTable validateDropTable(SqlDropTable drop) {
        String tableName = drop.table().name();
        if (!drop.ifExists() && !catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' does not exist");
        }
        return drop;
    }

    // ==================== ALTER TABLE ====================

    private SqlAlterTable validateAlterTable(SqlAlterTable alter) {
        String tableName = alter.table().name();
        if (!catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' does not exist");
        }
        TableMeta table = catalog.getTable(tableName);
        boolean exists = table.columns().stream()
            .anyMatch(c -> c.name().equalsIgnoreCase(alter.columnName()));
        if (exists) {
            throw new ValidationException("Column '" + alter.columnName() + "' already exists in table '" + tableName + "'");
        }
        return alter;
    }

    // ==================== CREATE INDEX ====================

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

    // ==================== DROP INDEX ====================

    private SqlDropIndex validateDropIndex(SqlDropIndex dropIdx) {
        String tableName = dropIdx.table().name();
        if (!catalog.tableExists(tableName)) {
            throw new ValidationException("Table '" + tableName + "' does not exist");
        }
        return dropIdx;
    }

    // ==================== 通用校验 ====================

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
            if (expression instanceof SqlAggCall agg) {
                validateAggCall(agg, tables);
            } else if (expression instanceof SqlIdentifier id) {
                if (!columnExists(id.name(), tables)) {
                    throw new ValidationException("Column '" + id.name() + "' not found");
                }
            }
        }
    }

    private void validateAggCall(SqlAggCall agg, List<TableScope> tables) {
        if (agg.arg().kind() == SqlKind.STAR) return;
        if (agg.arg() instanceof SqlIdentifier id) {
            if (!columnExists(id.name(), tables)) {
                throw new ValidationException("Column '" + id.name() + "' in " + agg.funcName() + "() not found");
            }
        }
    }

    private void validateGroupBy(SqlSelect select, List<TableScope> tables) {
        for (SqlNode node : select.groupBy().nodes()) {
            if (node instanceof SqlIdentifier id) {
                if (!columnExists(id.name(), tables)) {
                    throw new ValidationException("GROUP BY column '" + id.name() + "' not found");
                }
            }
        }
        for (SqlNode node : select.projection().nodes()) {
            SqlNode expression = unwrapAlias(node);
            if (expression.kind() == SqlKind.STAR) {
                throw new ValidationException("SELECT * not allowed with GROUP BY");
            }
            if (expression instanceof SqlIdentifier id) {
                if (!inGroupBy(id.name(), select.groupBy())) {
                    throw new ValidationException(
                        "Column '" + id.name() + "' must appear in GROUP BY or be in an aggregate function");
                }
            }
        }
    }

    private void validateOrderBy(SqlNodeList orderBy, List<TableScope> tables, Set<String> projectionAliases) {
        for (SqlNode node : orderBy.nodes()) {
            SqlNode expression = unwrapAlias(node);
            if (expression instanceof SqlIdentifier id) {
                if (projectionAliases.contains(id.name().toUpperCase())) {
                    continue;
                }
                if (!columnExists(id.name(), tables)) {
                    throw new ValidationException("ORDER BY column '" + id.name() + "' not found");
                }
            }
        }
    }

    private boolean inGroupBy(String colName, SqlNodeList groupBy) {
        return groupBy.nodes().stream()
            .filter(n -> n instanceof SqlIdentifier)
            .map(n -> ((SqlIdentifier) n).name())
            .anyMatch(name -> name.equalsIgnoreCase(colName));
    }

    private void validateCondition(SqlNode condition, List<TableScope> tables) {
        if (condition instanceof SqlBinaryOp binOp) {
            SqlKind kind = binOp.kind();
            if (kind == SqlKind.AND || kind == SqlKind.OR) {
                validateCondition(binOp.left(), tables);
                validateCondition(binOp.right(), tables);
            } else {
                validateColumnRef(binOp.left(), tables);
                validateColumnRef(binOp.right(), tables);
            }
        }
    }

    private void validateColumnRef(SqlNode node, List<TableScope> tables) {
        SqlNode expression = unwrapAlias(node);
        if (expression instanceof SqlIdentifier id) {
            if (!columnExists(id.name(), tables)) {
                throw new ValidationException("Column '" + id.name() + "' not found");
            }
        }
        if (expression instanceof SqlAggCall agg) {
            validateAggCall(agg, tables);
        }
    }

    private boolean columnExists(String colName, List<TableScope> tables) {
        if (colName.contains(".")) {
            String[] parts = colName.split("\\.", 2);
            String visibleTableName = parts[0];
            String col = parts[1];
            return tables.stream()
                .filter(t -> t.visibleName().equalsIgnoreCase(visibleTableName))
                .anyMatch(t -> t.table().columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(col)));
        }
        return tables.stream()
            .anyMatch(t -> t.table().columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(colName)));
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
}

class ValidationException extends RuntimeException {
    public ValidationException(String message) { super(message); }
}

record TableScope(String visibleName, TableMeta table) {}
