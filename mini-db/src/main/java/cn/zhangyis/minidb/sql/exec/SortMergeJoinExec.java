package cn.zhangyis.minidb.sql.exec;

import java.util.*;

/**
 * Sort-Merge Join：归并扫描
 * 1. 两侧按 join key 排序
 * 2. 双指针归并匹配
 * 时间复杂度: O(M*logM + N*logN + M + N)
 * 适用: 两侧已排序或数据量大
 */
public class SortMergeJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final String leftKey;
    private final String rightKey;

    private List<Row> sortedLeft;
    private List<Row> sortedRight;
    private int leftIdx;
    private int rightIdx;
    private int rightMatchStart; // 右侧相同 key 的起始位置
    private boolean needAdvanceLeft;

    public SortMergeJoinExec(ExecNode left, ExecNode right, String leftKey, String rightKey) {
        this.left = left;
        this.right = right;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        // 物化并排序
        sortedLeft = materializeAndSort(left, leftKey);
        sortedRight = materializeAndSort(right, rightKey);

        leftIdx = 0;
        rightIdx = 0;
        rightMatchStart = 0;
        needAdvanceLeft = false;
    }

    @Override
    public Row next() {
        while (leftIdx < sortedLeft.size() && rightIdx < sortedRight.size()) {
            Row leftRow = sortedLeft.get(leftIdx);
            Row rightRow = sortedRight.get(rightIdx);

            int cmp = compareKeys(leftRow.get(leftKey), rightRow.get(rightKey));

            if (cmp < 0) {
                leftIdx++;
                rightIdx = rightMatchStart; // 回退右侧
            } else if (cmp > 0) {
                rightIdx++;
                rightMatchStart = rightIdx;
            } else {
                // 匹配
                Row merged = leftRow.merge(rightRow);
                rightIdx++;

                // 如果右侧下一行 key 不同，左侧前进，右侧回退
                if (rightIdx >= sortedRight.size() ||
                    compareKeys(leftRow.get(leftKey), sortedRight.get(rightIdx).get(rightKey)) != 0) {
                    leftIdx++;
                    rightIdx = rightMatchStart;
                }

                return merged;
            }
        }
        return null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        sortedLeft = null;
        sortedRight = null;
    }

    private List<Row> materializeAndSort(ExecNode exec, String key) {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        rows.sort((a, b) -> compareKeys(a.get(key), b.get(key)));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private int compareKeys(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Comparable ca && b instanceof Comparable cb) {
            return ca.compareTo(cb);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
