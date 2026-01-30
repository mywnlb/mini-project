package cn.zhangyis.minidb.storage.btree;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引描述符
 *
 * <p>描述一个索引的完整信息，用于持久化和恢复。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class IndexDescriptor {

    /** 索引 ID */
    private final long indexId;

    /** 索引名称 */
    private final String indexName;

    /** 表 ID */
    private final long tableId;

    /** 空间 ID */
    private final int spaceId;

    /** 根页面号 */
    private int rootPageNo;

    /** 索引类型 */
    private final IndexType indexType;

    /** 树高度 */
    private int treeHeight;

    /** 记录数 */
    private long recordCount;

    /** 列定义（序列化格式） */
    private final List<ColumnDescriptor> columns;

    /** 创建时间 */
    private final long createTime;

    /** 最后更新时间 */
    private long lastUpdateTime;

    /** 是否已删除 */
    private boolean deleted;

    /**
     * 构造索引描述符
     */
    public IndexDescriptor(long indexId, String indexName, long tableId, int spaceId,
                           int rootPageNo, IndexType indexType, List<ColumnDescriptor> columns) {
        this.indexId = indexId;
        this.indexName = indexName;
        this.tableId = tableId;
        this.spaceId = spaceId;
        this.rootPageNo = rootPageNo;
        this.indexType = indexType;
        this.columns = new ArrayList<>(columns);
        this.treeHeight = 1;
        this.recordCount = 0;
        this.createTime = System.currentTimeMillis();
        this.lastUpdateTime = createTime;
        this.deleted = false;
    }

    /**
     * 创建单列整数索引描述符
     */
    public static IndexDescriptor createIntIndex(long indexId, String indexName, long tableId,
                                                  int spaceId, int rootPageNo, IndexType indexType,
                                                  String columnName) {
        List<ColumnDescriptor> columns = List.of(
                new ColumnDescriptor(columnName, ColumnType.INT, 4, false, false)
        );
        return new IndexDescriptor(indexId, indexName, tableId, spaceId, rootPageNo, indexType, columns);
    }

    // ==================== 序列化 ====================

    /**
     * 序列化为字节数组
     */
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(calculateSerializedSize());

        // 基本信息
        buffer.putLong(indexId);
        writeString(buffer, indexName);
        buffer.putLong(tableId);
        buffer.putInt(spaceId);
        buffer.putInt(rootPageNo);
        buffer.put((byte) indexType.ordinal());
        buffer.putInt(treeHeight);
        buffer.putLong(recordCount);
        buffer.putLong(createTime);
        buffer.putLong(lastUpdateTime);
        buffer.put((byte) (deleted ? 1 : 0));

        // 列信息
        buffer.putInt(columns.size());
        for (ColumnDescriptor col : columns) {
            col.serialize(buffer);
        }

        return buffer.array();
    }

    /**
     * 从字节数组反序列化
     */
    public static IndexDescriptor deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        long indexId = buffer.getLong();
        String indexName = readString(buffer);
        long tableId = buffer.getLong();
        int spaceId = buffer.getInt();
        int rootPageNo = buffer.getInt();
        IndexType indexType = IndexType.values()[buffer.get()];
        int treeHeight = buffer.getInt();
        long recordCount = buffer.getLong();
        long createTime = buffer.getLong();
        long lastUpdateTime = buffer.getLong();
        boolean deleted = buffer.get() == 1;

        int columnCount = buffer.getInt();
        List<ColumnDescriptor> columns = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            columns.add(ColumnDescriptor.deserialize(buffer));
        }

        IndexDescriptor desc = new IndexDescriptor(indexId, indexName, tableId, spaceId,
                rootPageNo, indexType, columns);
        desc.treeHeight = treeHeight;
        desc.recordCount = recordCount;
        desc.lastUpdateTime = lastUpdateTime;
        desc.deleted = deleted;

        return desc;
    }

    /**
     * 计算序列化大小
     */
    private int calculateSerializedSize() {
        int size = 8 + 4 + indexName.getBytes(StandardCharsets.UTF_8).length; // indexId + name
        size += 8 + 4 + 4; // tableId + spaceId + rootPageNo
        size += 1 + 4 + 8; // indexType + treeHeight + recordCount
        size += 8 + 8 + 1; // createTime + lastUpdateTime + deleted
        size += 4; // column count

        for (ColumnDescriptor col : columns) {
            size += col.calculateSerializedSize();
        }

        return size;
    }

    private static void writeString(ByteBuffer buffer, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    private static String readString(ByteBuffer buffer) {
        int len = buffer.getInt();
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== 转换方法 ====================

    /**
     * 转换为 BTreeMetadata
     */
    public BTreeMetadata toBTreeMetadata() {
        BTreeMetadata metadata = new BTreeMetadata(indexId, spaceId, rootPageNo);
        metadata.setTreeHeight(treeHeight);
        metadata.setRecordCount(recordCount);
        return metadata;
    }

    /**
     * 转换为 CompositeKeyDef
     */
    public CompositeKeyDef toCompositeKeyDef() {
        List<KeyColumn> keyColumns = new ArrayList<>();
        for (ColumnDescriptor col : columns) {
            keyColumns.add(col.toKeyColumn());
        }
        return new CompositeKeyDef(keyColumns);
    }

    /**
     * 从 BTreeMetadata 更新
     */
    public void updateFromMetadata(BTreeMetadata metadata) {
        this.rootPageNo = metadata.getRootPageNo();
        this.treeHeight = metadata.getTreeHeight();
        this.recordCount = metadata.getRecordCount();
        this.lastUpdateTime = System.currentTimeMillis();
    }

    // ==================== Getters and Setters ====================

    public long getIndexId() {
        return indexId;
    }

    public String getIndexName() {
        return indexName;
    }

    public long getTableId() {
        return tableId;
    }

    public int getSpaceId() {
        return spaceId;
    }

    public int getRootPageNo() {
        return rootPageNo;
    }

    public void setRootPageNo(int rootPageNo) {
        this.rootPageNo = rootPageNo;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public IndexType getIndexType() {
        return indexType;
    }

    public int getTreeHeight() {
        return treeHeight;
    }

    public void setTreeHeight(int treeHeight) {
        this.treeHeight = treeHeight;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public long getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(long recordCount) {
        this.recordCount = recordCount;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public List<ColumnDescriptor> getColumns() {
        return columns;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public boolean isUnique() {
        return indexType == IndexType.PRIMARY || indexType == IndexType.UNIQUE;
    }

    public boolean isPrimary() {
        return indexType == IndexType.PRIMARY;
    }

    @Override
    public String toString() {
        return String.format("IndexDescriptor{id=%d, name='%s', table=%d, type=%s, root=%d, height=%d, records=%d}",
                indexId, indexName, tableId, indexType, rootPageNo, treeHeight, recordCount);
    }

    // ==================== 列描述符 ====================

    /**
     * 列描述符
     */
    public static class ColumnDescriptor {
        private final String name;
        private final ColumnType type;
        private final int maxLength;
        private final boolean nullable;
        private final boolean descending;

        public ColumnDescriptor(String name, ColumnType type, int maxLength,
                                boolean nullable, boolean descending) {
            this.name = name;
            this.type = type;
            this.maxLength = maxLength;
            this.nullable = nullable;
            this.descending = descending;
        }

        public void serialize(ByteBuffer buffer) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(nameBytes.length);
            buffer.put(nameBytes);
            buffer.put((byte) type.ordinal());
            buffer.putInt(maxLength);
            buffer.put((byte) (nullable ? 1 : 0));
            buffer.put((byte) (descending ? 1 : 0));
        }

        public static ColumnDescriptor deserialize(ByteBuffer buffer) {
            int nameLen = buffer.getInt();
            byte[] nameBytes = new byte[nameLen];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            ColumnType type = ColumnType.values()[buffer.get()];
            int maxLength = buffer.getInt();
            boolean nullable = buffer.get() == 1;
            boolean descending = buffer.get() == 1;
            return new ColumnDescriptor(name, type, maxLength, nullable, descending);
        }

        public int calculateSerializedSize() {
            return 4 + name.getBytes(StandardCharsets.UTF_8).length + 1 + 4 + 1 + 1;
        }

        public KeyColumn toKeyColumn() {
            return new KeyColumn(name, type, maxLength, nullable, descending);
        }

        public String getName() {
            return name;
        }

        public ColumnType getType() {
            return type;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public boolean isNullable() {
            return nullable;
        }

        public boolean isDescending() {
            return descending;
        }
    }
}
