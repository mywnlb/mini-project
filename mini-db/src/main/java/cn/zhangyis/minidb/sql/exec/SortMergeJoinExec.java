package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.JoinType;

import java.util.*;

/**
 * Sort-Merge Join：归并扫描
 * 1. 两侧按 join key 排序
 * 2. 双指针归并匹配
 * 时间复杂度: O(M*logM + N*logN + M + N)
 *
 * 支持 INNER / LEFT / RIGHT / FULL JOIN
 */
public class SortMergeJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final List<String> leftKeys;
    private final List<String> rightKeys;
    private final JoinType joinType;

    private List<Row> sortedLeft;
    private List<Row> sortedRight;
    private int leftIdx;
    private int rightIdx;
    private int rightMatchStart; // 右侧相同 key 的起始位置

    // OUTER JOIN
    private Row nullLeftRow;
    private Row nullRightRow;
    private Row pendingEmit; // 缓存待发射的 NULL 填充行
    private boolean leftDrained;  // 左表已遍历完，进入右表剩余行发射
    private boolean rightDrained; // 右表已遍历完，进入左表剩余行发射

    /** 单 key 便捷构造器（向后兼容） */
    public SortMergeJoinExec(ExecNode left, ExecNode right, String leftKey, String rightKey) {
        this(left, right, List.of(leftKey), List.of(rightKey), JoinType.INNER);
    }

    /** 多 key 构造器（向后兼容） */
    public SortMergeJoinExec(ExecNode left, ExecNode right, List<String> leftKeys, List<String> rightKeys) {
        this(left, right, leftKeys, rightKeys, JoinType.INNER);
    }

    /** 带 JoinType 构造器 */
    public SortMergeJoinExec(ExecNode left, ExecNode right, List<String> leftKeys, List<String> rightKeys, JoinType joinType) {
        this.left = left;
        this.right = right;
        this.leftKeys = leftKeys;
        this.rightKeys = rightKeys;
        this.joinType = joinType;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        // 物化并按组合 key 排序
        sortedLeft = materializeAndSort(left, leftKeys);
        sortedRight = materializeAndSort(right, rightKeys);

        leftIdx = 0;
        rightIdx = 0;
        rightMatchStart = 0;
        pendingEmit = null;
        leftDrained = false;
        rightDrained = false;

        // 预生成 null 行模板
        if (!sortedLeft.isEmpty()) nullLeftRow = Row.nullRow(sortedLeft.get(0));
        if (!sortedRight.isEmpty()) nullRightRow = Row.nullRow(sortedRight.get(0));
    }

    @Override
    public Row next() {
        // 先返回缓存的 pending 行
        if (pendingEmit != null) {
            Row r = pendingEmit;
            pendingEmit = null;
            return r;
        }

        // 主归并循环
        while (leftIdx < sortedLeft.size() && rightIdx < sortedRight.size()) {
            Row leftRow = sortedLeft.get(leftIdx);
            Row rightRow = sortedRight.get(rightIdx);

            int cmp = compareCompositeKeys(leftRow, leftKeys, rightRow, rightKeys);

            if (cmp < 0) {
                // 左行无匹配
                leftIdx++;
                rightIdx = rightMatchStart;
                if (joinType == JoinType.LEFT || joinType == JoinType.FULL) {
                    return leftRow.merge(nullRightRow);
                }
            } else if (cmp > 0) {
                // 右行无匹配
                rightIdx++;
                rightMatchStart = rightIdx;
                if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
                    return nullLeftRow.merge(rightRow);
                }
            } else {
                // 匹配
                Row merged = leftRow.merge(rightRow);
                rightIdx++;

                // 如果右侧下一行 key 不同，当前左行的匹配组结束
                if (rightIdx >= sortedRight.size() ||
                    compareCompositeKeys(leftRow, leftKeys, sortedRight.get(rightIdx), rightKeys) != 0) {
                    int rightGroupEnd = rightIdx;
                    leftIdx++;
                    // 下一左行 key 相同 → 右侧回退重新匹配同组
                    if (leftIdx < sortedLeft.size() &&
                        compareCompositeKeys(sortedLeft.get(leftIdx), leftKeys, leftRow, leftKeys) == 0) {
                        rightIdx = rightMatchStart;
                    } else {
                        // 左 key 变了 → rightMatchStart 前进到组尾，避免重复发射
                        rightMatchStart = rightGroupEnd;
                        rightIdx = rightGroupEnd;
                    }
                }

                return merged;
            }
        }

        // 左表剩余行（LEFT/FULL）
        if ((joinType == JoinType.LEFT || joinType == JoinType.FULL)
            && leftIdx < sortedLeft.size()) {
            Row leftRow = sortedLeft.get(leftIdx++);
            return leftRow.merge(nullRightRow);
        }

        // 右表剩余行（RIGHT/FULL）
        if ((joinType == JoinType.RIGHT || joinType == JoinType.FULL)
            && rightIdx < sortedRight.size()) {
            Row rightRow = sortedRight.get(rightIdx++);
            return nullLeftRow.merge(rightRow);
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

    private List<Row> materializeAndSort(ExecNode exec, List<String> keys) {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        rows.sort((a, b) -> compareCompositeKeys(a, keys, b, keys));
        return rows;
    }

    /**
     * 逐 key 比较组合键，第一个非零结果决定顺序。
     */
    private int compareCompositeKeys(Row rowA, List<String> keysA, Row rowB, List<String> keysB) {
        for (int i = 0; i < keysA.size(); i++) {
            int cmp = compareKeys(rowA.get(keysA.get(i)), rowB.get(keysB.get(i)));
            if (cmp != 0) return cmp;
        }
        return 0;
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
