package cn.zhangyis.minidb.sql.exec;

import java.util.*;

/**
 * Semi Hash Join：只返回左侧行，右侧仅用于存在性判断。
 * Build: 物化右表按 join key 建 HashSet（不需要值，只需要 key）
 * Probe: 扫描左表，key 命中即输出左行
 * 时间复杂度: O(M + N)，空间: O(N) — N 为右侧去重后的 key 集合大小
 */
public class SemiHashJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final List<String> leftKeys;
    private final List<String> rightKeys;

    private Set<List<Object>> rightKeySet;

    public SemiHashJoinExec(ExecNode left, ExecNode right,
                            String leftKey, String rightKey) {
        this(left, right, List.of(leftKey), List.of(rightKey));
    }

    public SemiHashJoinExec(ExecNode left, ExecNode right,
                            List<String> leftKeys, List<String> rightKeys) {
        this.left = left;
        this.right = right;
        this.leftKeys = leftKeys;
        this.rightKeys = rightKeys;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        // Build: 物化右表 key 集合（去重，只存 key 不存行）
        rightKeySet = new HashSet<>();
        Row row;
        while ((row = right.next()) != null) {
            List<Object> key = computeKey(row, rightKeys);
            if (key != null) rightKeySet.add(key);
        }
    }

    @Override
    public Row next() {
        Row leftRow;
        while ((leftRow = left.next()) != null) {
            List<Object> probeKey = computeKey(leftRow, leftKeys);
            if (probeKey != null && rightKeySet.contains(probeKey)) {
                return leftRow;  // 命中 → 输出左行（不 merge 右行）
            }
        }
        return null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        rightKeySet = null;
    }

    private static List<Object> computeKey(Row row, List<String> keys) {
        List<Object> composite = new ArrayList<>(keys.size());
        for (String key : keys) {
            Object val = row.get(key);
            if (val == null) return null;  // NULL key → SQL 三值逻辑
            composite.add(val);
        }
        return composite;
    }
}
