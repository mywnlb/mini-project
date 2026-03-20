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
            case SqlInsertSelect insertSelect -> validateInsertSelect(insertSelect);
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
            if (join.joinType() != cn.zhangyis.minidb.sql.ast.JoinType.CROSS && join.condition() == null) {
                throw new ValidationException("Non-CROSS JOIN must have ON condition");
            }
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
            // 从内层 FROM 构建表作用域，用于类型推断
            Map<String, TableMeta> innerTables = new LinkedHashMap<>();
            collectFromTables(inner.from(), innerTables);
            List<TableScope> innerScopes = tableScopes(innerTables);
            for (SqlNode node : inner.projection().nodes()) {
                String colName = deriveColumnName(node);
                SqlType type = inferType(node, innerScopes);
                if (type == null) type = SqlType.VARCHAR; // fallback
                columns.add(new ColumnMeta(colName, type, false));
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

        // 禁止 WHERE 中使用聚合函数
        if (select.where() != null) {
            rejectAggInWhere(select.where());
            validateCondition(select.where(), tables);
        }

        boolean hasGroupBy = select.groupBy() != null;
        boolean hasAgg = containsAggCallInList(select.projection())
                      || (select.having() != null && containsAggCall(select.having()));

        if (hasGroupBy) {
            validateGroupBy(select, tables);
        } else if (hasAgg) {
            // 隐式聚合 — SELECT 有 agg 但无 GROUP BY
            validateImplicitAggregation(select, tables);
        }

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

    private ValidatedDml validateInsertSelect(SqlInsertSelect insertSelect) {
        TableMeta table = resolveTable(insertSelect.table().name());

        // 验证列名存在性
        if (insertSelect.columns().size() > 0) {
            List<TableScope> tables = List.of(new TableScope(table.name(), table));
            for (SqlNode col : insertSelect.columns().nodes()) {
                if (col instanceof SqlIdentifier id && !columnExists(id.name(), tables)) {
                    throw new ValidationException(
                        "Column '" + id.name() + "' not found in table '" + table.name() + "'");
                }
            }
        }

        // 验证 SELECT 子查询
        validateSelect(insertSelect.select());

        // 验证列数匹配
        int expectedCols = insertSelect.columns().size() > 0
                ? insertSelect.columns().size()
                : table.columns().size();
        int selectCols = insertSelect.select().projection().size();
        // SELECT * 时无法在 validation 阶段确定列数，跳过检查
        boolean hasStar = insertSelect.select().projection().nodes().stream()
                .anyMatch(n -> n instanceof SqlStar);
        if (!hasStar && selectCols != expectedCols) {
            throw new ValidationException("SELECT has " + selectCols
                + " columns, expected " + expectedCols);
        }

        return new ValidatedDml(insertSelect, table);
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
        // INV-7: Instant DDL requires nullable column or explicit DEFAULT value
        if (!alter.nullable() && alter.defaultValue() == null) {
            throw new ValidationException(
                "Instant DDL requires nullable column or explicit DEFAULT value");
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
        // 拒绝嵌套聚合: COUNT(SUM(x))
        if (containsNestedAgg(agg.arg())) {
            throw new ValidationException("Aggregate functions cannot be nested");
        }
        validateExpression(agg.arg(), tables);
    }

    private void validateGroupBy(SqlSelect select, List<TableScope> tables) {
        Set<String> groupSignatures = new LinkedHashSet<>();
        for (SqlNode node : select.groupBy().nodes()) {
            validateExpression(node, tables);
            groupSignatures.add(expressionSignature(node));
        }

        // 检查 SELECT 列表
        for (SqlNode node : select.projection().nodes()) {
            SqlNode expression = unwrapAlias(node);
            if (expression.kind() == SqlKind.STAR) {
                throw new ValidationException("SELECT * not allowed with GROUP BY");
            }
            if (isLiteral(expression)) continue;
            // 完整签名匹配 GROUP BY（如 SELECT a+b ... GROUP BY a+b）
            if (groupSignatures.contains(expressionSignature(expression))) continue;
            // 提取非聚合列引用，逐个检查是否在 GROUP BY 中
            validateColumnsInGroupBy(expression, groupSignatures);
        }

        // 检查 HAVING 中非聚合列
        if (select.having() != null) {
            validateColumnsInGroupBy(select.having(), groupSignatures);
        }

        // 检查 ORDER BY 中非聚合列
        if (select.orderBy() != null) {
            Set<String> projectionAliases = projectionAliases(select.projection());
            for (SqlNode node : select.orderBy().nodes()) {
                SqlNode expr = orderByExpression(node);
                if (expr instanceof SqlIdentifier id
                    && projectionAliases.contains(id.name().toUpperCase())) continue;
                if (isLiteral(expr)) continue;
                if (groupSignatures.contains(expressionSignature(expr))) continue;
                validateColumnsInGroupBy(expr, groupSignatures);
            }
        }
    }

    private void validateColumnsInGroupBy(SqlNode expr, Set<String> groupSignatures) {
        List<SqlIdentifier> nonAggCols = collectNonAggColumns(expr);
        for (SqlIdentifier col : nonAggCols) {
            if (!groupSignatures.contains(expressionSignature(col))) {
                throw new ValidationException(
                    "Column '" + col.name() + "' must appear in GROUP BY or be aggregated");
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
            // 类型检查
            SqlKind k = binOp.kind();
            if (k == SqlKind.ADD || k == SqlKind.SUB || k == SqlKind.MUL || k == SqlKind.DIV) {
                checkArithmeticTypes(binOp.left(), binOp.right(), tables);
            }
            if (k == SqlKind.BINARY_EQ || k == SqlKind.BINARY_NE || k == SqlKind.BINARY_LT
                || k == SqlKind.BINARY_GT || k == SqlKind.BINARY_LE || k == SqlKind.BINARY_GE) {
                checkComparisonTypes(binOp.left(), binOp.right(), tables);
            }
            return;
        }
        if (expression instanceof SqlBetween between) {
            validateExpression(between.expr(), tables);
            validateExpression(between.low(), tables);
            validateExpression(between.high(), tables);
            checkBetweenTypes(between, tables);
            return;
        }
        if (expression instanceof SqlInList inList) {
            validateExpression(inList.expr(), tables);
            for (SqlNode value : inList.values().nodes()) {
                validateExpression(value, tables);
            }
            checkInListTypes(inList, tables);
            return;
        }
        if (expression instanceof SqlCase caseExpr) {
            for (SqlCase.WhenThen wt : caseExpr.whenThens()) {
                validateExpression(wt.condition(), tables);
                validateExpression(wt.result(), tables);
            }
            if (caseExpr.elseExpr() != null) validateExpression(caseExpr.elseExpr(), tables);
            checkCaseBranchTypes(caseExpr, tables);
            return;
        }
        if (expression instanceof SqlFunctionCall fc) {
            for (SqlNode arg : fc.arguments().nodes()) {
                validateExpression(arg, tables);
            }
            checkFunctionArgTypes(fc, tables);
            return;
        }
        if (expression instanceof SqlCast cast) {
            validateExpression(cast.expr(), tables);
            return;
        }
        if (expression instanceof SqlWindowFunction wf) {
            if (wf.partitionBy() != null) {
                for (SqlNode pNode : wf.partitionBy().nodes()) {
                    validateExpression(pNode, tables);
                }
            }
            if (wf.orderBy() != null) {
                for (SqlNode oNode : wf.orderBy().nodes()) {
                    validateExpression(oNode, tables);
                }
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

    // ==================== 聚合语义校验辅助方法 ====================

    /** 遍历 SqlNodeList 中每个元素检查是否包含聚合 */
    private boolean containsAggCallInList(SqlNodeList list) {
        for (SqlNode node : list.nodes()) {
            if (containsAggCall(node)) return true;
        }
        return false;
    }

    /**
     * 收集表达式中所有不在 SqlAggCall 内部的列引用。
     * 遇到 SqlAggCall 停止下降（其参数属于聚合内部）。
     * 遇到 SqlSubquery 也停止（子查询列属于内层作用域）。
     */
    private List<SqlIdentifier> collectNonAggColumns(SqlNode node) {
        List<SqlIdentifier> result = new ArrayList<>();
        collectNonAggColumnsImpl(unwrapAlias(node), result);
        return result;
    }

    private void collectNonAggColumnsImpl(SqlNode node, List<SqlIdentifier> result) {
        if (node == null || node instanceof SqlLiteral || node.kind() == SqlKind.NULL_LITERAL
            || node.kind() == SqlKind.STAR) return;
        if (node instanceof SqlAggCall) return;
        if (node instanceof SqlSubquery) return;
        if (node instanceof SqlIdentifier id) { result.add(id); return; }
        if (node instanceof SqlBinaryOp b) {
            collectNonAggColumnsImpl(b.left(), result);
            collectNonAggColumnsImpl(b.right(), result);
            return;
        }
        if (node instanceof SqlBetween b) {
            collectNonAggColumnsImpl(b.expr(), result);
            collectNonAggColumnsImpl(b.low(), result);
            collectNonAggColumnsImpl(b.high(), result);
            return;
        }
        if (node instanceof SqlInList in) {
            collectNonAggColumnsImpl(in.expr(), result);
            for (SqlNode v : in.values().nodes()) collectNonAggColumnsImpl(v, result);
            return;
        }
        if (node instanceof SqlCast cast) {
            collectNonAggColumnsImpl(cast.expr(), result);
            return;
        }
        if (node instanceof SqlFunctionCall fc) {
            for (SqlNode arg : fc.arguments().nodes()) collectNonAggColumnsImpl(arg, result);
            return;
        }
        if (node instanceof SqlCase c) {
            for (SqlCase.WhenThen wt : c.whenThens()) {
                collectNonAggColumnsImpl(wt.condition(), result);
                collectNonAggColumnsImpl(wt.result(), result);
            }
            if (c.elseExpr() != null) collectNonAggColumnsImpl(c.elseExpr(), result);
            return;
        }
        if (node instanceof SqlOrderByItem item) {
            collectNonAggColumnsImpl(item.column(), result);
            return;
        }
    }

    /** 检查表达式中是否嵌套了聚合函数（用于 validateAggCall 检测嵌套聚合） */
    private boolean containsNestedAgg(SqlNode node) {
        if (node == null || node instanceof SqlLiteral || node.kind() == SqlKind.NULL_LITERAL
            || node.kind() == SqlKind.STAR) return false;
        if (node instanceof SqlAggCall) return true;
        if (node instanceof SqlSubquery) return false;
        if (node instanceof SqlIdentifier) return false;
        if (node instanceof SqlBinaryOp b) {
            return containsNestedAgg(b.left()) || containsNestedAgg(b.right());
        }
        if (node instanceof SqlBetween b) {
            return containsNestedAgg(b.expr()) || containsNestedAgg(b.low()) || containsNestedAgg(b.high());
        }
        if (node instanceof SqlInList in) {
            if (containsNestedAgg(in.expr())) return true;
            for (SqlNode v : in.values().nodes()) {
                if (containsNestedAgg(v)) return true;
            }
            return false;
        }
        if (node instanceof SqlFunctionCall fc) {
            for (SqlNode arg : fc.arguments().nodes()) {
                if (containsNestedAgg(arg)) return true;
            }
            return false;
        }
        if (node instanceof SqlCase c) {
            for (SqlCase.WhenThen wt : c.whenThens()) {
                if (containsNestedAgg(wt.condition()) || containsNestedAgg(wt.result())) return true;
            }
            return c.elseExpr() != null && containsNestedAgg(c.elseExpr());
        }
        return false;
    }

    /** 隐式聚合校验：有聚合函数但无 GROUP BY 时，非聚合列不合法 */
    private void validateImplicitAggregation(SqlSelect select, List<TableScope> tables) {
        for (SqlNode node : select.projection().nodes()) {
            SqlNode expression = unwrapAlias(node);
            if (expression.kind() == SqlKind.STAR) {
                throw new ValidationException("SELECT * not allowed with aggregate functions");
            }
            if (isLiteral(expression)) continue;
            List<SqlIdentifier> nonAggCols = collectNonAggColumns(expression);
            if (!nonAggCols.isEmpty()) {
                throw new ValidationException(
                    "Column '" + nonAggCols.get(0).name()
                    + "' must appear in GROUP BY or be aggregated");
            }
        }
    }

    /** 禁止 WHERE 子句中使用聚合函数 */
    private void rejectAggInWhere(SqlNode node) {
        if (node == null || node instanceof SqlLiteral || node.kind() == SqlKind.NULL_LITERAL
            || node.kind() == SqlKind.STAR || node instanceof SqlIdentifier) return;
        if (node instanceof SqlSubquery) return;
        if (node instanceof SqlExists) return;
        if (node instanceof SqlInSubquery inSub) {
            rejectAggInWhere(inSub.expr());
            return;
        }
        if (node instanceof SqlAggCall) {
            throw new ValidationException("Aggregate functions not allowed in WHERE");
        }
        if (node instanceof SqlBinaryOp b) {
            rejectAggInWhere(b.left());
            rejectAggInWhere(b.right());
            return;
        }
        if (node instanceof SqlBetween b) {
            rejectAggInWhere(b.expr());
            rejectAggInWhere(b.low());
            rejectAggInWhere(b.high());
            return;
        }
        if (node instanceof SqlInList in) {
            rejectAggInWhere(in.expr());
            for (SqlNode v : in.values().nodes()) rejectAggInWhere(v);
            return;
        }
        if (node instanceof SqlFunctionCall fc) {
            for (SqlNode arg : fc.arguments().nodes()) rejectAggInWhere(arg);
            return;
        }
        if (node instanceof SqlCast cast) {
            rejectAggInWhere(cast.expr());
            return;
        }
        if (node instanceof SqlCase c) {
            for (SqlCase.WhenThen wt : c.whenThens()) {
                rejectAggInWhere(wt.condition());
                rejectAggInWhere(wt.result());
            }
            if (c.elseExpr() != null) rejectAggInWhere(c.elseExpr());
            return;
        }
    }

    // ==================== 表达式类型推断与检查 ====================

    /**
     * 推断表达式的返回类型。
     * 返回 null 表示无法推断（如 *)。
     */
    private SqlType inferType(SqlNode node, List<TableScope> tables) {
        if (node == null || node.kind() == SqlKind.NULL_LITERAL || node.kind() == SqlKind.STAR) return null;
        node = unwrapAlias(node);
        if (node instanceof SqlLiteral lit) return lit.type();
        if (node instanceof SqlIdentifier id) return resolveColumnType(id.name(), tables);
        if (node instanceof SqlCast cast) return cast.targetType();
        if (node instanceof SqlAggCall agg) {
            String func = agg.funcName().toUpperCase();
            return switch (func) {
                case "COUNT" -> SqlType.BIGINT;
                case "SUM" -> {
                    SqlType argType = inferType(agg.arg(), tables);
                    yield (argType == SqlType.INT32 || argType == SqlType.BIGINT) ? SqlType.BIGINT : SqlType.DECIMAL;
                }
                case "AVG" -> SqlType.DECIMAL;
                case "MAX", "MIN" -> inferType(agg.arg(), tables);
                default -> null;
            };
        }
        if (node instanceof SqlBinaryOp binOp) {
            SqlKind k = binOp.kind();
            if (k == SqlKind.ADD || k == SqlKind.SUB || k == SqlKind.MUL || k == SqlKind.DIV) {
                SqlType lt = inferType(binOp.left(), tables);
                SqlType rt = inferType(binOp.right(), tables);
                return promoteNumeric(lt, rt);
            }
            // 比较/逻辑运算符返回 boolean（我们没有 BOOLEAN 类型，用 INT32 代替）
            return SqlType.INT32;
        }
        if (node instanceof SqlFunctionCall fc) {
            String func = fc.functionName().toUpperCase();
            return switch (func) {
                case "UPPER", "LOWER", "COALESCE" -> SqlType.VARCHAR;
                default -> null;
            };
        }
        return null;
    }

    private SqlType resolveColumnType(String colName, List<TableScope> tables) {
        if (colName.contains(".")) {
            String[] parts = colName.split("\\.", 2);
            String tableName = parts[0];
            String col = parts[1];
            for (TableScope scope : tables) {
                if (scope.visibleName().equalsIgnoreCase(tableName)) {
                    for (ColumnMeta cm : scope.table().columns()) {
                        if (cm.name().equalsIgnoreCase(col)) return cm.type();
                    }
                }
            }
        }
        for (TableScope scope : tables) {
            for (ColumnMeta cm : scope.table().columns()) {
                if (cm.name().equalsIgnoreCase(colName)) return cm.type();
            }
        }
        return null;
    }

    private SqlType promoteNumeric(SqlType a, SqlType b) {
        if (a == null || b == null) return SqlType.DECIMAL;
        if (a == SqlType.DECIMAL || b == SqlType.DECIMAL) return SqlType.DECIMAL;
        if (a == SqlType.BIGINT || b == SqlType.BIGINT) return SqlType.BIGINT;
        return SqlType.INT32;
    }

    /**
     * 检查比较表达式两侧类型是否兼容。
     * 数值类型之间可以比较，VARCHAR 只能和 VARCHAR 比较。
     */
    private void checkComparisonTypes(SqlNode left, SqlNode right, List<TableScope> tables) {
        SqlType lt = inferType(left, tables);
        SqlType rt = inferType(right, tables);
        if (lt == null || rt == null) return; // 无法推断，跳过
        boolean leftNumeric = isNumericType(lt);
        boolean rightNumeric = isNumericType(rt);
        if (leftNumeric != rightNumeric) {
            throw new ValidationException(
                "Type mismatch: cannot compare " + lt + " with " + rt);
        }
    }

    /**
     * 检查算术表达式操作数是否为数值类型。
     */
    private void checkArithmeticTypes(SqlNode left, SqlNode right, List<TableScope> tables) {
        SqlType lt = inferType(left, tables);
        SqlType rt = inferType(right, tables);
        if (lt != null && !isNumericType(lt)) {
            throw new ValidationException(
                "Arithmetic operation requires numeric type, got " + lt);
        }
        if (rt != null && !isNumericType(rt)) {
            throw new ValidationException(
                "Arithmetic operation requires numeric type, got " + rt);
        }
    }

    private boolean isNumericType(SqlType type) {
        return type == SqlType.INT32 || type == SqlType.BIGINT || type == SqlType.DECIMAL;
    }

    private boolean areTypesCompatible(SqlType a, SqlType b) {
        if (a == null || b == null) return true;
        if (isNumericType(a) && isNumericType(b)) return true;
        return a == b;
    }

    private void checkCaseBranchTypes(SqlCase caseExpr, List<TableScope> tables) {
        List<SqlType> branchTypes = new ArrayList<>();
        for (SqlCase.WhenThen wt : caseExpr.whenThens()) {
            SqlType t = inferType(wt.result(), tables);
            if (t != null) branchTypes.add(t);
        }
        if (caseExpr.elseExpr() != null) {
            SqlType t = inferType(caseExpr.elseExpr(), tables);
            if (t != null) branchTypes.add(t);
        }
        if (branchTypes.size() < 2) return;
        SqlType first = branchTypes.get(0);
        for (int i = 1; i < branchTypes.size(); i++) {
            if (!areTypesCompatible(first, branchTypes.get(i))) {
                throw new ValidationException(
                    "CASE branch type mismatch: " + first + " vs " + branchTypes.get(i));
            }
        }
    }

    private void checkBetweenTypes(SqlBetween between, List<TableScope> tables) {
        SqlType exprType = inferType(between.expr(), tables);
        SqlType lowType = inferType(between.low(), tables);
        SqlType highType = inferType(between.high(), tables);
        if (exprType != null && lowType != null && !areTypesCompatible(exprType, lowType)) {
            throw new ValidationException(
                "BETWEEN type mismatch: " + exprType + " vs " + lowType);
        }
        if (exprType != null && highType != null && !areTypesCompatible(exprType, highType)) {
            throw new ValidationException(
                "BETWEEN type mismatch: " + exprType + " vs " + highType);
        }
    }

    private void checkInListTypes(SqlInList inList, List<TableScope> tables) {
        SqlType exprType = inferType(inList.expr(), tables);
        if (exprType == null) return;
        for (SqlNode value : inList.values().nodes()) {
            SqlType valType = inferType(value, tables);
            if (valType != null && !areTypesCompatible(exprType, valType)) {
                throw new ValidationException(
                    "IN list type mismatch: " + exprType + " vs " + valType);
            }
        }
    }

    private void checkFunctionArgTypes(SqlFunctionCall fc, List<TableScope> tables) {
        String func = fc.functionName().toUpperCase();
        switch (func) {
            case "UPPER", "LOWER" -> {
                if (fc.arguments().size() != 1) return;
                SqlType argType = inferType(fc.arguments().get(0), tables);
                if (argType != null && argType != SqlType.VARCHAR) {
                    throw new ValidationException(
                        func + " requires VARCHAR argument, got " + argType);
                }
            }
            case "COALESCE" -> {
                List<SqlType> argTypes = new ArrayList<>();
                for (SqlNode arg : fc.arguments().nodes()) {
                    SqlType t = inferType(arg, tables);
                    if (t != null) argTypes.add(t);
                }
                if (argTypes.size() < 2) return;
                SqlType first = argTypes.get(0);
                for (int i = 1; i < argTypes.size(); i++) {
                    if (!areTypesCompatible(first, argTypes.get(i))) {
                        throw new ValidationException(
                            "COALESCE argument type mismatch: " + first + " vs " + argTypes.get(i));
                    }
                }
            }
        }
    }

    private int extractProjectionCount(SqlNode node) {
        if (node instanceof SqlSelect select) {
            if (select.projection().size() == 1 && select.projection().get(0).kind() == SqlKind.STAR) {
                return -1;
            }
            return select.projection().size();
        }
        if (node instanceof SqlSetOperation setOp) {
            return extractProjectionCount(setOp.left());
        }
        return -1;
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
        int leftCount = extractProjectionCount(setOp.left());
        int rightCount = extractProjectionCount(setOp.right());
        if (leftCount > 0 && rightCount > 0 && leftCount != rightCount) {
            throw new ValidationException(
                "Set operation requires equal column count, left has " + leftCount + ", right has " + rightCount);
        }
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
