package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * Hash Join：Build/Probe 两阶段
 * 1. Build: 物化右表（build side），按 join key 建 hash 表
 * 2. Probe: 扫描左表（probe side），用 join key 查 hash 表
 * 时间复杂度: O(M + N)，空间: O(N)
 *
 * 支持 INNER / LEFT / RIGHT / FULL JOIN
 */
public class HashJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final List<String> leftKeys;
    private final List<String> rightKeys;
    private final JoinType joinType;

    private Map<List<Object>, List<Row>> hashTable;
    private Row currentLeft;
    private Iterator<Row> matchIterator;

    // OUTER JOIN 追踪
    private boolean currentLeftMatched;
    private Row nullBuildRow;
    private Set<List<Object>> matchedBuildKeys;
    private List<Row> allBuildRows;

    // RIGHT/FULL 最终阶段
    private boolean rightEmitPhase;
    private int rightEmitIdx;

    /** 单 key 便捷构造器（向后兼容） */
    public HashJoinExec(ExecNode left, ExecNode right, String leftKey, String rightKey) {
        this(left, right, List.of(leftKey), List.of(rightKey), JoinType.INNER);
    }

    /** 多 key 构造器（向后兼容） */
    public HashJoinExec(ExecNode left, ExecNode right, List<String> leftKeys, List<String> rightKeys) {
        this(left, right, leftKeys, rightKeys, JoinType.INNER);
    }

    /** 带 JoinType 构造器 */
    public HashJoinExec(ExecNode left, ExecNode right, List<String> leftKeys, List<String> rightKeys, JoinType joinType) {
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

        // Build phase: 物化右表，按组合 rightKeys 建 hash 表
        hashTable = new HashMap<>();
        boolean needTrackBuild = joinType == JoinType.RIGHT || joinType == JoinType.FULL;
        if (needTrackBuild) {
            matchedBuildKeys = new HashSet<>();
            allBuildRows = new ArrayList<>();
        }

        Row row;
        while ((row = right.next()) != null) {
            if (needTrackBuild) allBuildRows.add(row);
            List<Object> key = computeKey(row, rightKeys);
            if (key != null) { // NULL key 不入表（SQL: NULL = NULL → UNKNOWN）
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
            if (nullBuildRow == null) {
                nullBuildRow = Row.nullRow(row);
            }
        }

        currentLeft = null;
        matchIterator = Collections.emptyIterator();
        currentLeftMatched = false;
        rightEmitPhase = false;
        rightEmitIdx = 0;
    }

    @Override
    public Row next() {
        // RIGHT/FULL 最终阶段
        if (rightEmitPhase) {
            return emitUnmatchedBuild();
        }

        while (true) {
            // 先消费当前匹配
            while (matchIterator.hasNext()) {
                Row rightRow = matchIterator.next();
                currentLeftMatched = true;
                return currentLeft.merge(rightRow);
            }

            // LEFT/FULL: 左行无匹配时输出 NULL 填充行
            if (currentLeft != null && !currentLeftMatched
                && (joinType == JoinType.LEFT || joinType == JoinType.FULL)) {
                Row pending = currentLeft.merge(nullBuildRow != null ? nullBuildRow : new Row(Map.of()));
                currentLeft = null; // 标记已处理
                // 继续 probe 下一行前先输出
                currentLeft = left.next();
                currentLeftMatched = false;
                if (currentLeft != null) {
                    List<Object> probeKey = computeKey(currentLeft, leftKeys);
                    if (probeKey != null) {
                        List<Row> matches = hashTable.getOrDefault(probeKey, List.of());
                        matchIterator = matches.iterator();
                        if (matchedBuildKeys != null && !matches.isEmpty()) {
                            matchedBuildKeys.add(probeKey);
                        }
                    } else {
                        matchIterator = Collections.emptyIterator();
                    }
                }
                return pending;
            }

            // Probe: 下一行左表
            currentLeft = left.next();
            currentLeftMatched = false;
            if (currentLeft == null) {
                // Probe 结束
                if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
                    rightEmitPhase = true;
                    return emitUnmatchedBuild();
                }
                return null;
            }

            List<Object> probeKey = computeKey(currentLeft, leftKeys);
            if (probeKey == null) {
                // 任一 key 分量为 null，跳过此行（不匹配任何右表行）
                matchIterator = Collections.emptyIterator();
                continue;
            }
            List<Row> matches = hashTable.getOrDefault(probeKey, List.of());
            matchIterator = matches.iterator();
            if (matchedBuildKeys != null && !matches.isEmpty()) {
                matchedBuildKeys.add(probeKey);
            }
        }
    }

    private Row emitUnmatchedBuild() {
        if (allBuildRows == null) return null;
        Row nullProbeRow = null;
        while (rightEmitIdx < allBuildRows.size()) {
            Row buildRow = allBuildRows.get(rightEmitIdx++);
            List<Object> key = computeKey(buildRow, rightKeys);
            if (key != null && matchedBuildKeys.contains(key)) continue;
            // 未匹配的右表行
            if (nullProbeRow == null && currentLeft != null) {
                nullProbeRow = Row.nullRow(currentLeft);
            }
            if (nullProbeRow == null) {
                // 左表为空，构造空左行
                Map<String, Object> empty = new LinkedHashMap<>();
                return new Row(empty).merge(buildRow);
            }
            return nullProbeRow.merge(buildRow);
        }
        return null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        hashTable = null;
        allBuildRows = null;
        matchedBuildKeys = null;
    }

    /**
     * 计算组合 hash key。任一分量为 null 则返回 null（SQL 三值逻辑）。
     */
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
