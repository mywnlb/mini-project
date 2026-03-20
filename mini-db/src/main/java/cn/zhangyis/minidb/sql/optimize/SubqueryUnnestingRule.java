package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.*;

/**
 * 子查询去关联化规则：将 IN/EXISTS 子查询转换为 Semi-Join / Anti-Join。
 *
 * <p>转换前：RelFilter(input, condition 包含 SqlInSubquery/SqlExists)
 * <p>转换后：RelSemiJoin/RelAntiJoin(input, subqueryRel, equiCondition)
 *
 * <p>参考 PolarDB-X 子查询去关联化策略：IN → SemiJoin，NOT IN → AntiJoin。
 * 对于 OR 中的子查询和非等值关联条件，保留原 FilterExec 迭代执行。
 */
public class SubqueryUnnestingRule extends RelOptRule {
    private final CatalogSpi catalog;

    public SubqueryUnnestingRule(CatalogSpi catalog) {
        this.catalog = catalog;
    }

    @Override
    public boolean matches(RelNode node) {
        return node instanceof RelFilter filter
            && containsSubquery(filter.condition());
    }

    @Override
    public RelNode apply(RelNode node) {
        RelFilter filter = (RelFilter) node;
        List<SqlNode> conditions = flattenAnd(filter.condition());

        RelNode current = filter.input();
        List<SqlNode> remaining = new ArrayList<>();

        for (SqlNode cond : conditions) {
            if (cond instanceof SqlInSubquery inSub) {
                RelNode converted = convertInSubquery(current, inSub);
                if (converted != null) {
                    current = converted;
                } else {
                    remaining.add(cond);
                }
            } else if (cond instanceof SqlExists exists) {
                RelNode converted = convertExists(current, exists);
                if (converted != null) {
                    current = converted;
                } else {
                    remaining.add(cond);
                }
            } else {
                remaining.add(cond);
            }
        }

        // 剩余非子查询条件保留为 Filter
        if (!remaining.isEmpty()) {
            current = new RelFilter(current, buildAnd(remaining));
        }

        return current;
    }

    // ==================== IN 子查询转换 ====================

    private RelNode convertInSubquery(RelNode left, SqlInSubquery inSub) {
        // 左侧 key
        if (!(inSub.expr() instanceof SqlIdentifier leftId)) return null;
        String leftKey = leftId.name();

        SqlSelect select = inSub.select();

        // 子查询必须返回单列
        if (select.projection().size() != 1) return null;

        // 不支持复杂子查询（GROUP BY/HAVING/ORDER BY/LIMIT）
        if (select.groupBy() != null || select.having() != null
            || select.orderBy() != null || select.limit() != null) return null;

        // 右侧 key: 子查询 projection 第一列
        SqlNode firstProj = unwrapAlias(select.projection().get(0));
        if (firstProj.kind() == SqlKind.STAR) return null;
        if (!(firstProj instanceof SqlIdentifier rightId)) return null;
        String rightKey = rightId.name();

        // 构建右侧 RelNode
        RelNode right = buildRightSide(select.from());
        if (right == null) return null;

        // 构建 equi 条件
        SqlNode joinCond = new SqlBinaryOp(SqlKind.BINARY_EQ,
            new SqlIdentifier(leftKey), new SqlIdentifier(rightKey));

        // 子查询 WHERE → 右侧 Filter（非关联 IN 子查询的 WHERE 都是 local 条件）
        if (select.where() != null) {
            right = new RelFilter(right, select.where());
        }

        return inSub.negated()
            ? new RelAntiJoin(left, right, joinCond)
            : new RelSemiJoin(left, right, joinCond);
    }

    // ==================== EXISTS 子查询转换 ====================

    private RelNode convertExists(RelNode left, SqlExists exists) {
        SqlSelect select = exists.select();

        // EXISTS 必须有 WHERE（否则是无关联 EXISTS，退化为 constant）
        if (select.where() == null) return null;

        // 不支持复杂子查询
        if (select.groupBy() != null || select.having() != null
            || select.orderBy() != null || select.limit() != null) return null;

        // 构建右侧 RelNode
        RelNode right = buildRightSide(select.from());
        if (right == null) return null;

        // 分离关联条件和本地条件
        String leftTable = findOutputName(left);
        String rightTable = findOutputName(right);
        if (leftTable == null || rightTable == null) return null;

        CorrelationResult corr = extractCorrelation(select.where(), leftTable, rightTable);
        if (corr.correlatedCondition == null) return null; // 无关联，不转换

        // 本地条件 → 右侧 Filter
        if (corr.localFilter != null) {
            right = new RelFilter(right, corr.localFilter);
        }

        return exists.negated()
            ? new RelAntiJoin(left, right, corr.correlatedCondition)
            : new RelSemiJoin(left, right, corr.correlatedCondition);
    }

