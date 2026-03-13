package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlBinaryOp;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Nested Loop Join：双层循环
 * 对每一行外表，扫描整个内表
 * 时间复杂度: O(M * N)
 */
public class NestedLoopJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final SqlNode condition;

    private Row currentLeft;
    private List<Row> rightRows;
    private Iterator<Row> rightIterator;

    public NestedLoopJoinExec(ExecNode left, ExecNode right, SqlNode condition) {
        this.left = left;
        this.right = right;
        this.condition = condition;
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
        rightIterator = rightRows.iterator();
    }

    @Override
    public Row next() {
        while (currentLeft != null) {
            while (rightIterator.hasNext()) {
                Row rightRow = rightIterator.next();
                if (matches(currentLeft, rightRow)) {
                    Row merged = currentLeft.merge(rightRow);
                    return merged;
                }
            }
            // 下一行外表，重置内表迭代器
            currentLeft = left.next();
            rightIterator = rightRows.iterator();
        }
        return null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        rightRows = null;
    }

    private boolean matches(Row leftRow, Row rightRow) {
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
