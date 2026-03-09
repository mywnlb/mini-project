package cn.zhangyis.minidb.storage.catalog.cache;

import cn.zhangyis.minidb.storage.catalog.DatabaseDescriptor;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalog 内存缓存
 *
 * <p>为读路径提供快速查找，使用 ConcurrentHashMap 实现无锁读。
 * 写操作由 CatalogManager 的 per-DB 锁保护。</p>
 *
 * <h3>并发策略</h3>
 * <ul>
 *   <li>读：直接查 ConcurrentHashMap，无锁</li>
 *   <li>写（DDL）：由 CatalogManager 获取对应 database 的写锁后调用</li>
 * </ul>
 */
public class CatalogCache {

    private final ConcurrentHashMap<Long, TableDescriptor> tableById;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, TableDescriptor>> tableByDbAndName;

    private final ConcurrentHashMap<String, DatabaseDescriptor> databaseByName;

    public CatalogCache() {
        this.tableById = new ConcurrentHashMap<>();
        this.tableByDbAndName = new ConcurrentHashMap<>();
        this.databaseByName = new ConcurrentHashMap<>();
    }

    // ==================== Database 操作 ====================

    public void putDatabase(DatabaseDescriptor db) {
        databaseByName.put(db.getDatabaseName(), db);
        tableByDbAndName.putIfAbsent(db.getDatabaseName(), new ConcurrentHashMap<>());
    }

    public DatabaseDescriptor getDatabase(String dbName) {
        return databaseByName.get(dbName);
    }

    public DatabaseDescriptor removeDatabase(String dbName) {
        Map<String, TableDescriptor> removedTables = tableByDbAndName.remove(dbName);
        if (removedTables != null) {
            for (TableDescriptor table : removedTables.values()) {
                tableById.remove(table.getTableId(), table);
            }
        }
        return databaseByName.remove(dbName);
    }

    public List<DatabaseDescriptor> listDatabases() {
        return Collections.unmodifiableList(new ArrayList<>(databaseByName.values()));
    }

    public boolean hasDatabase(String dbName) {
        return databaseByName.containsKey(dbName);
    }

    // ==================== Table 操作 ====================

    public void putTable(String dbName, TableDescriptor table) {
        tableById.put(table.getTableId(), table);
        tableByDbAndName.computeIfAbsent(dbName, k -> new ConcurrentHashMap<>())
                .put(table.getTableName(), table);
    }

    public TableDescriptor getTable(String dbName, String tableName) {
        Map<String, TableDescriptor> dbTables = tableByDbAndName.get(dbName);
        return dbTables != null ? dbTables.get(tableName) : null;
    }

    public TableDescriptor getTableById(long tableId) {
        return tableById.get(tableId);
    }

    public TableDescriptor removeTable(String dbName, String tableName) {
        Map<String, TableDescriptor> dbTables = tableByDbAndName.get(dbName);
        TableDescriptor removed = null;
        if (dbTables != null) {
            removed = dbTables.remove(tableName);
        }
        if (removed != null) {
            tableById.remove(removed.getTableId());
        }
        return removed;
    }

    /**
     * 按 tableId 移除表缓存（DDL Log 重放使用）
     *
     * <p>幂等：tableId 不存在时静默返回 null。</p>
     *
     * @param tableId 表 ID
     * @return 被移除的 TableDescriptor，不存在返回 null
     */
    public TableDescriptor removeTableById(long tableId) {
        TableDescriptor removed = tableById.remove(tableId);
        if (removed != null) {
            Map<String, TableDescriptor> dbTables = tableByDbAndName.get(
                    findDbNameByTableId(removed));
            if (dbTables != null) {
                dbTables.remove(removed.getTableName());
            }
        }
        return removed;
    }

    /**
     * 通过 TableDescriptor 的 databaseId 反查 dbName
     */
    private String findDbNameByTableId(TableDescriptor table) {
        for (Map.Entry<String, DatabaseDescriptor> entry : databaseByName.entrySet()) {
            if (entry.getValue().getDatabaseId() == table.getDatabaseId()) {
                return entry.getKey();
            }
        }
        return null;
    }

    public List<TableDescriptor> listTables(String dbName) {
        Map<String, TableDescriptor> dbTables = tableByDbAndName.get(dbName);
        if (dbTables == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(dbTables.values()));
    }

    // ==================== 批量加载（启动时使用）====================

    public void clear() {
        tableById.clear();
        tableByDbAndName.clear();
        databaseByName.clear();
    }
}
