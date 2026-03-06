package cn.zhangyis.minidb.storage.catalog;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库描述符（不可变标识 + 可变表映射）
 *
 * <p>表示一个逻辑数据库。databaseId/databaseName/charset/createTime 在创建后不可变，
 * tableNameToId 映射由 CatalogManager 在 DDL 操作时维护。</p>
 *
 * <h3>并发安全</h3>
 * <p>tableNameToId 使用 ConcurrentHashMap，读无锁；写由 CatalogManager 的 per-DB 锁保护。</p>
 */
public class DatabaseDescriptor {

    private final int databaseId;
    private final String databaseName;
    private final String charset;
    private final long createTime;

    /** tableName → tableId 映射 */
    private final ConcurrentHashMap<String, Long> tableNameToId;

    public DatabaseDescriptor(int databaseId, String databaseName,
                              String charset, long createTime) {
        this.databaseId = databaseId;
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.createTime = createTime;
        this.tableNameToId = new ConcurrentHashMap<>();
    }

    /**
     * 从持久化恢复（带已有表映射）
     */
    public DatabaseDescriptor(int databaseId, String databaseName,
                              String charset, long createTime,
                              Map<String, Long> existingTables) {
        this(databaseId, databaseName, charset, createTime);
        if (existingTables != null) {
            this.tableNameToId.putAll(existingTables);
        }
    }

    // ==================== Getters ====================

    public int getDatabaseId() {
        return databaseId;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getCharset() {
        return charset;
    }

    public long getCreateTime() {
        return createTime;
    }

    /**
     * 获取表名到 tableId 的只读视图
     */
    public Map<String, Long> getTableNameToId() {
        return Collections.unmodifiableMap(tableNameToId);
    }

    public int getTableCount() {
        return tableNameToId.size();
    }

    // ==================== 表映射维护（由 CatalogManager 调用）====================

    /**
     * 注册一张表
     *
     * @param tableName 表名
     * @param tableId   表 ID
     */
    public void addTable(String tableName, long tableId) {
        Long prev = tableNameToId.putIfAbsent(tableName, tableId);
        if (prev != null) {
            throw new IllegalStateException("Table already exists: " + tableName);
        }
    }

    /**
     * 移除一张表
     *
     * @param tableName 表名
     * @return 被移除的 tableId，不存在则返回 -1
     */
    public long removeTable(String tableName) {
        Long removed = tableNameToId.remove(tableName);
        return removed != null ? removed : -1;
    }

    /**
     * 查询表 ID
     *
     * @param tableName 表名
     * @return tableId，不存在返回 -1
     */
    public long getTableId(String tableName) {
        Long id = tableNameToId.get(tableName);
        return id != null ? id : -1;
    }

    /**
     * 检查表是否存在
     */
    public boolean hasTable(String tableName) {
        return tableNameToId.containsKey(tableName);
    }

    @Override
    public String toString() {
        return String.format("DatabaseDescriptor{id=%d, name='%s', charset='%s', tables=%d}",
                databaseId, databaseName, charset, tableNameToId.size());
    }
}
