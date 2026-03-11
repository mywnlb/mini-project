package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * Hash Join：Build/Probe 两阶段
 * 1. Build: 物化右表（build side），按 join key 建 hash 表
 * 2. Probe: 扫描左表（probe side），用 join key 查 hash 表
 * 时间复杂度: O(M + N)，空间: O(N)
 */
public class HashJoinExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final String leftKey;
    private final String rightKey;

    private Map<Object, List<Row>> hashTable;
    private Row currentLeft;
    private Iterator<Row> matchIterator;

    public HashJoinExec(ExecNode left, ExecNode right, String leftKey, String rightKey) {
        this.left = left;
        this.right = right;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        // Build phase: 物化右表，按 rightKey 建 hash 表
        hashTable = new HashMap<>();
        Row row;
        while ((row = right.next()) != null) {
            Object key = row.get(rightKey);
            hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        currentLeft = left.next();
        matchIterator = Collections.emptyIterator();
    }

    @Override
    public Row next() {
        while (true) {
            // 先消费当前匹配
            while (matchIterator.hasNext()) {
                Row rightRow = matchIterator.next();
                return currentLeft.merge(rightRow);
            }

            // Probe: 下一行左表
            currentLeft = left.next();
            if (currentLeft == null) return null;

            Object probeKey = currentLeft.get(leftKey);
            List<Row> matches = hashTable.getOrDefault(probeKey, List.of());
            matchIterator = matches.iterator();
        }
    }

    @Override
    public void close() {
        left.close();
        right.close();
        hashTable = null;
    }
}
