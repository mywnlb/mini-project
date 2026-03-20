package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.*;

/**
 * 投影裁剪规则：
 * 1. 消除恒等投影（SELECT * 直接返回 input）
 * 2. 合并连续 Project
 * 3. Push Projection Below Join：在 Join 两侧插入窄化投影
 */
public class ProjectionPruningRule extends RelOptRule {

    public static final ProjectionPruningRule INSTANCE = new ProjectionPruningRule();

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelProject outer)) return false;
        if (isStarProjection(outer.projection())) return true;
        if (outer.input() instanceof RelProject) return true;
        // Project over Join: 可以尝试窄化
        if (outer.input() instanceof RelJoin) return true;
        return false;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelProject outer = (RelProject) node;

        // 消除 SELECT * → 直接返回 input
        if (isStarProjection(outer.projection())) {
            return outer.input();
        }

        // 合并连续 Project
        if (outer.input() instanceof RelProject inner) {
            if (isStarProjection(inner.projection())) {
                return new RelProject(inner.input(), outer.projection());
            }
            return new RelProject(inner.input(), outer.projection());
        }

        // Push Projection Below Join
        if (outer.input() instanceof RelJoin join) {
            return pushProjectBelowJoin(outer, join);
        }

        return node;
    }

    private RelNode pushProjectBelowJoin(RelProject project, RelJoin join) {
        // 收集投影和 JOIN 条件引用的所有标识符
        Set<String> referencedCols = new LinkedHashSet<>();
        collectIdentifiers(project.projection(), referencedCols);
        if (join.condition() != null) {
            collectIdentifiers(join.condition(), referencedCols);
        }

        // 收集左右两侧的可用列
        Set<String> leftCols = collectScanColumns(join.left());
        Set<String> rightCols = collectScanColumns(join.right());

        if (leftCols.isEmpty() || rightCols.isEmpty()) {
            return project; // 无法确定列归属，保守不裁剪
        }

        // 按归属分配引用列到左右两侧
        Set<String> leftNeeded = new LinkedHashSet<>();
        Set<String> rightNeeded = new LinkedHashSet<>();
        for (String col : referencedCols) {
            String bare = bareColumn(col);
            String qualifier = qualifier(col);
            if (qualifier != null) {
                if (matchesSource(qualifier, bare, leftCols, join.left())) {
                    leftNeeded.add(col);
                } else if (matchesSource(qualifier, bare, rightCols, join.right())) {
                    rightNeeded.add(col);
                }
            } else {
                // 无限定符：按左优先匹配
                if (containsBare(leftCols, bare)) {
                    leftNeeded.add(col);
                } else if (containsBare(rightCols, bare)) {
                    rightNeeded.add(col);
                }
            }
        }

        boolean leftNarrowed = leftNeeded.size() < leftCols.size();
        boolean rightNarrowed = rightNeeded.size() < rightCols.size();

        if (!leftNarrowed && !rightNarrowed) {
            return project; // 无裁剪效果
        }

        RelNode newLeft = leftNarrowed ? buildNarrowProject(join.left(), leftNeeded) : join.left();
        RelNode newRight = rightNarrowed ? buildNarrowProject(join.right(), rightNeeded) : join.right();

        RelJoin newJoin = new RelJoin(newLeft, newRight, join.condition(), join.joinType());
        return new RelProject(newJoin, project.projection());
    }

    private void collectIdentifiers(SqlNode node, Set<String> identifiers) {
        if (node == null) return;
        if (node instanceof SqlIdentifier id) {
            identifiers.add(id.name());
            return;
        }
        if (node instanceof SqlAlias alias) {
            collectIdentifiers(alias.expression(), identifiers);
            return;
        }
        if (node instanceof SqlBinaryOp binOp) {
            collectIdentifiers(binOp.left(), identifiers);
            collectIdentifiers(binOp.right(), identifiers);
            return;
        }
        if (node instanceof SqlNodeList list) {
            for (SqlNode child : list.nodes()) collectIdentifiers(child, identifiers);
            return;
        }
        if (node instanceof SqlAggCall agg) {
            collectIdentifiers(agg.arg(), identifiers);
            return;
        }
        if (node instanceof SqlFunctionCall fc) {
            for (SqlNode arg : fc.arguments().nodes()) collectIdentifiers(arg, identifiers);
            return;
        }
        if (node instanceof SqlCase c) {
            for (SqlCase.WhenThen wt : c.whenThens()) {
                collectIdentifiers(wt.condition(), identifiers);
                collectIdentifiers(wt.result(), identifiers);
            }
            if (c.elseExpr() != null) collectIdentifiers(c.elseExpr(), identifiers);
            return;
        }
        if (node instanceof SqlCast cast) {
            collectIdentifiers(cast.expr(), identifiers);
            return;
        }
        if (node instanceof SqlBetween between) {
            collectIdentifiers(between.expr(), identifiers);
            collectIdentifiers(between.low(), identifiers);
            collectIdentifiers(between.high(), identifiers);
            return;
        }
        if (node instanceof SqlInList inList) {
            collectIdentifiers(inList.expr(), identifiers);
            for (SqlNode v : inList.values().nodes()) collectIdentifiers(v, identifiers);
            return;
        }
        if (node instanceof SqlOrderByItem item) {
            collectIdentifiers(item.column(), identifiers);
        }
    }

    private void collectIdentifiers(SqlNodeList list, Set<String> identifiers) {
        for (SqlNode node : list.nodes()) collectIdentifiers(node, identifiers);
    }

    private Set<String> collectScanColumns(RelNode node) {
        Set<String> cols = new LinkedHashSet<>();
        collectScanColumnsImpl(node, cols);
        return cols;
    }

    private void collectScanColumnsImpl(RelNode node, Set<String> cols) {
        if (node instanceof RelScan scan) {
            for (ColumnMeta cm : scan.tableMeta().columns()) {
                cols.add(scan.outputName() + "." + cm.name());
            }
        } else if (node instanceof RelFilter filter) {
            collectScanColumnsImpl(filter.input(), cols);
        } else if (node instanceof RelProject proj) {
            collectScanColumnsImpl(proj.input(), cols);
        } else if (node instanceof RelSort sort) {
            collectScanColumnsImpl(sort.input(), cols);
        }
    }

    private boolean matchesSource(String qualifier, String bare, Set<String> sourceCols, RelNode node) {
        String qualified = qualifier + "." + bare;
        for (String col : sourceCols) {
            if (col.equalsIgnoreCase(qualified)) return true;
        }
        return false;
    }

    private boolean containsBare(Set<String> qualifiedCols, String bare) {
        for (String col : qualifiedCols) {
            String colBare = bareColumn(col);
            if (colBare.equalsIgnoreCase(bare)) return true;
        }
        return false;
    }

    private String bareColumn(String col) {
        int dot = col.indexOf('.');
        return dot >= 0 ? col.substring(dot + 1) : col;
    }

    private String qualifier(String col) {
        int dot = col.indexOf('.');
        return dot >= 0 ? col.substring(0, dot) : null;
    }

    private RelNode buildNarrowProject(RelNode input, Set<String> neededCols) {
        SqlNodeList narrowProjection = new SqlNodeList();
        for (String col : neededCols) {
            narrowProjection.add(new SqlIdentifier(col));
        }
        if (narrowProjection.size() == 0) return input;
        return new RelProject(input, narrowProjection);
    }

    private boolean isStarProjection(SqlNodeList projection) {
        return projection.size() == 1 && projection.get(0).kind() == SqlKind.STAR;
    }
}