    // ==================== 辅助方法 ====================

    private RelNode buildRightSide(SqlNode from) {
        if (from == null) return null;

        String tableName;
        String outputName;
        if (from instanceof SqlTableRef ref) {
            tableName = ref.tableName();
            outputName = ref.visibleName();
        } else if (from instanceof SqlIdentifier id) {
            tableName = id.name();
            outputName = id.name();
        } else {
            return null; // JOIN 或子查询 FROM，不支持
        }

        TableMeta meta = catalog.getTable(tableName);
        if (meta == null) return null;

        return new RelScan(tableName, meta, outputName.toUpperCase());
    }

    /**
     * 分离关联条件（跨表 equi 条件）和本地条件（仅引用内层表）。
     * 关联条件用于 Semi-Join 的 join condition。
     */
    private CorrelationResult extractCorrelation(SqlNode where, String leftTable, String rightTable) {
        List<SqlNode> all = flattenAnd(where);
        List<SqlNode> correlated = new ArrayList<>();
        List<SqlNode> local = new ArrayList<>();

        for (SqlNode cond : all) {
            SqlNode normalized = normalizeCorrelatedCondition(cond, leftTable, rightTable);
            if (normalized != null) {
                correlated.add(normalized);
            } else {
                local.add(cond);
            }
        }

        return new CorrelationResult(buildAnd(correlated), buildAnd(local));
    }

    /**
     * 如果条件是关联等值条件，返回规范化版本（外层表标识符在左，内层表在右）。
     * 返回 null 表示不是关联等值条件。
     * 规范化保证 PhysicalPlanner 可以直接将条件左侧作为 leftKey、右侧作为 rightKey。
     */
    private SqlNode normalizeCorrelatedCondition(SqlNode cond, String leftTable, String rightTable) {
        if (!(cond instanceof SqlBinaryOp binOp)) return null;
        if (binOp.kind() != SqlKind.BINARY_EQ) return null;
        if (!(binOp.left() instanceof SqlIdentifier leftId)
            || !(binOp.right() instanceof SqlIdentifier rightId)) return null;

        // 检查: 左=外层, 右=内层 → 已是正确顺序
        if (referencesTable(leftId.name(), leftTable)
            && referencesTable(rightId.name(), rightTable)) {
            return cond;
        }
        // 检查: 左=内层, 右=外层 → 需要交换
        if (referencesTable(leftId.name(), rightTable)
            && referencesTable(rightId.name(), leftTable)) {
            return new SqlBinaryOp(SqlKind.BINARY_EQ, rightId, leftId);
        }
        return null;
    }

    private boolean referencesTable(String identifier, String tableName) {
        if (identifier.contains(".")) {
            String qualifier = identifier.substring(0, identifier.indexOf('.'));
            return qualifier.equalsIgnoreCase(tableName);
        }
        return false; // 无限定符无法确定属于哪个表
    }

    private String findOutputName(RelNode node) {
        if (node instanceof RelScan scan) return scan.outputName();
        if (node instanceof RelFilter f) return findOutputName(f.input());
        if (node instanceof RelProject p) return findOutputName(p.input());
        if (node instanceof RelDistinct d) return findOutputName(d.input());
        if (node instanceof RelSort s) return findOutputName(s.input());
        if (node instanceof RelAggregate a) return findOutputName(a.input());
        return null;
    }

    private boolean containsSubquery(SqlNode node) {
        if (node instanceof SqlInSubquery || node instanceof SqlExists) return true;
        if (node instanceof SqlBinaryOp b) {
            return containsSubquery(b.left()) || containsSubquery(b.right());
        }
        return false;
    }

    private List<SqlNode> flattenAnd(SqlNode condition) {
        List<SqlNode> result = new ArrayList<>();
        flattenAndInternal(condition, result);
        return result.isEmpty() ? List.of(condition) : result;
    }

    private void flattenAndInternal(SqlNode node, List<SqlNode> result) {
        if (node instanceof SqlBinaryOp bin && bin.kind() == SqlKind.AND) {
            flattenAndInternal(bin.left(), result);
            flattenAndInternal(bin.right(), result);
        } else {
            result.add(node);
        }
    }

    private SqlNode buildAnd(List<SqlNode> conditions) {
        if (conditions.isEmpty()) return null;
        if (conditions.size() == 1) return conditions.get(0);
        SqlNode result = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            result = new SqlBinaryOp(SqlKind.AND, result, conditions.get(i));
        }
        return result;
    }

    private SqlNode unwrapAlias(SqlNode node) {
        return node instanceof SqlAlias alias ? alias.expression() : node;
    }

    private record CorrelationResult(SqlNode correlatedCondition, SqlNode localFilter) {}
}
