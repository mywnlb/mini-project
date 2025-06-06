package cn.zhangyis.storage.catalog;

import cn.zhangyis.exceptions.CatalogException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Description TODO
 * @Date 2025/5/27 17:33
 * @Created by libo
 */
public class CatalogManager {
    private static CatalogManager instance;
    /**
     * A map to hold the tables in the catalog.
     * The key is the table name, and the value is the Table object.
     */
    private Map<String, Table> tables = new ConcurrentHashMap<>();

    private CatalogManager() {
    }


    public static CatalogManager getInstance() {
        if (instance == null) {
            instance = new CatalogManager();
        }
        return instance;
    }

    /**
     * Checks if a table exists in the catalog.
     *
     * @param tableName The name of the table to check.
     * @return true if the table exists, false otherwise.
     */
    public boolean tableExists(String tableName) {
        return tables.containsKey(tableName);
    }

    public Table getTable(String tableName) {
        if (!tableExists(tableName)) {
            throw new CatalogException("Table " + tableName + " does not exist.");
        }
        return tables.get(tableName);
    }

    /**
     * Adds a table to the catalog.
     *
     * @param table The table to add.
     */
    public void addTable(Table table) {
        if (tableExists(table.getName())) {
            throw new CatalogException("Table " + table.getName() + " already exists.");
        }
        tables.put(table.getName(), table);
    }
}
