package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;

/**
 * 物理计划生成器：RelNode → ExecNode
 * 支持两种模式：
 * 1. 手动指定 JOIN 算法
 * 2. 基于 CBO 自动选择 JOIN 算法（默认）
 *
 * <p>默认配置只在真实 capability 存在时启用索引路径；
 * 手动启用 + 支持 lookup 的 DataSourceSpi 才会生成 INDEX_NESTED_LOOP。</p>
 */
public class PhysicalPlanner {

    private record JoinKeys(String leftKey, String rightKey) {}
    private record LookupTarget(String tableName, String outputName, String lookupColumn) {}

    public enum JoinAlgorithm {
        NESTED_LOOP, HASH_JOIN, SORT_MERGE, INDEX_NESTED_LOOP
    }

    private final CostOptimizer costOptimizer;
    private final DataSourceSpi dataSource;
    private final CatalogSpi catalog;
    private ExecutionContext executionContext;

    public PhysicalPlanner() {
        this(new CostOptimizer(), MockDataSourceAdapter.INSTANCE, null);
    }

    public PhysicalPlanner(CostOptimizer costOptimizer) {
        this(costOptimizer, MockDataSourceAdapter.INSTANCE, null);
    }

    public PhysicalPlanner(DataSourceSpi dataSource) {
        this(new CostOptimizer(), dataSource, null);
    }

    public PhysicalPlanner(CostOptimizer costOptimizer, DataSourceSpi dataSource) {
        this(costOptimizer, dataSource, null);
    }

    public PhysicalPlanner(CostOptimizer costOptimizer, DataSourceSpi dataSource, CatalogSpi catalog) {
        this.costOptimizer = costOptimizer;
        this.dataSource = dataSource;
        this.catalog = catalog;
    }

    public void setExecutionContext(ExecutionContext ctx) {
        this.executionContext = ctx;
    }

    /**
     * 直接从 SqlNode 生成执行器（用于事务控制语句，不经过 SqlToRelConverter）
     */
    public ExecNode planSqlNode(SqlNode sqlNode) {
        if (sqlNode instanceof SqlTransaction txn) {
            if (executionContext == null) {
                throw new IllegalStateException("ExecutionContext not set, cannot execute transaction control");
            }
            return switch (txn.kind()) {
                case BEGIN_TXN -> new BeginExec(executionContext);
                case COMMIT_TXN -> new CommitExec(executionContext);
                case ROLLBACK_TXN -> new RollbackExec(executionContext);
                default -> throw new IllegalArgumentException("Unknown transaction kind: " + txn.kind());
            };
        }
        throw new IllegalArgumentException("planSqlNode only handles transaction control, got: " + sqlNode.kind());
    }

    /**
     * 基于 CBO 自动选择 JOIN 算法
     */
    public ExecNode plan(RelNode relNode) {
        return planInternal(relNode, null);
    }

    /**
     * 手动指定 JOIN 算法（用于测试对比）
     */
    public ExecNode plan(RelNode relNode, JoinAlgorithm joinAlgo) {
        return planInternal(relNode, joinAlgo);
    }

    private ExecNode planInternal(RelNode relNode, JoinAlgorithm overrideAlgo) {
        if (relNode instanceof RelIndexedScan indexedScan) {
            return new IndexScanExec(indexedScan, dataSource);
        }
        if (relNode instanceof RelScan scan) {
            return new ScanExec(scan.tableName(), scan.outputName(), dataSource);
        }
        if (relNode instanceof RelDerivedScan derived) {
            ExecNode innerExec = planInternal(derived.input(), overrideAlgo);
            return new SubqueryExec(innerExec, derived.alias());
        }
        if (relNode instanceof RelFilter filter) {
            ExecNode input = planInternal(filter.input(), overrideAlgo);
            return new FilterExec(input, filter.condition(), createSubqueryEvaluator(), createSubqueryExecutor());
        }
        if (relNode instanceof RelProject project) {
            ExecNode input = planInternal(project.input(), overrideAlgo);
            return new ProjectExec(input, project.projection());
        }
        if (relNode instanceof RelDistinct distinct) {
            ExecNode input = planInternal(distinct.input(), overrideAlgo);
            return new DistinctExec(input);
        }
        if (relNode instanceof RelJoin join) {
            return planJoin(join, overrideAlgo);
        }
        if (relNode instanceof RelAggregate agg) {
            ExecNode input = planInternal(agg.input(), overrideAlgo);
            return new AggregateExec(input, agg.groupKeys(), agg.aggCalls());
        }
        if (relNode instanceof RelSort sort) {
            ExecNode input = planInternal(sort.input(), overrideAlgo);
            Integer limit = null;
            Integer offset = null;
            if (sort.limit() instanceof SqlLiteral lit) {
                limit = Integer.parseInt(lit.value());
            }
            if (sort.offset() instanceof SqlLiteral lit) {
                offset = Integer.parseInt(lit.value());
            }
            return new SortExec(input, sort.orderBy(), limit, offset);
        }
        if (relNode instanceof RelInsert insert) {
            return new InsertExec(insert, dataSource);
        }
        if (relNode instanceof RelUpdate update) {
            return new UpdateExec(update, dataSource, catalog);
        }
        if (relNode instanceof RelDelete delete) {
            return new DeleteExec(delete, dataSource);
        }
        if (relNode instanceof RelCreateTable create) {
            return new CreateTableExec(create);
        }
        if (relNode instanceof RelDropTable drop) {
            return new DropTableExec(drop);
        }
        if (relNode instanceof RelAlterTable alter) {
            return new AlterTableExec(alter);
        }
        if (relNode instanceof RelCreateIndex createIdx) {
            return new CreateIndexExec(createIdx);
        }
        if (relNode instanceof RelDropIndex dropIdx) {
            return new DropIndexExec(dropIdx);
        }
        throw new IllegalArgumentException("Unknown RelNode: " + relNode.getClass().getSimpleName());
    }

