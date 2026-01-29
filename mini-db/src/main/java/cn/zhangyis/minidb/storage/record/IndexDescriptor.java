package cn.zhangyis.minidb.storage.record;

import java.util.Arrays;
import java.util.Objects;

/**
 * 索引描述符
 * 
 * <p>
 * 定义索引视角下的比较语义，包括索引键字段类型、比较字段数、唯一性等。
 * DataTuple 的比较必须基于 IndexDescriptor 进行。
 * </p>
 * 
 * <h2>核心概念</h2>
 * <ul>
 * <li><b>keyTypes</b>: 索引键字段的类型数组</li>
 * <li><b>nFieldsCmp</b>: 用于比较的字段数（可能小于键数）</li>
 * <li><b>unique</b>: 是否唯一索引</li>
 * <li><b>secondary</b>: 是否二级索引</li>
 * </ul>
 * 
 * <h2>聚簇索引 vs 二级索引</h2>
 * <ul>
 * <li><b>聚簇索引</b>: 包含所有列，按主键排序</li>
 * <li><b>二级索引</b>: 索引列 + 主键列，nFieldsCmp 通常设为索引列数</li>
 * </ul>
 * 
 * <h2>使用示例</h2>
 * 
 * <pre>
 * // 二级索引 INDEX(a, b)，主键为 pk
 * // fields = [a, b, pk], nFieldsCmp = 2（只比较 a, b）
 * IndexDescriptor idx = IndexDescriptor.secondary(
 *         new FieldType[] { typeA, typeB, typePk }, 2);
 * </pre>
 * 
 * @author MiniDB
 * @version 1.0
 * @see DataTuple
 */
public class IndexDescriptor {

    /** 索引键字段类型 */
    private final FieldType[] keyTypes;

    /** 用于比较的字段数 */
    private final int nFieldsCmp;

    /** 是否唯一索引 */
    private final boolean unique;

    /** 是否二级索引 */
    private final boolean secondary;

    // ==================== 静态工厂方法 ====================

    /**
     * 创建聚簇索引描述符
     * 
     * @param keyTypes 所有字段类型
     * @return IndexDescriptor 实例
     */
    public static IndexDescriptor clustered(FieldType[] keyTypes) {
        return new IndexDescriptor(keyTypes, keyTypes.length, true, false);
    }

    /**
     * 创建唯一二级索引描述符
     * 
     * @param keyTypes   索引字段类型（包含主键列）
     * @param nFieldsCmp 用于比较的字段数（不含主键）
     * @return IndexDescriptor 实例
     */
    public static IndexDescriptor uniqueSecondary(FieldType[] keyTypes, int nFieldsCmp) {
        return new IndexDescriptor(keyTypes, nFieldsCmp, true, true);
    }

    /**
     * 创建普通二级索引描述符
     * 
     * @param keyTypes   索引字段类型（包含主键列）
     * @param nFieldsCmp 用于比较的字段数（不含主键）
     * @return IndexDescriptor 实例
     */
    public static IndexDescriptor secondary(FieldType[] keyTypes, int nFieldsCmp) {
        return new IndexDescriptor(keyTypes, nFieldsCmp, false, true);
    }

    // ==================== 构造函数 ====================

    /**
     * 创建索引描述符
     * 
     * @param keyTypes   索引键字段类型
     * @param nFieldsCmp 用于比较的字段数
     * @param unique     是否唯一索引
     * @param secondary  是否二级索引
     */
    public IndexDescriptor(FieldType[] keyTypes, int nFieldsCmp, boolean unique, boolean secondary) {
        Objects.requireNonNull(keyTypes, "keyTypes cannot be null");
        if (keyTypes.length == 0) {
            throw new IllegalArgumentException("keyTypes cannot be empty");
        }
        if (nFieldsCmp < 1 || nFieldsCmp > keyTypes.length) {
            throw new IllegalArgumentException(
                    "nFieldsCmp must be between 1 and " + keyTypes.length + ", got " + nFieldsCmp);
        }

        this.keyTypes = Arrays.copyOf(keyTypes, keyTypes.length);
        this.nFieldsCmp = nFieldsCmp;
        this.unique = unique;
        this.secondary = secondary;
    }

    // ==================== Getters ====================

    /**
     * 获取索引键字段类型
     * 
     * @return 字段类型数组副本
     */
    public FieldType[] getKeyTypes() {
        return Arrays.copyOf(keyTypes, keyTypes.length);
    }

    /**
     * 获取指定位置的字段类型
     * 
     * @param index 字段索引
     * @return 字段类型
     */
    public FieldType getKeyType(int index) {
        return keyTypes[index];
    }

    /**
     * 获取索引键字段数
     * 
     * @return 总字段数
     */
    public int getKeyCount() {
        return keyTypes.length;
    }

    /**
     * 获取用于比较的字段数
     * 
     * @return 比较字段数
     */
    public int getNFieldsCmp() {
        return nFieldsCmp;
    }

    public boolean isUnique() {
        return unique;
    }

    public boolean isSecondary() {
        return secondary;
    }

    /**
     * 是否为聚簇索引
     * 
     * @return 如果不是二级索引则为聚簇索引
     */
    public boolean isClustered() {
        return !secondary;
    }

    @Override
    public String toString() {
        return String.format("IndexDescriptor{keys=%d, cmp=%d, unique=%s, secondary=%s}",
                keyTypes.length, nFieldsCmp, unique, secondary);
    }
}
