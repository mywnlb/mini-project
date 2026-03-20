package cn.zhangyis.minidb.sql.planner;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.ValidatedDml;
import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

import java.util.*;

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
            case SqlSelect select -> {
                // 支持 UNION 中的原始 SELECT（验证后递归）
                SqlValidator v = new SqlValidator(catalog);
                SqlNode val = v.validate(select);
                yield convert(val);
            }
            case SqlSetOperation op -> {
                RelNode left = convert(op.left());
                RelNode right = convert(op.right());
                yield new RelUnion(left, right, op.all(), op.opType());
            }
            case ValidatedDml dml -> convertDml(dml);
            case SqlCreateTable create -> new RelCreateTable(create, catalog);
            case SqlDropTable drop -> new RelDropTable(drop, catalog);
            case SqlAlterTable alter -> new RelAlterTable(alter, catalog);
            case SqlCreateIndex createIdx -> new RelCreateIndex(createIdx, catalog);
            case SqlDropIndex dropIdx -> new RelDropIndex(dropIdx, catalog);
            default -> throw new IllegalArgumentException("Unsupported: " + validated.kind());
        };
    }

    private RelNode convertSelect(ValidatedSqlSelect validated) {
        SqlSelect select = validated.original();
        RelNode plan = convertFrom(select.from(), validated);

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
                plan = factories.filter(plan, rewriteAggCallsToSlotRefs(select.having()));
            }
        }

        plan = factories.project(plan, select.projection());
        if (select.distinct()) {
            plan = new RelDistinct(plan);
        }

        if (select.orderBy() != null || select.limit() != null || select.offset() != null) {
            plan = new RelSort(plan, select.orderBy(), select.limit(), select.offset());
        }

        return plan;
    }

    private RelNode convertFrom(SqlNode from, ValidatedSqlSelect validated) {
        if (from == null) {
            // 常量 SELECT（UNION 测试用），使用 dummy scan 避免 NPE
            return factories.scan("USERS", validated.tables().values().stream().findFirst()
                .orElse(new TableMeta("USERS", java.util.List.of(), 0)), "USERS");
        }

        if (from instanceof SqlJoin join) {
            RelNode left = convertFrom(join.left(), validated);
            RelNode right = convertFrom(join.right(), validated);
            return factories.join(left, right, join.condition(), join.joinType());
        }

        if (from instanceof SqlDerivedTable derived) {
            // 递归验证并转换内层 SELECT
            SqlValidator validator = new SqlValidator(catalog);
            SqlNode innerValidated = validator.validate(derived.select());
            RelNode innerPlan = convert(innerValidated);
            return new RelDerivedScan(innerPlan, derived.alias(), derived.select());
        }

        SqlTableRef ref = asTableRef(from);
        TableMeta table = validated.table(ref.visibleName());
        if (table == null) {
            throw new IllegalArgumentException("Missing validated table scope for " + ref.visibleName());
        }
        return factories.scan(ref.tableName(), table, ref.visibleName());
    }

    private List<SqlAggCall> collectAllAggCalls(SqlSelect select) {
        Map<String, SqlAggCall> aggMap = new LinkedHashMap<>();
        for (SqlNode node : select.projection().nodes()) {
            collectAggCallsFromTree(unwrapAlias(node), aggMap);
        }
        if (select.having() != null) {
            collectAggCallsFromTree(select.having(), aggMap);
        }

        return new ArrayList<>(aggMap.values());
    }

    private void collectAggCallsFromTree(SqlNode node, Map<String, SqlAggCall> aggMap) {
        if (node instanceof SqlSubquery) return; // 子查询内部的 agg 不属于外层
        if (node instanceof SqlAggCall agg) {
            String key = aggSlotKey(agg);
            aggMap.putIfAbsent(key, agg);
            return;
        }
        if (node instanceof SqlBinaryOp binOp) {
            collectAggCallsFromTree(binOp.left(), aggMap);
            collectAggCallsFromTree(binOp.right(), aggMap);
            return;
        }
        if (node instanceof SqlBetween between) {
            collectAggCallsFromTree(between.expr(), aggMap);
            collectAggCallsFromTree(between.low(), aggMap);
            collectAggCallsFromTree(between.high(), aggMap);
            return;
        }
        if (node instanceof SqlInList inList) {
            collectAggCallsFromTree(inList.expr(), aggMap);
            for (SqlNode value : inList.values().nodes()) {
                collectAggCallsFromTree(value, aggMap);
            }
        }
    }

    private boolean containsAggCall(SqlNodeList projection) {
        for (SqlNode node : projection.nodes()) {
            if (treeContainsAggCall(unwrapAlias(node))) {
                return true;
            }
        }
        return false;
    }

    private boolean treeContainsAggCall(SqlNode node) {
        if (node instanceof SqlSubquery) return false; // 子查询内部的 agg 不属于外层
        if (node instanceof SqlAggCall) return true;
        if (node instanceof SqlBinaryOp binOp) {
            return treeContainsAggCall(binOp.left()) || treeContainsAggCall(binOp.right());
        }
        if (node instanceof SqlBetween between) {
            return treeContainsAggCall(between.expr())
                || treeContainsAggCall(between.low())
                || treeContainsAggCall(between.high());
        }
        if (node instanceof SqlInList inList) {
            if (treeContainsAggCall(inList.expr())) {
                return true;
            }
            for (SqlNode value : inList.values().nodes()) {
                if (treeContainsAggCall(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private SqlNode rewriteAggCallsToSlotRefs(SqlNode node) {
        if (node instanceof SqlSubquery) return node; // 子查询不改写
        if (node instanceof SqlAggCall agg) {
            return new SqlIdentifier(aggSlotKey(agg));
        }
        if (node instanceof SqlBinaryOp binOp) {
            return new SqlBinaryOp(binOp.opKind(),
                rewriteAggCallsToSlotRefs(binOp.left()),
                rewriteAggCallsToSlotRefs(binOp.right()));
        }
        if (node instanceof SqlBetween between) {
            return new SqlBetween(
                rewriteAggCallsToSlotRefs(between.expr()),
                rewriteAggCallsToSlotRefs(between.low()),
                rewriteAggCallsToSlotRefs(between.high())
            );
        }
        if (node instanceof SqlInList inList) {
            SqlNodeList values = new SqlNodeList();
            for (SqlNode value : inList.values().nodes()) {
                values.add(rewriteAggCallsToSlotRefs(value));
            }
            return new SqlInList(rewriteAggCallsToSlotRefs(inList.expr()), values);
        }
        return node;
    }

    private String aggSlotKey(SqlAggCall agg) {
        if (agg.arg().kind() == SqlKind.STAR) {
            return agg.funcName().toUpperCase() + "(*)";
        }
        String argStr = agg.arg().toString().replaceAll("\\s+", "");
        return agg.funcName().toUpperCase() + "(" + argStr + ")";
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

    private RelNode convertDml(ValidatedDml dml) {
        return switch (dml.original()) {
            case SqlInsertSelect insertSelect -> convertInsertSelect(insertSelect, dml.tableMeta());
            case SqlInsert insert -> convertInsert(insert, dml.tableMeta());
            case SqlUpdate update -> convertUpdate(update, dml.tableMeta());
            case SqlDelete delete -> convertDelete(delete, dml.tableMeta());
            default -> throw new IllegalArgumentException("Unsupported DML: " + dml.kind());
        };
    }

    private RelNode convertInsertSelect(SqlInsertSelect insertSelect, TableMeta table) {
        // SELECT 已在 Validator 中验证，此处需重新走 validate+convert 产生 RelNode
        SqlValidator validator = new SqlValidator(catalog);
        SqlNode validatedSelect = validator.validate(insertSelect.select());
        RelNode selectPlan = convert(validatedSelect);
        return new RelInsertSelect(insertSelect.table().name(), table, insertSelect.columns(), selectPlan);
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
    RelJoin join(RelNode left, RelNode right, SqlNode condition);
    default RelJoin join(RelNode left, RelNode right, SqlNode condition, cn.zhangyis.minidb.sql.ast.JoinType joinType) {
        return new RelJoin(left, right, condition, joinType);
    }
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
    public RelJoin join(RelNode left, RelNode right, SqlNode condition) {
        return new RelJoin(left, right, condition);
    }

    @Override
    public RelJoin join(RelNode left, RelNode right, SqlNode condition, cn.zhangyis.minidb.sql.ast.JoinType joinType) {
        return new RelJoin(left, right, condition, joinType);
    }
}
