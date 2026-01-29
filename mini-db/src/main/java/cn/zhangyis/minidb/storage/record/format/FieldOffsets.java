package cn.zhangyis.minidb.storage.record.format;

import java.util.Arrays;

/**
 * 字段偏移表
 *
 * <p>解析变长字段长度列表和 NULL bitmap 后的结果，
 * 用于快速定位各字段在记录中的位置。</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I8: offset 基准 = 相对 dataStart，不含系统列</li>
 *   <li>用户列从 layout.fixedSysBytes() 开始</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class FieldOffsets {

    /**
     * 各字段的偏移（相对用户列起始位置）
     * <p>注意：不是相对 dataStart，而是相对 userColumnsStart</p>
     */
    private final int[] offsets;

    /**
     * 各字段的长度
     */
    private final int[] lengths;

    /**
     * NULL 标记
     */
    private final boolean[] nulls;

    /**
     * 用户列起始偏移（相对 dataStart）
     * <p>即 systemLayout.fixedSysBytes()</p>
     */
    private final int userColumnsOffset;

    /**
     * 变长字段长度列表占用的字节数
     */
    private final int varlenListBytes;

    /**
     * NULL bitmap 占用的字节数
     */
    private final int nullBitmapBytes;

    /**
     * 构造函数
     */
    public FieldOffsets(int columnCount, int userColumnsOffset) {
        this.offsets = new int[columnCount];
        this.lengths = new int[columnCount];
        this.nulls = new boolean[columnCount];
        this.userColumnsOffset = userColumnsOffset;
        this.varlenListBytes = 0;
        this.nullBitmapBytes = 0;
    }

    /**
     * 完整构造函数
     */
    public FieldOffsets(int[] offsets, int[] lengths, boolean[] nulls,
                        int userColumnsOffset, int varlenListBytes, int nullBitmapBytes) {
        this.offsets = Arrays.copyOf(offsets, offsets.length);
        this.lengths = Arrays.copyOf(lengths, lengths.length);
        this.nulls = Arrays.copyOf(nulls, nulls.length);
        this.userColumnsOffset = userColumnsOffset;
        this.varlenListBytes = varlenListBytes;
        this.nullBitmapBytes = nullBitmapBytes;
    }

    // ==================== 字段访问 ====================

    /**
     * 获取字段数量
     */
    public int getFieldCount() {
        return offsets.length;
    }

    /**
     * 获取字段偏移（相对用户列起始）
     *
     * @param index 字段索引
     * @return 偏移量
     */
    public int getOffset(int index) {
        return offsets[index];
    }

    /**
     * 获取字段长度
     *
     * @param index 字段索引
     * @return 长度
     */
    public int getLength(int index) {
        return lengths[index];
    }

    /**
     * 字段是否为 NULL
     *
     * @param index 字段索引
     * @return 是否为 NULL
     */
    public boolean isNull(int index) {
        return nulls[index];
    }

    /**
     * 获取字段的绝对偏移（相对 dataStart）
     *
     * @param index 字段索引
     * @return 相对 dataStart 的偏移
     */
    public int getAbsoluteOffset(int index) {
        return userColumnsOffset + offsets[index];
    }

    /**
     * 获取用户列起始偏移
     */
    public int getUserColumnsOffset() {
        return userColumnsOffset;
    }

    /**
     * 获取变长字段长度列表字节数
     */
    public int getVarlenListBytes() {
        return varlenListBytes;
    }

    /**
     * 获取 NULL bitmap 字节数
     */
    public int getNullBitmapBytes() {
        return nullBitmapBytes;
    }

    /**
     * 计算记录头之前的额外空间（varlen list + null bitmap）
     */
    public int getExtraBytesBeforeHeader() {
        return varlenListBytes + nullBitmapBytes;
    }

    /**
     * 计算用户数据总长度
     */
    public int getUserDataLength() {
        int total = 0;
        for (int i = 0; i < lengths.length; i++) {
            if (!nulls[i]) {
                total += lengths[i];
            }
        }
        return total;
    }

    // ==================== 构建器 ====================

    /**
     * 创建构建器
     */
    public static Builder builder(int columnCount, int userColumnsOffset) {
        return new Builder(columnCount, userColumnsOffset);
    }

    public static class Builder {
        private final int[] offsets;
        private final int[] lengths;
        private final boolean[] nulls;
        private final int userColumnsOffset;
        private int varlenListBytes;
        private int nullBitmapBytes;

        public Builder(int columnCount, int userColumnsOffset) {
            this.offsets = new int[columnCount];
            this.lengths = new int[columnCount];
            this.nulls = new boolean[columnCount];
            this.userColumnsOffset = userColumnsOffset;
        }

        public Builder setField(int index, int offset, int length, boolean isNull) {
            offsets[index] = offset;
            lengths[index] = length;
            nulls[index] = isNull;
            return this;
        }

        public Builder setVarlenListBytes(int bytes) {
            this.varlenListBytes = bytes;
            return this;
        }

        public Builder setNullBitmapBytes(int bytes) {
            this.nullBitmapBytes = bytes;
            return this;
        }

        public FieldOffsets build() {
            return new FieldOffsets(offsets, lengths, nulls,
                userColumnsOffset, varlenListBytes, nullBitmapBytes);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("FieldOffsets{\n");
        for (int i = 0; i < offsets.length; i++) {
            sb.append(String.format("  [%d] offset=%d, len=%d, null=%b%n",
                i, offsets[i], lengths[i], nulls[i]));
        }
        sb.append("}");
        return sb.toString();
    }
}
