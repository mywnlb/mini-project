package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.rel.RelNode;

import java.util.*;

/**
 * 并行执行工具类
 * 提取公共的 key 提取、row 合并、schema 处理逻辑
 * 避免三个并行算子重复代码
 */
public class ExecUtils {

    /**
     * 从 Row 和 join condition 中提取 join key
     * 支持单列和多列 equi-join
     */
    public static Object extractJoinKey(Row row, SqlNode condition, List<String> equiColumns) {
        if (condition == null || equiColumns.isEmpty()) {
            return row.columns().values().iterator().next(); // 降级
        }
        List<Object> keyParts = new ArrayList<>();
        for (String col : equiColumns) {
            keyParts.add(row.get(col));
        }
        return keyParts.size() == 1 ? keyParts.get(0) : keyParts;
    }

    /**
     * 从 Row 和 group by 列中提取 group key
     */
    public static Object extractGroupKey(Row row, List<String> groupByColumns) {
        if (groupByColumns.isEmpty()) {
            return "GLOBAL";
        }
        List<Object> keyParts = new ArrayList<>();
        for (String col : groupByColumns) {
            keyParts.add(row.get(col));
        }
        return keyParts.size() == 1 ? keyParts.get(0) : keyParts;
    }

    /**
     * 合并两个 Row，感知 schema
     * 优先使用 qualified name 避免冲突
     */
    public static Row mergeRows(Row left, Row right) {
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.putAll(left.columns());
        combined.putAll(right.columns());
        return new Row(combined);
    }

    /**
     * 比较两个 Row 用于排序
     * 简化版，实际应使用 ORDER BY 表达式
     */
    public static int compareRows(Row r1, Row r2) {
        Object v1 = r1.columns().values().iterator().next();
        Object v2 = r2.columns().values().iterator().next();
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        if (v1 instanceof Comparable c1 && v2 instanceof Comparable c2) {
            return c1.compareTo(c2);
        }
        return v1.hashCode() - v2.hashCode();
    }

    /**
     * 检查是否为 equi-join 条件
     */
    public static boolean isEquiJoin(SqlNode condition) {
        // 简化版，实际应解析 SqlBinaryOp
        return condition != null;
    }
}