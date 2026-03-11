package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

/**
 * 物理计划生成器：RelNode → ExecNode
 * 根据代价模型选择 Join 算法
 */
public class PhysicalPlanner {

    public enum JoinAlgorithm {
        NESTED_LOOP, HASH_JOIN, SORT_MERGE, INDEX_NESTED_LOOP
    }

    public ExecNode plan(RelNode relNode) {
        return plan(relNode, JoinAlgorithm.HASH_JOIN); // 默认 Hash Join
    }

    public ExecNode plan(RelNode relNode, JoinAlgorithm joinAlgo) {
        if (relNode instanceof RelScan scan) {
            return new ScanExec(scan.tableName());
        }
        if (relNode instanceof RelIndexedScan indexed) {
            // IndexedScan = Scan + Filter
            ExecNode scan = new ScanExec(indexed.tableName());
            return new FilterExec(scan, indexed.indexCondition());
        }
        if (relNode instanceof RelFilter filter) {
            ExecNode input = plan(filter.input(), joinAlgo);
            return new FilterExec(input, filter.condition());
        }
        if (relNode instanceof RelProject project) {
            ExecNode input = plan(project.input(), joinAlgo);
            return new ProjectExec(input, project.projection());
        }
        if (relNode instanceof RelJoin join) {
            return planJoin(join, joinAlgo);
        }
        if (relNode instanceof RelAggregate agg) {
            ExecNode input = plan(agg.input(), joinAlgo);
            return new AggregateExec(input, agg.groupKeys(), agg.aggCalls());
        }
        if (relNode instanceof RelSort sort) {
            ExecNode input = plan(sort.input(), joinAlgo);
            Integer limit = null;
            if (sort.limit() instanceof SqlLiteral lit) {
                limit = Integer.parseInt(lit.value());
            }
            return new SortExec(input, sort.orderBy(), limit);
        }
        if (relNode instanceof RelInsert || relNode instanceof RelUpdate || relNode instanceof RelDelete) {
            // DML 暂不执行，返回空扫描
            return new ExecNode() {
                @Override public void open() {}
                @Override public Row next() { return null; }
                @Override public void close() {}
            };
        }
        throw new IllegalArgumentException("Unknown RelNode: " + relNode.getClass().getSimpleName());
    }

    private ExecNode planJoin(RelJoin join, JoinAlgorithm algo) {
        ExecNode left = plan(join.left(), algo);
        ExecNode right = plan(join.right(), algo);

        // 尝试提取等值 join key
        String[] keys = extractJoinKeys(join.condition());

        return switch (algo) {
            case NESTED_LOOP -> new NestedLoopJoinExec(left, right, join.condition());
            case HASH_JOIN -> {
                if (keys != null) {
                    yield new HashJoinExec(left, right, keys[0], keys[1]);
                }
                yield new NestedLoopJoinExec(left, right, join.condition()); // fallback
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

    /**
     * 从 ON 条件中提取等值 join key
     * 例如: users.id = orders.user_id → ["USERS.ID", "ORDERS.USER_ID"]
     */
    private String[] extractJoinKeys(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.BINARY_EQ) {
            if (binOp.left() instanceof SqlIdentifier left && binOp.right() instanceof SqlIdentifier right) {
                return new String[]{left.name(), right.name()};
            }
        }
        return null;
    }
}
