package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlSetOperation.SetOpType;
import java.util.*;

/**
 * UNION / EXCEPT / INTERSECT 执行器
 */
public class UnionExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final boolean all;
    private final SetOpType opType;

    private Iterator<Row> iterator;

    public UnionExec(ExecNode left, ExecNode right, boolean all) {
        this(left, right, all, SetOpType.UNION);
    }

    public UnionExec(ExecNode left, ExecNode right, boolean all, SetOpType opType) {
        this.left = left;
        this.right = right;
        this.all = all;
        this.opType = opType;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        List<Row> leftRows = collectAll(left);
        List<Row> rightRows = collectAll(right);

        List<Row> result = switch (opType) {
            case UNION -> computeUnion(leftRows, rightRows);
            case EXCEPT -> computeExcept(leftRows, rightRows);
            case INTERSECT -> computeIntersect(leftRows, rightRows);
        };

        iterator = result.iterator();
    }

    private List<Row> computeUnion(List<Row> leftRows, List<Row> rightRows) {
        List<Row> result = new ArrayList<>(leftRows);
        result.addAll(rightRows);
        if (!all) {
            Set<String> seen = new LinkedHashSet<>();
            List<Row> deduped = new ArrayList<>();
            for (Row r : result) {
                if (seen.add(rowKey(r))) deduped.add(r);
            }
            return deduped;
        }
        return result;
    }

    private List<Row> computeExcept(List<Row> leftRows, List<Row> rightRows) {
        if (all) {
            // EXCEPT ALL: 对于右侧每个重复值，从左侧移除一个
            Map<String, Integer> rightCounts = new LinkedHashMap<>();
            for (Row r : rightRows) {
                rightCounts.merge(rowKey(r), 1, Integer::sum);
            }
            List<Row> result = new ArrayList<>();
            for (Row r : leftRows) {
                String key = rowKey(r);
                Integer count = rightCounts.get(key);
                if (count != null && count > 0) {
                    rightCounts.put(key, count - 1);
                } else {
                    result.add(r);
                }
            }
            return result;
        } else {
            // EXCEPT: 左侧去重后减去右侧
            Set<String> rightKeys = new LinkedHashSet<>();
            for (Row r : rightRows) rightKeys.add(rowKey(r));
            Set<String> seen = new LinkedHashSet<>();
            List<Row> result = new ArrayList<>();
            for (Row r : leftRows) {
                String key = rowKey(r);
                if (!rightKeys.contains(key) && seen.add(key)) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    private List<Row> computeIntersect(List<Row> leftRows, List<Row> rightRows) {
        if (all) {
            // INTERSECT ALL: 取两侧每个值出现次数的最小值
            Map<String, Integer> rightCounts = new LinkedHashMap<>();
            for (Row r : rightRows) {
                rightCounts.merge(rowKey(r), 1, Integer::sum);
            }
            List<Row> result = new ArrayList<>();
            for (Row r : leftRows) {
                String key = rowKey(r);
                Integer count = rightCounts.get(key);
                if (count != null && count > 0) {
                    result.add(r);
                    rightCounts.put(key, count - 1);
                }
            }
            return result;
        } else {
            // INTERSECT: 两侧都有的行（去重）
            Set<String> rightKeys = new LinkedHashSet<>();
            for (Row r : rightRows) rightKeys.add(rowKey(r));
            Set<String> seen = new LinkedHashSet<>();
            List<Row> result = new ArrayList<>();
            for (Row r : leftRows) {
                String key = rowKey(r);
                if (rightKeys.contains(key) && seen.add(key)) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    private List<Row> collectAll(ExecNode exec) {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) rows.add(row);
        return rows;
    }

    /** 基于行所有列值生成可比较的 key */
    private String rowKey(Row row) {
        StringBuilder sb = new StringBuilder();
        for (Object val : row.columns().values()) {
            sb.append(val == null ? "\0NULL\0" : val.toString()).append('\1');
        }
        return sb.toString();
    }

    @Override
    public Row next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        iterator = null;
    }
}
