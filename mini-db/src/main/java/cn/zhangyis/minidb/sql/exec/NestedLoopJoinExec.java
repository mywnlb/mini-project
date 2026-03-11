package cn.zhangyis.minidb.sql.exec;

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
                Row merged = currentLeft.merge(rightRow);
                if (FilterExec.evaluate(condition, merged)) {
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
}
