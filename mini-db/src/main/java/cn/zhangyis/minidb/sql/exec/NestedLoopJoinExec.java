package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * Nested Loop Join：双层循环
 * 对每一行外表，扫描整个内表
 * 时间复杂度: O(M * N)
 *
 * 支持 INNER / LEFT / RIGHT / FULL JOIN
 */
public class NestedLoopJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final SqlNode condition;
    private final JoinType joinType;

    private Row currentLeft;
    private List<Row> rightRows;
    private Iterator<Row> rightIterator;

    // OUTER JOIN 追踪
    private boolean currentLeftMatched;
    private Set<Integer> matchedRightIndices;
    private int rightScanIdx;
    private Row nullRightRow;
    private Row nullLeftRow;

    // RIGHT/FULL 最终阶段：发射右表未匹配行
    private boolean rightEmitPhase;
    private int rightEmitIdx;

    public NestedLoopJoinExec(ExecNode left, ExecNode right, SqlNode condition) {
        this(left, right, condition, JoinType.INNER);
    }

    public NestedLoopJoinExec(ExecNode left, ExecNode right, SqlNode condition, JoinType joinType) {
        this.left = left;
        this.right = right;
        this.condition = condition;
        this.joinType = joinType;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        // 物化右表（内表）
        rightRows = new ArrayList<>();
        Row row;
        while ((row = right.next()) != null) {
            rightRows.add(row);
        }

        currentLeft = left.next();
        rightScanIdx = 0;
        rightIterator = rightRows.iterator();
        currentLeftMatched = false;
        rightEmitPhase = false;
        rightEmitIdx = 0;

        if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
            matchedRightIndices = new HashSet<>();
        }
    }

    @Override
    public Row next() {
        // RIGHT/FULL 最终阶段：发射右表未匹配行
        if (rightEmitPhase) {
            return emitUnmatchedRight();
        }

        while (currentLeft != null) {
            while (rightScanIdx < rightRows.size()) {
                Row rightRow = rightRows.get(rightScanIdx);
                int idx = rightScanIdx;
                rightScanIdx++;

                if (matches(currentLeft, rightRow)) {
                    currentLeftMatched = true;
                    if (matchedRightIndices != null) {
                        matchedRightIndices.add(idx);
                    }
                    return currentLeft.merge(rightRow);
                }
            }

            // 内层循环结束
            Row pendingNull = null;
            if ((joinType == JoinType.LEFT || joinType == JoinType.FULL) && !currentLeftMatched) {
                if (nullRightRow == null && !rightRows.isEmpty()) {
                    nullRightRow = Row.nullRow(rightRows.get(0));
                }
                if (nullRightRow != null) {
                    pendingNull = currentLeft.merge(nullRightRow);
                }
            }

            // 下一行外表，重置内表
            currentLeft = left.next();
            rightScanIdx = 0;
            currentLeftMatched = false;

            if (pendingNull != null) {
                return pendingNull;
            }
        }

        // 左表遍历完毕，RIGHT/FULL 需要发射右表未匹配行
        if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
            rightEmitPhase = true;
            return emitUnmatchedRight();
        }
        return null;
    }

    private Row emitUnmatchedRight() {
        while (rightEmitIdx < rightRows.size()) {
            int idx = rightEmitIdx++;
            if (!matchedRightIndices.contains(idx)) {
                Row rightRow = rightRows.get(idx);
                if (nullLeftRow == null) {
                    nullLeftRow = Row.nullRow(currentLeft != null ? currentLeft : rightRow);
                }
                return nullLeftRow.merge(rightRow);
            }
        }
        return null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        rightRows = null;
        matchedRightIndices = null;
    }

    private boolean matches(Row leftRow, Row rightRow) {
        if (condition == null) return true; // CROSS JOIN
        return evaluateCondition(condition, leftRow, rightRow);
    }

    private boolean evaluateCondition(SqlNode node, Row leftRow, Row rightRow) {
        if (node instanceof SqlBinaryOp binOp) {
            if (binOp.kind() == SqlKind.AND) {
                return evaluateCondition(binOp.left(), leftRow, rightRow)
                    && evaluateCondition(binOp.right(), leftRow, rightRow);
            }
            if (binOp.kind() == SqlKind.OR) {
                return evaluateCondition(binOp.left(), leftRow, rightRow)
                    || evaluateCondition(binOp.right(), leftRow, rightRow);
            }

            Boolean equiJoinMatch = evaluateSafeBareSameNameEquiTerm(binOp, leftRow, rightRow);
            if (equiJoinMatch != null) {
                return equiJoinMatch;
            }
        }

        Row merged = leftRow.merge(rightRow);
        return FilterExec.evaluate(node, merged);
    }

    private Boolean evaluateSafeBareSameNameEquiTerm(SqlBinaryOp binOp, Row leftRow, Row rightRow) {
        if (binOp.kind() != SqlKind.BINARY_EQ) {
            return null;
        }
        if (!(binOp.left() instanceof SqlIdentifier first) || !(binOp.right() instanceof SqlIdentifier second)) {
            return null;
        }

        String firstName = first.name();
        String secondName = second.name();
        if (isQualified(firstName) || isQualified(secondName)) {
            return null;
        }
        if (!firstName.equalsIgnoreCase(secondName)) {
            return null;
        }
        if (!rowHasIdentifier(leftRow, firstName) || !rowHasIdentifier(rightRow, secondName)) {
            return null;
        }
        return FilterExec.compareValues(leftRow.get(firstName), rightRow.get(secondName)) == 0;
    }

    private boolean rowHasIdentifier(Row row, String identifier) {
        String normalized = identifier.toUpperCase();
        for (String key : row.columns().keySet()) {
            if (key.equalsIgnoreCase(normalized)) {
                return true;
            }
            if (!normalized.contains(".")
                && key.contains(".")
                && key.substring(key.indexOf('.') + 1).equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean isQualified(String identifier) {
        return identifier.contains(".");
    }
}
