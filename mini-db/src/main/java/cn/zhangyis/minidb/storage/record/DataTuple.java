package cn.zhangyis.minidb.storage.record;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * 逻辑记录（Data Tuple）
 * 
 * <p>
 * 对应 InnoDB 的 dtuple_t，表示一组有类型、有顺序、有比较语义的字段集合。
 * 这是逻辑记录层的核心类，所有 B+Tree 操作的比较都必须基于此类。
 * </p>
 * 
 * <h2>核心原则</h2>
 * <ul>
 * <li>逻辑记录与物理记录严格分层</li>
 * <li>B+Tree search/insert 必须基于 DataTuple.compare</li>
 * <li>禁止在 B+Tree 中直接解析 byte[] 做比较</li>
 * </ul>
 * 
 * <h2>字段含义</h2>
 * <ul>
 * <li><b>fields</b>: 字段数组</li>
 * <li><b>nFields</b>: 字段总数</li>
 * <li><b>nFieldsCmp</b>: 用于比较的字段数</li>
 * <li><b>infoBits</b>: 逻辑层标志位（如 delete_mark）</li>
 * </ul>
 * 
 * <h2>比较规则</h2>
 * <ol>
 * <li>仅比较前 nFieldsCmp 个字段</li>
 * <li>按字段顺序逐一比较</li>
 * <li>使用字段类型对应的 comparator</li>
 * <li>NULL < NOT NULL</li>
 * <li>任一字段不等即返回</li>
 * </ol>
 * 
 * <h2>InnoDB 源码参考</h2>
 * <ul>
 * <li>data0data.h - dtuple_t 定义</li>
 * <li>cmp0cmp.cc - cmp_dtuple_rec 比较函数</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 * @see DataField
 * @see IndexDescriptor
 */
public class DataTuple {

    // ==================== Info Bits 常量 ====================

    /** 删除标记位 */
    public static final int INFO_BITS_DELETE_MARK = 0x20;

    /** 最小记录标记（Infimum） */
    public static final int INFO_BITS_MIN_REC = 0x10;

    // ==================== 实例字段 ====================

    /** 字段数组 */
    private final DataField[] fields;

    /** 字段总数 */
    private final int nFields;

    /** 用于比较的字段数（可修改） */
    private int nFieldsCmp;

    /** 逻辑层标志位 */
    private int infoBits;

    // ==================== 静态工厂方法 ====================

    /**
     * 创建 DataTuple
     * 
     * @param fields 字段数组
     * @return DataTuple 实例
     */
    public static DataTuple of(DataField... fields) {
        return new DataTuple(fields, fields.length, 0);
    }

    /**
     * 创建带有比较字段数的 DataTuple
     * 
     * @param nFieldsCmp 用于比较的字段数
     * @param fields     字段数组
     * @return DataTuple 实例
     */
    public static DataTuple withCmpFields(int nFieldsCmp, DataField... fields) {
        return new DataTuple(fields, nFieldsCmp, 0);
    }

    // ==================== 构造函数 ====================

    /**
     * 创建逻辑记录
     * 
     * @param fields     字段数组
     * @param nFieldsCmp 用于比较的字段数
     * @param infoBits   标志位
     */
    public DataTuple(DataField[] fields, int nFieldsCmp, int infoBits) {
        Objects.requireNonNull(fields, "fields cannot be null");
        if (fields.length == 0) {
            throw new IllegalArgumentException("fields cannot be empty");
        }
        if (nFieldsCmp < 0 || nFieldsCmp > fields.length) {
            throw new IllegalArgumentException(
                    "nFieldsCmp must be between 0 and " + fields.length + ", got " + nFieldsCmp);
        }

        this.fields = Arrays.copyOf(fields, fields.length);
        this.nFields = fields.length;
        this.nFieldsCmp = nFieldsCmp;
        this.infoBits = infoBits;
    }

    // ==================== 核心比较方法 ====================