    private ExecNode planJoin(RelJoin join, JoinAlgorithm overrideAlgo) {
        ExecNode left = planInternal(join.left(), overrideAlgo);
        ExecNode right = planInternal(join.right(), overrideAlgo);

        // CBO 自动选择 or 手动指定
        JoinAlgorithm algo = overrideAlgo != null
            ? overrideAlgo
            : costOptimizer.chooseJoinAlgorithm(join);

        JoinKeys keys = extractJoinKeys(join);

        return switch (algo) {
            case NESTED_LOOP -> new NestedLoopJoinExec(left, right, join.condition());
            case HASH_JOIN -> {
                if (keys != null) {
                    yield new HashJoinExec(left, right, keys.leftKey(), keys.rightKey());
                }
                yield new NestedLoopJoinExec(left, right, join.condition());
            }
            case SORT_MERGE -> {
                if (keys != null) {
                    yield new SortMergeJoinExec(left, right, keys.leftKey(), keys.rightKey());
                }
                yield new NestedLoopJoinExec(left, right, join.condition());
            }
            case INDEX_NESTED_LOOP -> {
                ExecNode exec = keys == null ? null : planIndexJoin(join, left, right, keys);
                if (exec != null) {
                    yield exec;
                }
                if (keys != null) {
                    yield new HashJoinExec(left, right, keys.leftKey(), keys.rightKey());
                }
                yield new NestedLoopJoinExec(left, right, join.condition());
            }
        };
    }

    private ExecNode planIndexJoin(RelJoin join, ExecNode leftExec, ExecNode rightExec, JoinKeys keys) {
        LookupTarget rightLookup = lookupTarget(join.right(), keys.rightKey());
        if (rightLookup != null) {
            return new IndexNestedLoopJoinExec(
                leftExec,
                keys.leftKey(),
                rightLookup.tableName(),
                rightLookup.outputName(),
                rightLookup.lookupColumn(),
                dataSource,
                true
            );
        }

        LookupTarget leftLookup = lookupTarget(join.left(), keys.leftKey());
        if (leftLookup != null) {
            return new IndexNestedLoopJoinExec(
                rightExec,
                keys.rightKey(),
                leftLookup.tableName(),
                leftLookup.outputName(),
                leftLookup.lookupColumn(),
                dataSource,
                false
            );
        }
        return null;
    }

    private JoinKeys extractJoinKeys(RelJoin join) {
        if (!(join.condition() instanceof SqlBinaryOp binOp) || binOp.kind() != SqlKind.BINARY_EQ) {
            return null;
        }
        if (!(binOp.left() instanceof SqlIdentifier first) || !(binOp.right() instanceof SqlIdentifier second)) {
            return null;
        }

        String firstName = first.name();
        String secondName = second.name();

        boolean firstQualified = isQualified(firstName);
        boolean secondQualified = isQualified(secondName);
        if (firstQualified != secondQualified) {
            return null;
        }

        if (firstQualified) {
            return alignQualifiedKeys(join, firstName, secondName);
        }
        if (isSafeBareSameNameEquiJoin(join, firstName, secondName)) {
            return new JoinKeys(firstName, secondName);
        }
        return null;
    }

