package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Sort 下推规则:
 * 1. 将 Sort 穿过 RelProject（如果投影不改变排序列的语义，即排序列都在投影中直传）
 * 2. 对 RelUnion: 将 Sort 下推为各分支的 LocalSort + 上层保留 Sort（MergeSort 在执行层处理）
 */
public class SortPushdownRule extends RelOptRule {

    public static final SortPushdownRule INSTANCE = new SortPushdownRule();

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelSort sort)) return false;
        if (sort.orderBy() == null || sort.orderBy().size() == 0) return false;
        RelNode child = sort.input();
        // 支持穿过 Project 或 下推到 Union
        if (child instanceof RelProject project) {
            // 排序列必须都在投影中直传（简单标识符）
            return orderColumnsPassThrough(sort.orderBy(), project.projection());
        }
        if (child instanceof RelUnion) {
            // 避免重复下推：Union 的子节点已经是 Sort 时跳过
            RelUnion union = (RelUnion) child;
            return !(union.left() instanceof RelSort) && !(union.right() instanceof RelSort);
        }
        return false;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelSort sort = (RelSort) node;
        RelNode child = sort.input();

        if (child instanceof RelProject project) {
            // Sort 穿过 Project: Sort(Project(input)) → Project(Sort(input))
            RelNode newSort = new RelSort(project.input(), sort.orderBy(), sort.limit(), sort.offset());
            return new RelProject(newSort, project.projection());
        }

        if (child instanceof RelUnion union) {
            // Sort 下推到 Union 各分支:
            // Sort(Union(A, B)) → Sort(Union(Sort(A), Sort(B)))
            // 各分支 Sort 带上 limit+offset 截断以减少中间行数
            SqlNode pushdownLimit = computePushdownLimit(sort);
            RelSort leftSort = new RelSort(union.left(), sort.orderBy(), pushdownLimit, null);
            RelSort rightSort = new RelSort(union.right(), sort.orderBy(), pushdownLimit, null);
            RelUnion newUnion = new RelUnion(leftSort, rightSort, union.all(), union.opType());
            return new RelSort(newUnion, sort.orderBy(), sort.limit(), sort.offset());
        }

        return node;
    }

    /**
     * 检查排序列是否都在投影中直传（即投影包含同名列，没有被重命名或转换）
     */
    private boolean orderColumnsPassThrough(SqlNodeList orderBy, SqlNodeList projection) {
        Set<String> projectedNames = new HashSet<>();
        for (SqlNode proj : projection.nodes()) {
            if (proj instanceof SqlIdentifier id) {
                projectedNames.add(id.name().toUpperCase());
            } else if (proj instanceof SqlAlias alias && alias.expression() instanceof SqlIdentifier id) {
                projectedNames.add(id.name().toUpperCase());
            }
            // SELECT * 通配符：假设所有列都传递
            if (proj instanceof SqlIdentifier id && id.name().equals("*")) {
                return true;
            }
        }

        for (SqlNode orderNode : orderBy.nodes()) {
            SqlNode col = orderNode instanceof SqlOrderByItem item ? item.column() : orderNode;
            if (col instanceof SqlIdentifier id) {
                if (!projectedNames.contains(id.name().toUpperCase())) {
                    return false;
                }
            } else {
                // 非简单列引用，不下推
                return false;
            }
        }
        return true;
    }

    /**
     * 计算下推的 LIMIT: limit + offset（如果有的话）
     */
    private SqlNode computePushdownLimit(RelSort sort) {
        if (sort.limit() == null) return null;
        int limit = intValue(sort.limit());
        int offset = sort.offset() != null ? intValue(sort.offset()) : 0;
        int pushdown = limit + offset;
        return new SqlLiteral(String.valueOf(pushdown),
                cn.zhangyis.minidb.sql.types.SqlType.INT32);
    }

    private int intValue(SqlNode node) {
        if (node instanceof SqlLiteral lit) {
            return Integer.parseInt(lit.value());
        }
        return Integer.MAX_VALUE;
    }
}
