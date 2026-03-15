package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.SqlBinaryOp;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.rel.RelFilter;
import cn.zhangyis.minidb.sql.rel.RelIndexedScan;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.rel.RelScan;

/**
 * Filter(Scan) -> IndexedScan，仅在真实主键 lookup 路径启用时使用。
 */
public class PushFilterIntoScanRule extends RelOptRule {
    public static final PushFilterIntoScanRule INSTANCE = new PushFilterIntoScanRule(false);

    private final boolean enabled;

    public PushFilterIntoScanRule() {
        this(true);
    }

    private PushFilterIntoScanRule(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean matches(RelNode node) {
        return enabled && split(node) != null;
    }

    @Override
    public RelNode apply(RelNode node) {
        IndexSplit split = split(node);
        if (split == null) {
            return node;
        }

        RelIndexedScan indexedScan = new RelIndexedScan(
            split.scan().tableName(),
            split.scan().tableMeta(),
            split.scan().outputName(),
            split.indexCondition()
        );
        if (split.residualCondition() == null) {
            return indexedScan;
        }
        return new RelFilter(indexedScan, split.residualCondition());
    }

    private IndexSplit split(RelNode node) {
        if (!(node instanceof RelFilter filter) || !(filter.input() instanceof RelScan scan)) {
            return null;
        }
        ConditionSplit conditionSplit = extractIndexCondition(scan, filter.condition());
        if (conditionSplit == null) {
            return null;
        }
        return new IndexSplit(scan, conditionSplit.indexCondition(), conditionSplit.residualCondition());
    }

    private ConditionSplit extractIndexCondition(RelScan scan, SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.AND) {
            ConditionSplit left = extractIndexCondition(scan, binOp.left());
            ConditionSplit right = extractIndexCondition(scan, binOp.right());

            if (left != null && right == null) {
                return new ConditionSplit(left.indexCondition(), combineResidual(left.residualCondition(), binOp.right()));
            }
            if (left == null && right != null) {
                return new ConditionSplit(right.indexCondition(), combineResidual(binOp.left(), right.residualCondition()));
            }
            return null;
        }

        return isPrimaryLookupCondition(scan, condition)
            ? new ConditionSplit(condition, null)
            : null;
    }

    private SqlNode combineResidual(SqlNode left, SqlNode right) {
        if (left == null) return right;
        if (right == null) return left;
        return new SqlBinaryOp(SqlKind.AND, left, right);
    }

    private boolean isPrimaryLookupCondition(RelScan scan, SqlNode condition) {
        if (!(condition instanceof SqlBinaryOp binOp) || binOp.kind() != SqlKind.BINARY_EQ) {
            return false;
        }

        if (matchesLookupSide(scan, binOp.left(), binOp.right())) {
            return true;
        }
        return matchesLookupSide(scan, binOp.right(), binOp.left());
    }

    private boolean matchesLookupSide(RelScan scan, SqlNode lookupSide, SqlNode constantSide) {
        if (!(lookupSide instanceof SqlIdentifier id) || referencesRow(constantSide)) {
            return false;
        }
        return isPrimaryColumn(scan, id.name());
    }

    private boolean isPrimaryColumn(RelScan scan, String identifier) {
        String columnName = identifier;
        if (identifier.contains(".")) {
            String[] parts = identifier.split("\\.", 2);
            if (!scan.outputName().equalsIgnoreCase(parts[0])) {
                return false;
            }
            columnName = parts[1];
        }
        String col = columnName;
        return scan.tableMeta().columns().stream()
            .anyMatch(column -> column.isPrimaryKey() && column.name().equalsIgnoreCase(col));
    }

    private boolean referencesRow(SqlNode node) {
        if (node instanceof SqlIdentifier || node instanceof cn.zhangyis.minidb.sql.ast.SqlAggCall) {
            return true;
        }
        if (node instanceof SqlBinaryOp binOp) {
            return referencesRow(binOp.left()) || referencesRow(binOp.right());
        }
        if (node instanceof cn.zhangyis.minidb.sql.ast.SqlBetween between) {
            return referencesRow(between.expr()) || referencesRow(between.low()) || referencesRow(between.high());
        }
        if (node instanceof cn.zhangyis.minidb.sql.ast.SqlInList inList) {
            if (referencesRow(inList.expr())) {
                return true;
            }
            for (SqlNode value : inList.values().nodes()) {
                if (referencesRow(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record ConditionSplit(SqlNode indexCondition, SqlNode residualCondition) {
    }

    private record IndexSplit(RelScan scan, SqlNode indexCondition, SqlNode residualCondition) {
    }
}