    private JoinKeys alignQualifiedKeys(RelJoin join, String firstName, String secondName) {
        boolean firstOnLeft = identifierBelongsTo(join.left(), firstName);
        boolean firstOnRight = identifierBelongsTo(join.right(), firstName);
        boolean secondOnLeft = identifierBelongsTo(join.left(), secondName);
        boolean secondOnRight = identifierBelongsTo(join.right(), secondName);

        if (firstOnLeft && secondOnRight) {
            return new JoinKeys(firstName, secondName);
        }
        if (secondOnLeft && firstOnRight) {
            return new JoinKeys(secondName, firstName);
        }
        return null;
    }

    private boolean isSafeBareSameNameEquiJoin(RelJoin join, String firstName, String secondName) {
        return firstName.equalsIgnoreCase(secondName)
            && identifierBelongsTo(join.left(), firstName)
            && identifierBelongsTo(join.right(), secondName);
    }

    private boolean isQualified(String identifier) {
        return identifier.contains(".");
    }

    private boolean identifierBelongsTo(RelNode node, String identifier) {
        if (node instanceof RelScan scan) {
            return scanOutputsIdentifier(scan, identifier);
        }
        if (node instanceof RelIndexedScan scan) {
            return scanOutputsIdentifier(scan, identifier);
        }
        if (node instanceof RelFilter filter) {
            return identifierBelongsTo(filter.input(), identifier);
        }
        if (node instanceof RelProject project) {
            return identifierBelongsTo(project.input(), identifier);
        }
        if (node instanceof RelDistinct distinct) {
            return identifierBelongsTo(distinct.input(), identifier);
        }
        if (node instanceof RelAggregate aggregate) {
            return identifierBelongsTo(aggregate.input(), identifier);
        }
        if (node instanceof RelSort sort) {
            return identifierBelongsTo(sort.input(), identifier);
        }
        if (node instanceof RelJoin join) {
            return identifierBelongsTo(join.left(), identifier) || identifierBelongsTo(join.right(), identifier);
        }
        return false;
    }

    private boolean scanOutputsIdentifier(RelScan scan, String identifier) {
        String normalized = identifier.toUpperCase();
        if (normalized.contains(".")) {
            String[] parts = normalized.split("\\.", 2);
            if (!scan.outputName().equalsIgnoreCase(parts[0])) {
                return false;
            }
            return scan.tableMeta().columns().stream()
                .anyMatch(column -> column.name().equalsIgnoreCase(parts[1]));
        }
        return scan.tableMeta().columns().stream()
            .anyMatch(column -> column.name().equalsIgnoreCase(normalized));
    }

    private LookupTarget lookupTarget(RelNode node, String identifier) {
        if (!(node instanceof RelScan scan)) {
            return null;
        }
        String lookupColumn = bareColumn(identifier);
        boolean matchesPrimary = scan.tableMeta().columns().stream()
            .anyMatch(column -> column.isPrimaryKey() && column.name().equalsIgnoreCase(lookupColumn));
        if (!matchesPrimary || !dataSource.supportsLookup(scan.tableName(), lookupColumn)) {
            return null;
        }
        return new LookupTarget(scan.tableName(), scan.outputName(), lookupColumn);
    }

    private String bareColumn(String identifier) {
        return identifier.contains(".") ? identifier.split("\\.", 2)[1] : identifier;
    }

    /**
     * 创建 EXISTS/IN 子查询执行器：将 SqlSelect 编译为 ExecNode
     */
    private FilterExec.SubqueryExecutor createSubqueryExecutor() {
        CatalogSpi cat = this.catalog != null ? this.catalog : new MockCatalog();
        return select -> {
            SqlValidator validator = new SqlValidator(cat);
            SqlNode validated = validator.validate(select);
            SqlToRelConverter converter = new SqlToRelConverter(cat);
            RelNode rel = converter.convert(validated);
            return new PhysicalPlanner(costOptimizer, dataSource, cat).plan(rel);
        };
    }

    private FilterExec.SubqueryEvaluator createSubqueryEvaluator() {
        CatalogSpi cat = this.catalog != null ? this.catalog : new MockCatalog();
        return subquery -> {
            SqlValidator validator = new SqlValidator(cat);
            SqlNode validated = validator.validate(subquery.select());
            SqlToRelConverter converter = new SqlToRelConverter(cat);
            RelNode rel = converter.convert(validated);
            ExecNode exec = new PhysicalPlanner(costOptimizer, dataSource, cat).plan(rel);
            exec.open();
            try {
                Row row = exec.next();
                if (row == null) return null;
                if (exec.next() != null) {
                    throw new IllegalStateException("Scalar subquery returned more than one row");
                }
                // 返回第一列的值
                return row.columns().values().iterator().next();
            } finally {
                exec.close();
            }
        };
    }
}
