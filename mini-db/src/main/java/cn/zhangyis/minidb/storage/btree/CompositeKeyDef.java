package cn.zhangyis.minidb.storage.btree;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 复合键定义
 *
 * <p>定义复合键的结构，包含多个列。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompositeKeyDef {

    /** 列定义列表 */
    private final List<KeyColumn> columns;

    /** 总列数 */
    private final int columnCount;

    /**
     * 构造复合键定义
     *
     * @param columns 列定义列表
     */
    public CompositeKeyDef(List<KeyColumn> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Composite key must have at least one column");
        }
        this.columns = List.copyOf(columns);
        this.columnCount = columns.size();
    }

    /**
     * 构造复合键定义（可变参数）
     *
     * @param columns 列定义
     */
    public CompositeKeyDef(KeyColumn... columns) {
        this(Arrays.asList(columns));
    }

    /**
     * 创建单列整数键
     */
    public static CompositeKeyDef singleInt(String name) {
        return new CompositeKeyDef(KeyColumn.intColumn(name));
    }

    /**
     * 创建双列整数键
     */
    public static CompositeKeyDef doubleInt(String name1, String name2) {
        return new CompositeKeyDef(
                KeyColumn.intColumn(name1),
                KeyColumn.intColumn(name2)
        );
    }

    /**
     * 创建三列整数键
     */
    public static CompositeKeyDef tripleInt(String name1, String name2, String name3) {
        return new CompositeKeyDef(
                KeyColumn.intColumn(name1),
                KeyColumn.intColumn(name2),
                KeyColumn.intColumn(name3)
        );
    }

    // ==================== Getters ====================

    public List<KeyColumn> getColumns() {
        return columns;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public KeyColumn getColumn(int index) {
        return columns.get(index);
    }

    /**
     * 获取编码后的最大字节数
     */
    public int getMaxEncodedLength() {
        int total = 0;
        for (KeyColumn column : columns) {
            total += column.getMaxEncodedLength();
        }
        return total;
    }

    /**
     * 检查是否包含变长列
     */
    public boolean hasVariableLengthColumn() {
        for (KeyColumn column : columns) {
            if (column.getType().isVariableSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否包含可空列
     */
    public boolean hasNullableColumn() {
        for (KeyColumn column : columns) {
            if (column.isNullable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CompositeKey(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(columns.get(i));
        }
        sb.append(")");
        return sb.toString();
    }
}
