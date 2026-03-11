package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

import java.util.List;

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
            default -> throw new ValidationException("Unsupported statement: " + node.kind());
        };
    }

    // ==================== SELECT ====================

    private ValidatedSqlSelect validateSelect(SqlSelect select) {
        if (select.from() instanceof SqlJoin join) {
            return validateJoin(select, join);
        }

        SqlIdentifier tableId = (SqlIdentifier) select.from();
        TableMeta table = resolveTable(tableId.name());
        List<TableMeta> tables = List.of(table);

        validateCommon(select, tables);
        return new ValidatedSqlSelect(select, table);
    }

    private ValidatedSqlSelect validateJoin(SqlSelect select, SqlJoin join) {
        SqlIdentifier leftId = (SqlIdentifier) join.left();
        SqlIdentifier rightId = (SqlIdentifier) join.right();
        TableMeta leftTable = resolveTable(leftId.name());
        TableMeta rightTable = resolveTable(rightId.name());

        List<TableMeta> tables = List.of(leftTable, rightTable);
        if (join.condition() != null) {
            validateCondition(join.condition(), tables);
        }
        validateCommon(select, tables);
        return new ValidatedSqlSelect(select, leftTable, rightTable);
    }

    private void validateCommon(SqlSelect select, List<TableMeta> tables) {
        validateProjection(select.projection(), tables);
        if (select.where() != null) validateCondition(select.where(), tables);
        if (select.groupBy() != null) validateGroupBy(select, tables);
        if (select.having() != null) validateCondition(select.having(), tables);
        if (select.orderBy() != null) validateOrderBy(select.orderBy(), tables);
    }

    // ==================== INSERT ====================

    private ValidatedDml validateInsert(SqlInsert insert) {
        TableMeta table = resolveTable(insert.table().name());
        List<TableMeta> tables = List.of(table);

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
        List<TableMeta> tables = List.of(table);

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
        List<TableMeta> tables = List.of(table);

        if (delete.where() != null) {
            validateCondition(delete.where(), tables);
        }

        return new ValidatedDml(delete, table);
    }

    // ==================== 通用校验 ====================

    private TableMeta resolveTable(String name) {
        TableMeta table = catalog.getTable(name);
        if (table == null) {
            throw new ValidationException("Table '" + name + "' not found");
        }
        return table;
    }

    private void validateProjection(SqlNodeList projection, List<TableMeta> tables) {
        for (SqlNode node : projection.nodes()) {
            if (node.kind() == SqlKind.STAR) continue;
            if (node instanceof SqlAggCall agg) {
                validateAggCall(agg, tables);
            } else if (node instanceof SqlIdentifier id) {
                if (!columnExists(id.name(), tables)) {
                    throw new ValidationException("Column '" + id.name() + "' not found");
                }
            }
        }
    }

    private void validateAggCall(SqlAggCall agg, List<TableMeta> tables) {
        if (agg.arg().kind() == SqlKind.STAR) return;
        if (agg.arg() instanceof SqlIdentifier id) {
            if (!columnExists(id.name(), tables)) {
                throw new ValidationException("Column '" + id.name() + "' in " + agg.funcName() + "() not found");
            }
        }
    }

    private void validateGroupBy(SqlSelect select, List<TableMeta> tables) {
        for (SqlNode node : select.groupBy().nodes()) {
            if (node instanceof SqlIdentifier id) {
                if (!columnExists(id.name(), tables)) {
                    throw new ValidationException("GROUP BY column '" + id.name() + "' not found");
                }
            }
        }
        for (SqlNode node : select.projection().nodes()) {
            if (node.kind() == SqlKind.STAR) {
                throw new ValidationException("SELECT * not allowed with GROUP BY");
            }
            if (node instanceof SqlIdentifier id) {
                if (!inGroupBy(id.name(), select.groupBy())) {
                    throw new ValidationException(
                        "Column '" + id.name() + "' must appear in GROUP BY or be in an aggregate function");
                }
            }
        }
    }

    private void validateOrderBy(SqlNodeList orderBy, List<TableMeta> tables) {
        for (SqlNode node : orderBy.nodes()) {
            if (node instanceof SqlIdentifier id) {
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

    private void validateCondition(SqlNode condition, List<TableMeta> tables) {
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

    private void validateColumnRef(SqlNode node, List<TableMeta> tables) {
        if (node instanceof SqlIdentifier id) {
            if (!columnExists(id.name(), tables)) {
                throw new ValidationException("Column '" + id.name() + "' not found");
            }
        }
        if (node instanceof SqlAggCall agg) {
            validateAggCall(agg, tables);
        }
    }

    private boolean columnExists(String colName, List<TableMeta> tables) {
        if (colName.contains(".")) {
            String[] parts = colName.split("\\.", 2);
            String tableName = parts[0];
            String col = parts[1];
            return tables.stream()
                .filter(t -> t.name().equalsIgnoreCase(tableName))
                .anyMatch(t -> t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(col)));
        }
        return tables.stream()
            .anyMatch(t -> t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(colName)));
    }
}

class ValidationException extends RuntimeException {
    public ValidationException(String message) { super(message); }
}
