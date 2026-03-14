package cn.zhangyis.minidb.sql.planner;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.ValidatedDml;
import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SqlToRelConverter {
    private final RelFactories factories;
    private final CatalogSpi catalog;

    public SqlToRelConverter() {
        this(DefaultRelFactories.INSTANCE, null);
    }

    public SqlToRelConverter(CatalogSpi catalog) {
        this(DefaultRelFactories.INSTANCE, catalog);
    }

    public SqlToRelConverter(RelFactories factories) {
        this(factories, null);
    }

    public SqlToRelConverter(RelFactories factories, CatalogSpi catalog) {
        this.factories = factories;
        this.catalog = catalog;
    }

    public RelNode convert(SqlNode validated) {
        return switch (validated) {
            case ValidatedSqlSelect select -> convertSelect(select);
            case ValidatedDml dml -> convertDml(dml);
            case SqlCreateTable create -> new RelCreateTable(create, catalog);
            case SqlDropTable drop -> new RelDropTable(drop, catalog);
            case SqlAlterTable alter -> new RelAlterTable(alter, catalog);
            case SqlCreateIndex createIdx -> new RelCreateIndex(createIdx, catalog);
            case SqlDropIndex dropIdx -> new RelDropIndex(dropIdx, catalog);
            default -> throw new IllegalArgumentException("Unsupported: " + validated.kind());
        };
    }

    // ==================== SELECT ====================

    private RelNode convertSelect(ValidatedSqlSelect validated) {
        SqlSelect select = validated.original();
        RelNode plan;

        if (validated.isJoin()) {
            SqlJoin join = (SqlJoin) select.from();
            SqlTableRef leftRef = asTableRef(join.left());
            SqlTableRef rightRef = asTableRef(join.right());
            RelScan leftScan = factories.scan(leftRef.tableName(), validated.leftTable(), leftRef.visibleName());
            RelScan rightScan = factories.scan(rightRef.tableName(), validated.rightTable(), rightRef.visibleName());
            plan = factories.join(leftScan, rightScan, join.condition());
        } else {
            SqlTableRef tableRef = asTableRef(select.from());
            plan = factories.scan(tableRef.tableName(), validated.tableMeta(), tableRef.visibleName());
        }

        if (select.where() != null) {
            plan = factories.filter(plan, select.where());
        }

        boolean needsAggregate = select.groupBy() != null
                || select.having() != null
                || containsAggCall(select.projection());

        if (needsAggregate) {
            List<SqlAggCall> aggCalls = collectAllAggCalls(select);
            plan = new RelAggregate(plan, select.groupBy(), aggCalls);
            if (select.having() != null) {
                SqlNode rewrittenHaving = rewriteAggCallsToSlotRefs(select.having());
                plan = factories.filter(plan, rewrittenHaving);
            }
        }

        plan = factories.project(plan, select.projection());
        if (select.distinct()) {
            plan = new RelDistinct(plan);
        }

        if (select.orderBy() != null || select.limit() != null) {
            plan = new RelSort(plan, select.orderBy(), select.limit());
        }

        return plan;
    }

    /**
     * 收集 SELECT projection 和 HAVING 中的所有 SqlAggCall，去重。
     */
    private List<SqlAggCall> collectAllAggCalls(SqlSelect select) {
        Set<String> seen = new LinkedHashSet<>();
        List<SqlAggCall> aggCalls = new ArrayList<>();

        // 从 SELECT projection 收集
        for (SqlNode node : select.projection().nodes()) {
            collectAggCallsFromTree(unwrapAlias(node), seen, aggCalls);
        }

        // 从 HAVING 收集
        if (select.having() != null) {
            collectAggCallsFromTree(select.having(), seen, aggCalls);
        }

        return aggCalls;
    }

    /**
     * 递归遍历表达式树，收集所有 SqlAggCall。
     */
    private void collectAggCallsFromTree(SqlNode node, Set<String> seen, List<SqlAggCall> aggCalls) {
        if (node instanceof SqlAggCall agg) {
            String key = aggSlotKey(agg);
            if (seen.add(key)) {
                aggCalls.add(agg);
            }
            return;
        }
        if (node instanceof SqlBinaryOp binOp) {
            collectAggCallsFromTree(binOp.left(), seen, aggCalls);
            collectAggCallsFromTree(binOp.right(), seen, aggCalls);
        }
    }

    /**
     * 检查 projection 中是否包含聚合函数调用。
     */
    private boolean containsAggCall(SqlNodeList projection) {
        for (SqlNode node : projection.nodes()) {
            if (treeContainsAggCall(unwrapAlias(node))) {
                return true;
            }
        }
        return false;
    }

    private boolean treeContainsAggCall(SqlNode node) {
        if (node instanceof SqlAggCall) return true;
        if (node instanceof SqlBinaryOp binOp) {
            return treeContainsAggCall(binOp.left()) || treeContainsAggCall(binOp.right());
        }
        return false;
    }

    /**
     * 将 HAVING 条件中的 SqlAggCall 替换为 SqlIdentifier，
     * 引用 AggregateExec 输出 Row 中的聚合槽位 key。
     */
    private SqlNode rewriteAggCallsToSlotRefs(SqlNode node) {
        if (node instanceof SqlAggCall agg) {
            return new SqlIdentifier(aggSlotKey(agg));
        }
        if (node instanceof SqlBinaryOp binOp) {
            SqlNode newLeft = rewriteAggCallsToSlotRefs(binOp.left());
            SqlNode newRight = rewriteAggCallsToSlotRefs(binOp.right());
            if (newLeft != binOp.left() || newRight != binOp.right()) {
                return new SqlBinaryOp(binOp.opKind(), newLeft, newRight);
            }
        }
        return node;
    }

    /**
     * 聚合槽位 key，与 AggregateExec / ProjectExec 中的 key 格式一致。
     */
    private String aggSlotKey(SqlAggCall agg) {
        return agg.funcName() + "(" + agg.arg() + ")";
    }

    private SqlTableRef asTableRef(SqlNode node) {
        if (node instanceof SqlTableRef ref) {
            return ref;
        }
        if (node instanceof SqlIdentifier id) {
            return new SqlTableRef(id.name(), null);
        }
        throw new IllegalArgumentException("Expected table reference, got " + node);
    }

    private SqlNode unwrapAlias(SqlNode node) {
        return node instanceof SqlAlias alias ? alias.expression() : node;
    }

    // ==================== DML ====================

    private RelNode convertDml(ValidatedDml dml) {
        return switch (dml.original()) {
            case SqlInsert insert -> convertInsert(insert, dml.tableMeta());
            case SqlUpdate update -> convertUpdate(update, dml.tableMeta());
            case SqlDelete delete -> convertDelete(delete, dml.tableMeta());
            default -> throw new IllegalArgumentException("Unsupported DML: " + dml.kind());
        };
    }

    private RelNode convertInsert(SqlInsert insert, TableMeta table) {
        return new RelInsert(insert.table().name(), table, insert.columns(), insert.valueRows());
    }

    private RelNode convertUpdate(SqlUpdate update, TableMeta table) {
        RelNode scan = factories.scan(update.table().name(), table, update.table().name());
        RelNode plan = scan;
        if (update.where() != null) {
            plan = factories.filter(plan, update.where());
        }
        return new RelUpdate(plan, update.assignments());
    }

    private RelNode convertDelete(SqlDelete delete, TableMeta table) {
        RelNode scan = factories.scan(delete.table().name(), table, delete.table().name());
        RelNode plan = scan;
        if (delete.where() != null) {
            plan = factories.filter(plan, delete.where());
        }
        return new RelDelete(plan);
    }
}

interface RelFactories {
    RelScan scan(String tableName, TableMeta meta, String outputName);
    RelFilter filter(RelNode input, SqlNode condition);
    RelProject project(RelNode input, SqlNodeList projection);
    RelJoin join(RelScan left, RelScan right, SqlNode condition);
}

class DefaultRelFactories implements RelFactories {
    public static final DefaultRelFactories INSTANCE = new DefaultRelFactories();

    @Override
    public RelScan scan(String tableName, TableMeta meta, String outputName) {
        return new RelScan(tableName, meta, outputName);
    }

    @Override
    public RelFilter filter(RelNode input, SqlNode condition) {
        return new RelFilter(input, condition);
    }

    @Override
    public RelProject project(RelNode input, SqlNodeList projection) {
        return new RelProject(input, projection);
    }

    @Override
    public RelJoin join(RelScan left, RelScan right, SqlNode condition) {
        return new RelJoin(left, right, condition);
    }
}
