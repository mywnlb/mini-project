package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.catalog.StatisticsStore;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.types.SqlType;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private record JoinKeys(List<String> leftKeys, List<String> rightKeys, SqlNode residual) {
        /** 单 key 便捷构造 */
        static JoinKeys single(String leftKey, String rightKey) {
            return new JoinKeys(List.of(leftKey), List.of(rightKey), null);
        }
        String leftKey()  { return leftKeys.get(0); }
        String rightKey() { return rightKeys.get(0); }
        boolean isSingleKey() { return leftKeys.size() == 1; }
    }
    private record LookupTarget(String tableName, String outputName, String lookupColumn) {}

    public enum JoinAlgorithm {
        NESTED_LOOP, HASH_JOIN, SORT_MERGE, INDEX_NESTED_LOOP
    }

    /** 右表预估行数超过此阈值时，HASH_JOIN 降级为 SORT_MERGE（避免 OOM） */
    private static final int MAX_HASH_BUILD_ROWS = 100_000;

    private final CostOptimizer costOptimizer;
    private final DataSourceSpi dataSource;
    private final CatalogSpi catalog;
    private StatisticsStore statisticsStore;
    private ExecutionContext executionContext;

    public PhysicalPlanner() {
        this(new CostOptimizer(), MockDataSourceAdapter.INSTANCE, null);
    }

    public PhysicalPlanner(CostOptimizer costOptimizer) {
        this(costOptimizer, MockDataSourceAdapter.INSTANCE, null);
    }

    public PhysicalPlanner(DataSourceSpi dataSource) {
        this(new CostOptimizer(dataSource instanceof StorageDataSource), dataSource, null);
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

    public void setStatisticsStore(StatisticsStore store) {
        this.statisticsStore = store;
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
            if (executionContext != null && executionContext.parallelism() > 1
                && dataSource.partitionCount(scan.tableName()) > 1) {
                return new ParallelScanExec(scan.tableName(), scan.outputName(), dataSource, executionContext.parallelism(), executionContext.queryThreadPool());
            }
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
            // 检查投影中是否含有窗口函数
            List<WindowExec.WindowSpec> windowSpecs = extractWindowSpecs(project.projection());
            if (!windowSpecs.isEmpty()) {
                input = new WindowExec(input, windowSpecs);
            }
            return new ProjectExec(input, project.projection());
        }
        if (relNode instanceof RelDistinct distinct) {
            ExecNode input = planInternal(distinct.input(), overrideAlgo);
            return new DistinctExec(input);
        }
        if (relNode instanceof RelSemiJoin semi) {
            return planSemiJoin(semi, overrideAlgo);
        }
        if (relNode instanceof RelAntiJoin anti) {
            return planAntiJoin(anti, overrideAlgo);
        }
        if (relNode instanceof RelJoin join) {
            return planJoin(join, overrideAlgo);
        }
        if (relNode instanceof RelPartialAggregate pa) {
            ExecNode input = planInternal(pa.input(), overrideAlgo);
            return new PartialAggregateExec(input, pa.groupKeys(), pa.partialCalls());
        }
        if (relNode instanceof RelFinalAggregate fa) {
            ExecNode input = planInternal(fa.input(), overrideAlgo);
            return new FinalAggregateExec(input, fa.groupKeys(), fa.originalCalls(), fa.partialCalls());
        }
        if (relNode instanceof RelAggregate agg) {
            ExecNode input = planInternal(agg.input(), overrideAlgo);
            if (executionContext != null && executionContext.parallelism() > 1) {
                List<String> groupByCols = extractGroupByColumnNames(agg.groupKeys());
                return new ParallelAggregateExec(input, agg.aggCalls(), groupByCols, executionContext.queryThreadPool());
            }
            return new AggregateExec(input, agg.groupKeys(), agg.aggCalls());
        }
        if (relNode instanceof RelUnion union) {
            ExecNode left = planInternal(union.left(), overrideAlgo);
            ExecNode right = planInternal(union.right(), overrideAlgo);
            return new UnionExec(left, right, union.all(), union.opType());
        }
        if (relNode instanceof RelSort sort) {
            ExecNode input = planInternal(sort.input(), overrideAlgo);
            if (executionContext != null && executionContext.parallelism() > 1
                && sort.limit() == null && sort.offset() == null) {
                return new ParallelSortExec(input, sort.orderBy(), executionContext.queryThreadPool());
            }
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
        if (relNode instanceof RelInsertSelect insertSelect) {
            ExecNode selectExec = planInternal(insertSelect.input(), overrideAlgo);
            return new InsertSelectExec(insertSelect, selectExec, dataSource);
        }
        if (relNode instanceof RelInsert insert) {
            return new InsertExec(insert, dataSource);
        }
        if (relNode instanceof RelUpdate update) {
            return new UpdateExec(update, dataSource, catalog, createSubqueryEvaluator());
        }
        if (relNode instanceof RelDelete) {
            RelDelete delete = (RelDelete) relNode;
            return new DeleteExec(delete, dataSource);
        }
        if (relNode instanceof RelCreateTable) {
            RelCreateTable create = (RelCreateTable) relNode;
            return new CreateTableExec(create);
        }
        if (relNode instanceof RelDropTable) {
            RelDropTable drop = (RelDropTable) relNode;
            return new DropTableExec(drop);
        }
        if (relNode instanceof RelAlterTable) {
            RelAlterTable alter = (RelAlterTable) relNode;
            return new AlterTableExec(alter, dataSource);
        }
        if (relNode instanceof cn.zhangyis.minidb.sql.rel.RelAnalyzeTable analyzeTable) {
            if (statisticsStore == null) {
                throw new IllegalStateException("StatisticsStore not set, cannot execute ANALYZE TABLE");
            }
            return new AnalyzeTableExec(analyzeTable.tableName(), dataSource, catalog, statisticsStore);
        }
        if (relNode instanceof RelCreateIndex) {
            RelCreateIndex createIdx = (RelCreateIndex) relNode;
            return new CreateIndexExec(createIdx);
        }
        if (relNode instanceof RelDropIndex) {
            RelDropIndex dropIdx = (RelDropIndex) relNode;
            return new DropIndexExec(dropIdx);
        }
        throw new IllegalArgumentException("Unknown RelNode: " + relNode.getClass().getSimpleName());
    }

    private ExecNode planJoin(RelJoin join, JoinAlgorithm overrideAlgo) {
        ExecNode left = planInternal(join.left(), overrideAlgo);
        ExecNode right = planInternal(join.right(), overrideAlgo);
        cn.zhangyis.minidb.sql.ast.JoinType joinType = join.joinType();

        JoinKeys keys = extractJoinKeys(join);

        // 并行 Hash Join：INNER JOIN + equi-keys + parallelism > 1
        if (executionContext != null && executionContext.parallelism() > 1
            && joinType == cn.zhangyis.minidb.sql.ast.JoinType.INNER
            && keys != null) {
            List<String> equiCols = keys.leftKeys().stream().map(this::bareColumn).toList();
            ExecNode joinExec = new ParallelHashJoinExec(left, right, join.condition(), equiCols, executionContext.queryThreadPool());
            return wrapResidual(joinExec, keys.residual());
        }

        // CBO 自动选择 or 手动指定
        JoinAlgorithm algo = overrideAlgo != null
            ? overrideAlgo
            : costOptimizer.chooseJoinAlgorithm(join);

        return switch (algo) {
            case NESTED_LOOP -> new NestedLoopJoinExec(left, right, join.condition(), joinType);
            case HASH_JOIN -> {
                if (keys != null) {
                    // 右表预估行数超阈值时降级到 SortMerge（避免 build 阶段 OOM）
                    double rightRows = costOptimizer.costModel().estimateRows(join.right());
                    if (rightRows > MAX_HASH_BUILD_ROWS) {
                        ExecNode joinExec = new SortMergeJoinExec(left, right, keys.leftKeys(), keys.rightKeys(), joinType);
                        yield wrapResidual(joinExec, keys.residual());
                    }
                    ExecNode joinExec = new HashJoinExec(left, right, keys.leftKeys(), keys.rightKeys(), joinType);
                    yield wrapResidual(joinExec, keys.residual());
                }
                yield new NestedLoopJoinExec(left, right, join.condition(), joinType);
            }
            case SORT_MERGE -> {
                if (keys != null) {
                    ExecNode joinExec = new SortMergeJoinExec(left, right, keys.leftKeys(), keys.rightKeys(), joinType);
                    yield wrapResidual(joinExec, keys.residual());
                }
                yield new NestedLoopJoinExec(left, right, join.condition(), joinType);
            }
            case INDEX_NESTED_LOOP -> {
                // IndexNL 仅支持 INNER JOIN（OUTER 需要追踪未匹配行，IndexNL 无法做到）
                if (joinType != cn.zhangyis.minidb.sql.ast.JoinType.INNER) {
                    if (keys != null) {
                        ExecNode joinExec = new HashJoinExec(left, right, keys.leftKeys(), keys.rightKeys(), joinType);
                        yield wrapResidual(joinExec, keys.residual());
                    }
                    yield new NestedLoopJoinExec(left, right, join.condition(), joinType);
                }
                ExecNode exec = (keys != null && keys.isSingleKey()) ? planIndexJoin(join, left, right, keys) : null;
                if (exec != null) {
                    yield wrapResidual(exec, keys.residual());
                }
                if (keys != null) {
                    ExecNode joinExec = new HashJoinExec(left, right, keys.leftKeys(), keys.rightKeys());
                    yield wrapResidual(joinExec, keys.residual());
                }
                yield new NestedLoopJoinExec(left, right, join.condition());
            }
        };
    }

    // ==================== Semi/Anti-Join ====================

    private ExecNode planSemiJoin(RelSemiJoin semi, JoinAlgorithm overrideAlgo) {
        ExecNode left = planInternal(semi.left(), overrideAlgo);
        ExecNode right = planInternal(semi.right(), overrideAlgo);
        JoinKeys keys = extractSemiAntiJoinKeys(semi.condition());
        if (keys != null) {
            ExecNode exec = new SemiHashJoinExec(left, right, keys.leftKeys(), keys.rightKeys());
            return wrapResidual(exec, keys.residual());
        }
        // 无法提取 equi-key → 退化为 NestedLoop（逐行检查）
        return new FilterExec(
            new NestedLoopJoinExec(left, right, semi.condition()),
            null, createSubqueryEvaluator(), createSubqueryExecutor());
    }

    private ExecNode planAntiJoin(RelAntiJoin anti, JoinAlgorithm overrideAlgo) {
        ExecNode left = planInternal(anti.left(), overrideAlgo);
        ExecNode right = planInternal(anti.right(), overrideAlgo);
        JoinKeys keys = extractSemiAntiJoinKeys(anti.condition());
        if (keys != null) {
            ExecNode exec = new AntiHashJoinExec(left, right, keys.leftKeys(), keys.rightKeys());
            return wrapResidual(exec, keys.residual());
        }
        return new FilterExec(
            new NestedLoopJoinExec(left, right, anti.condition()),
            null, createSubqueryEvaluator(), createSubqueryExecutor());
    }

    /**
     * 从 Semi/Anti-Join 条件中直接提取 equi-key 对。
     * 条件由 SubqueryUnnestingRule 构造，格式固定：左 key 在 left，右 key 在 right。
     */
    private JoinKeys extractSemiAntiJoinKeys(SqlNode condition) {
        List<String> leftKeys = new ArrayList<>();
        List<String> rightKeys = new ArrayList<>();
        List<SqlNode> residuals = new ArrayList<>();
        collectSemiAntiEquiPairs(condition, leftKeys, rightKeys, residuals);
        if (leftKeys.isEmpty()) return null;
        SqlNode residual = null;
        for (SqlNode r : residuals) {
            residual = residual == null ? r : new SqlBinaryOp(SqlKind.AND, residual, r);
        }
        return new JoinKeys(leftKeys, rightKeys, residual);
    }

    private void collectSemiAntiEquiPairs(SqlNode node,
                                           List<String> leftKeys, List<String> rightKeys,
                                           List<SqlNode> residuals) {
        if (node instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.AND) {
            collectSemiAntiEquiPairs(binOp.left(), leftKeys, rightKeys, residuals);
            collectSemiAntiEquiPairs(binOp.right(), leftKeys, rightKeys, residuals);
            return;
        }
        if (node instanceof SqlBinaryOp binOp
            && binOp.kind() == SqlKind.BINARY_EQ
            && binOp.left() instanceof SqlIdentifier leftId
            && binOp.right() instanceof SqlIdentifier rightId) {
            leftKeys.add(leftId.name());
            rightKeys.add(rightId.name());
            return;
        }
        residuals.add(node);
    }

    /**
     * 从投影列表中提取窗口函数规格
     */
    private List<WindowExec.WindowSpec> extractWindowSpecs(SqlNodeList projection) {
        List<WindowExec.WindowSpec> specs = new ArrayList<>();
        for (SqlNode node : projection.nodes()) {
            SqlNode expr = node instanceof SqlAlias alias ? alias.expression() : node;
            if (expr instanceof SqlWindowFunction wf) {
                String label = node instanceof SqlAlias alias ? alias.alias() : wf.toString();
                specs.add(new WindowExec.WindowSpec(wf.funcName(), label, wf.partitionBy(), wf.orderBy(), wf.arg(), wf.extraArgs()));
            }
        }
        return specs;
    }

    /** 有 residual 条件时包一层 FilterExec，否则原样返回 */
    private ExecNode wrapResidual(ExecNode joinExec, SqlNode residual) {
        if (residual == null) return joinExec;
        return new FilterExec(joinExec, residual);
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
                true,
                executionContext  // 传递事务上下文
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
                false,
                executionContext  // 传递事务上下文
            );
        }
        return null;
    }

    private JoinKeys extractJoinKeys(RelJoin join) {
        List<String> leftKeys = new ArrayList<>();
        List<String> rightKeys = new ArrayList<>();
        List<SqlNode> residuals = new ArrayList<>();
        collectEquiPairs(join.condition(), join, leftKeys, rightKeys, residuals);
        if (leftKeys.isEmpty()) return null;
        // 将剩余 residual 条件合并为 AND 链
        SqlNode residual = null;
        for (SqlNode r : residuals) {
            residual = residual == null ? r : new SqlBinaryOp(SqlKind.AND, residual, r);
        }
        return new JoinKeys(leftKeys, rightKeys, residual);
    }

    /**
     * 递归收集 AND 链中的 equi-key 对，非 equi 条件收集到 residuals。
     */
    private void collectEquiPairs(SqlNode node, RelJoin join,
                                  List<String> leftKeys, List<String> rightKeys,
                                  List<SqlNode> residuals) {
        if (!(node instanceof SqlBinaryOp binOp)) {
            residuals.add(node);
            return;
        }

        if (binOp.kind() == SqlKind.AND) {
            collectEquiPairs(binOp.left(), join, leftKeys, rightKeys, residuals);
            collectEquiPairs(binOp.right(), join, leftKeys, rightKeys, residuals);
            return;
        }

        if (binOp.kind() == SqlKind.BINARY_EQ
                && binOp.left() instanceof SqlIdentifier first
                && binOp.right() instanceof SqlIdentifier second) {
            String firstName = first.name();
            String secondName = second.name();
            boolean firstQualified = isQualified(firstName);
            boolean secondQualified = isQualified(secondName);

            if (firstQualified == secondQualified) {
                boolean added = false;
                if (firstQualified) {
                    JoinKeys aligned = alignQualifiedKeys(join, firstName, secondName);
                    if (aligned != null) {
                        leftKeys.add(aligned.leftKey());
                        rightKeys.add(aligned.rightKey());
                        added = true;
                    }
                } else if (isSafeBareSameNameEquiJoin(join, firstName, secondName)) {
                    leftKeys.add(firstName);
                    rightKeys.add(secondName);
                    added = true;
                }
                if (added) return;
            }
        }
        // 非 equi-key 条件 → residual
        residuals.add(node);
    }

    private JoinKeys alignQualifiedKeys(RelJoin join, String firstName, String secondName) {
        boolean firstOnLeft = identifierBelongsTo(join.left(), firstName);
        boolean secondOnRight = identifierBelongsTo(join.right(), secondName);

        if (firstOnLeft && secondOnRight) {
            return JoinKeys.single(firstName, secondName);
        }
        boolean secondOnLeft = identifierBelongsTo(join.left(), secondName);
        boolean firstOnRight = identifierBelongsTo(join.right(), firstName);
        if (secondOnLeft && firstOnRight) {
            return JoinKeys.single(secondName, firstName);
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
        if (node instanceof RelSemiJoin semi) {
            return identifierBelongsTo(semi.left(), identifier);
        }
        if (node instanceof RelAntiJoin anti) {
            return identifierBelongsTo(anti.left(), identifier);
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

    private List<String> extractGroupByColumnNames(SqlNodeList groupKeys) {
        if (groupKeys == null) return List.of();
        List<String> cols = new ArrayList<>();
        for (SqlNode node : groupKeys.nodes()) {
            if (node instanceof SqlIdentifier id) {
                cols.add(id.name());
            } else {
                cols.add(node.toString());
            }
        }
        return cols;
    }

    /**
     * 创建 EXISTS/IN 子查询执行器：将 SqlSelect 编译为 ExecNode
     */
    private FilterExec.SubqueryExecutor createSubqueryExecutor() {
        CatalogSpi cat = this.catalog != null ? this.catalog : new MockCatalog();
        return (SqlSelect select, Row outerRow) -> {
            // 通用相关子查询 decorrelation：将外层引用替换为字面量
            if (outerRow != null && select.where() != null) {
                SqlNode newWhere = decorrelateWhere(select.where(), outerRow);
                if (newWhere != select.where()) {
                    select = new SqlSelect(select.projection(), select.from(), newWhere,
                            select.distinct(), select.groupBy(), select.having(),
                            select.orderBy(), select.limit(), select.offset());
                }
            }

            SqlValidator validator = new SqlValidator(cat);
            SqlNode validated = validator.validate(select);
            SqlToRelConverter converter = new SqlToRelConverter(cat);
            RelNode rel = converter.convert(validated);
            return new PhysicalPlanner(costOptimizer, dataSource, cat).plan(rel);
        };
    }

    // ==================== Decorrelation 辅助方法 ====================

    /**
     * 递归遍历 WHERE 子句 AST，将属于外层行的 SqlIdentifier 替换为 SqlLiteral。
     * 使用引用相等（==）判断是否发生替换，避免不必要的 AST 重建。
     */
    private static SqlNode decorrelateWhere(SqlNode node, Row outerRow) {
        if (node == null) return null;

        if (node instanceof SqlIdentifier id) {
            String name = id.name();
            if (outerRowContains(outerRow, name)) {
                Object val = outerRow.get(name);
                return valueToLiteral(val);
            }
            return node;
        }

        if (node instanceof SqlBinaryOp binOp) {
            SqlNode newLeft = decorrelateWhere(binOp.left(), outerRow);
            SqlNode newRight = decorrelateWhere(binOp.right(), outerRow);
            if (newLeft == binOp.left() && newRight == binOp.right()) return node;
            return new SqlBinaryOp(binOp.kind(), newLeft, newRight);
        }

        if (node instanceof SqlBetween between) {
            SqlNode newExpr = decorrelateWhere(between.expr(), outerRow);
            SqlNode newLow = decorrelateWhere(between.low(), outerRow);
            SqlNode newHigh = decorrelateWhere(between.high(), outerRow);
            if (newExpr == between.expr() && newLow == between.low() && newHigh == between.high()) return node;
            return new SqlBetween(newExpr, newLow, newHigh);
        }

        // SqlLiteral, SqlAggCall, SqlStar 等叶子节点 → 原样返回
        return node;
    }

    /**
     * 判断标识符是否属于外层行（与 Row.get() 的解析逻辑一致）。
     * 支持 qualified（table.column）和 bare（column）两种格式匹配。
     */
    private static boolean outerRowContains(Row outerRow, String name) {
        for (String key : outerRow.columns().keySet()) {
            // 完全匹配（如 "users.id" == "users.id"）
            if (key.equalsIgnoreCase(name)) return true;
            // bare name 匹配 qualified key 的列部分（如 "id" 匹配 "users.id"）
            if (!name.contains(".") && key.contains(".")
                    && key.substring(key.indexOf('.') + 1).equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 Java 值转为 SqlLiteral。
     */
    private static SqlNode valueToLiteral(Object value) {
        if (value == null) {
            return (SqlNode) () -> SqlKind.NULL_LITERAL;
        }
        if (value instanceof Integer i) {
            return new SqlLiteral(String.valueOf(i), SqlType.INT32);
        }
        if (value instanceof Long l) {
            return new SqlLiteral(String.valueOf(l), SqlType.BIGINT);
        }
        if (value instanceof Double d) {
            return new SqlLiteral(String.valueOf(d), SqlType.DECIMAL);
        }
        if (value instanceof String s) {
            return new SqlLiteral(s, SqlType.VARCHAR);
        }
        // 兜底：转为字符串
        return new SqlLiteral(String.valueOf(value), SqlType.VARCHAR);
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
