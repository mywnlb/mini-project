package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.rel.*;

/**
 * 物理计划生成器：RelNode → ExecNode
 * 支持两种模式：
 * 1. 手动指定 JOIN 算法
 * 2. 基于 CBO 自动选择 JOIN 算法（默认）
 */
public class PhysicalPlanner {

    public enum JoinAlgorithm {
        NESTED_LOOP, HASH_JOIN, SORT_MERGE, INDEX_NESTED_LOOP
    }

    private final CostOptimizer costOptimizer;
    private final DataSourceSpi dataSource;
    private final CatalogSpi catalog;

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
        if (relNode instanceof RelIndexedScan indexed) {
            ExecNode scan = new ScanExec(indexed.tableName(), indexed.outputName(), dataSource);
            return new FilterExec(scan, indexed.indexCondition());
        }
        if (relNode instanceof RelScan scan) {
            return new ScanExec(scan.tableName(), scan.outputName(), dataSource);
        }
        if (relNode instanceof RelFilter filter) {
            ExecNode input = planInternal(filter.input(), overrideAlgo);
            return new FilterExec(input, filter.condition());
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
            if (sort.limit() instanceof SqlLiteral lit) {
                limit = Integer.parseInt(lit.value());
            }
            return new SortExec(input, sort.orderBy(), limit);
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

        String[] keys = extractJoinKeys(join.condition());

        return switch (algo) {
            case NESTED_LOOP -> new NestedLoopJoinExec(left, right, join.condition());
            case HASH_JOIN -> {
                if (keys != null) {
                    yield new HashJoinExec(left, right, keys[0], keys[1]);
                }
                yield new NestedLoopJoinExec(left, right, join.condition());
            }
            case SORT_MERGE -> {
                if (keys != null) {
                    yield new SortMergeJoinExec(left, right, keys[0], keys[1]);
                }
                yield new NestedLoopJoinExec(left, right, join.condition());
            }
            case INDEX_NESTED_LOOP -> {
                if (keys != null) {
                    yield new IndexNestedLoopJoinExec(left, right, keys[0], keys[1]);
                }
                yield new NestedLoopJoinExec(left, right, join.condition());
            }
        };
    }

    private String[] extractJoinKeys(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.BINARY_EQ) {
            if (binOp.left() instanceof SqlIdentifier left && binOp.right() instanceof SqlIdentifier right) {
                return new String[]{left.name(), right.name()};
            }
        }
        return null;
    }
}
