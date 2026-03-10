package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;

public class SqlValidator {
    private final CatalogSpi catalog;

    public SqlValidator(CatalogSpi catalog) {
        this.catalog = catalog;
    }

    public SqlSelect validate(SqlSelect select) {
        TableMeta table = catalog.getTable(select.table().name());
        if (table == null) {
            throw new ValidationException("Table '" + select.table().name() + "' not found");
        }

        validateProjection(select.projection(), table);
        if (select.where() != null) {
            validateCondition(select.where(), table);
        }

        return new ValidatedSqlSelect(select, table);
    }

    private void validateProjection(SqlNodeList projection, TableMeta table) {
        for (SqlNode node : projection.nodes()) {
            if (node.kind() == SqlKind.STAR) continue;
            if (node instanceof SqlIdentifier id) {
                if (!columnExists(id.name(), table)) {
                    throw new ValidationException("Column '" + id.name() + "' not found in " + table.name());
                }
            }
        }
    }

    private void validateCondition(SqlNode condition, TableMeta table) {
        // MVP: simple column = literal
        if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.BINARY_EQ) {
            if (binOp.left() instanceof SqlIdentifier col) {
                if (!columnExists(col.name(), table)) {
                    throw new ValidationException("Column '" + col.name() + "' not found");
                }
            }
        }
    }

    private boolean columnExists(String colName, TableMeta table) {
        return table.columns().stream().anyMatch(c -> c.name().equals(colName));
    }
}

record ValidatedSqlSelect(SqlSelect original, TableMeta tableMeta) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.SELECT; }
}

class ValidationException extends RuntimeException {
    public ValidationException(String message) { super(message); }
}