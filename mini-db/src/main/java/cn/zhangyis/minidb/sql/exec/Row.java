package cn.zhangyis.minidb.sql.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行数据：列名 → 值
 */
public class Row {
    private final Map<String, Object> columns;

    public Row(Map<String, Object> columns) {
        this.columns = new LinkedHashMap<>(columns);
    }

    public Object get(String column) {
        // 支持 table.column 和 column 两种格式
        if (columns.containsKey(column)) return columns.get(column);
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            String key = entry.getKey();
            if (key.contains(".") && key.substring(key.indexOf('.') + 1).equalsIgnoreCase(column)) {
                return entry.getValue();
            }
            if (key.equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Map<String, Object> columns() { return columns; }

    /**
     * 合并两行（用于 JOIN）
     */
    public Row merge(Row other) {
        Map<String, Object> merged = new LinkedHashMap<>(this.columns);
        merged.putAll(other.columns);
        return new Row(merged);
    }

    @Override
    public String toString() {
        return columns.toString();
    }
}
