package cn.zhangyis.minidb.sql.exec;

import java.util.*;

/**
 * Anti Hash Join：只返回在右侧无匹配的左侧行。
 * Build: 物化右表按 join key 建 HashSet
 * Probe: 扫描左表，key 未命中时输出左行
 * 时间复杂度: O(M + N)
 *
 * NOT IN NULL 语义说明：
 * 标准 SQL 中 x NOT IN (1, 2, NULL) 永远不返回 TRUE。
 * 当前采用简化处理：右侧 NULL key 不入 HashSet，左侧 NULL key 直接跳过。
 * 在右侧结果集不含 NULL 时语义正确。
 */
public class AntiHashJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final List<String> leftKeys;
    private final List<String> rightKeys;

    private Set<List<Object>> rightKeySet;

    public AntiHashJoinExec(ExecNode left, ExecNode right,
                            String leftKey, String rightKey) {
        this(left, right, List.of(leftKey), List.of(rightKey));
    }

    public AntiHashJoinExec(ExecNode left, ExecNode right,
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
            // NULL key: SQL 三值逻辑下 NULL NOT IN (...) → UNKNOWN → 不输出
            if (probeKey == null) continue;
            if (!rightKeySet.contains(probeKey)) {
                return leftRow;  // 未命中 → 输出左行
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
            if (val == null) return null;
            composite.add(val);
        }
        return composite;
    }
}