    /**
     * 比较两个逻辑记录
     * 
     * <p>
     * 比较规则：
     * </p>
     * <ol>
     * <li>仅比较前 nFieldsCmp 个字段</li>
     * <li>按字段顺序逐一比较</li>
     * <li>使用字段类型对应的 comparator</li>
     * <li>NULL < NOT NULL</li>
     * <li>任一字段不等即返回</li>
     * <li>全部相等返回 0</li>
     * </ol>
     * 
     * @param other 另一个逻辑记录
     * @param index 索引描述符（用于获取比较字段数）
     * @return 负数表示 this < other，0 表示相等，正数表示 this > other
     */
    public int compare(DataTuple other, IndexDescriptor index) {
        Objects.requireNonNull(other, "other cannot be null");
        Objects.requireNonNull(index, "index cannot be null");

        int cmpCount = Math.min(this.nFieldsCmp, other.nFieldsCmp);
        if (cmpCount == 0) {
            cmpCount = index.getNFieldsCmp();
        }

        return compareFields(other, cmpCount);
    }

    /**
     * 比较指定数量的字段
     * 
     * @param other    另一个逻辑记录
     * @param cmpCount 比较的字段数
     * @return 比较结果
     */
    public int compareFields(DataTuple other, int cmpCount) {
        int actualCmp = Math.min(cmpCount, Math.min(this.nFields, other.nFields));

        for (int i = 0; i < actualCmp; i++) {
            DataField thisField = this.fields[i];
            DataField otherField = other.fields[i];

            // 1. NULL 处理
            if (thisField.isNull() && otherField.isNull()) {
                continue; // 都是 NULL，视为相等
            }
            if (thisField.isNull()) {
                return -1; // NULL < NOT NULL
            }
            if (otherField.isNull()) {
                return 1; // NOT NULL > NULL
            }

            // 2. 使用字段类型对应的 comparator 比较
            Comparator<byte[]> cmp = thisField.getType().comparator();
            int result = cmp.compare(
                    thisField.getDataInternal(),
                    otherField.getDataInternal());

            // 3. 任一字段不等即返回
            if (result != 0) {
                return result;
            }
        }

        // 4. 全部相等返回 0
        return 0;
    }

    // ==================== Getters/Setters ====================

    /**
     * 获取指定位置的字段
     * 
     * @param index 字段索引
     * @return DataField
     */
    public DataField getField(int index) {
        if (index < 0 || index >= nFields) {
            throw new IndexOutOfBoundsException("Field index: " + index + ", nFields: " + nFields);
        }
        return fields[index];
    }

    /**
     * 获取所有字段
     * 
     * @return 字段数组副本
     */
    public DataField[] getFields() {
        return Arrays.copyOf(fields, nFields);
    }

    public int getNFields() {
        return nFields;
    }

    public int getNFieldsCmp() {
        return nFieldsCmp;
    }

    /**
     * 设置用于比较的字段数
     * 
     * @param nFieldsCmp 比较字段数
     */
    public void setNFieldsCmp(int nFieldsCmp) {
        if (nFieldsCmp < 0 || nFieldsCmp > nFields) {
            throw new IllegalArgumentException(
                    "nFieldsCmp must be between 0 and " + nFields + ", got " + nFieldsCmp);
        }
        this.nFieldsCmp = nFieldsCmp;
    }

    public int getInfoBits() {
        return infoBits;
    }

    public void setInfoBits(int infoBits) {
        this.infoBits = infoBits;
    }

    /**
     * 检查是否标记为删除
     * 
     * @return 如果设置了删除标记返回 true
     */
    public boolean isDeleteMarked() {
        return (infoBits & INFO_BITS_DELETE_MARK) != 0;
    }

    /**
     * 设置删除标记
     * 
     * @param marked true 表示标记删除，false 表示取消标记
     */
    public void setDeleteMark(boolean marked) {
        if (marked) {
            infoBits |= INFO_BITS_DELETE_MARK;
        } else {
            infoBits &= ~INFO_BITS_DELETE_MARK;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        DataTuple dataTuple = (DataTuple) o;
        return nFields == dataTuple.nFields &&
                Arrays.equals(fields, dataTuple.fields);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(nFields);
        result = 31 * result + Arrays.hashCode(fields);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DataTuple{");
        sb.append("nFields=").append(nFields);
        sb.append(", nFieldsCmp=").append(nFieldsCmp);
        sb.append(", infoBits=0x").append(Integer.toHexString(infoBits));
        sb.append(", fields=[");
        for (int i = 0; i < Math.min(nFields, 3); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(fields[i]);
        }
        if (nFields > 3) {
            sb.append(", ...");
        }
        sb.append("]}");
        return sb.toString();
    }
}
