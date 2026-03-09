package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.btree.IndexDescriptor;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.btree.IndexType;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.cache.CatalogCache;
import cn.zhangyis.minidb.storage.catalog.ddl.DdlLogManager;
import cn.zhangyis.minidb.storage.catalog.persist.CatalogBootstrap;
import cn.zhangyis.minidb.storage.catalog.persist.CatalogMetaPage;
import cn.zhangyis.minidb.storage.catalog.persist.TableMetaPage;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.record.schema.SchemaRegistry;
import cn.zhangyis.minidb.storage.space.TableSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全局 Catalog 管理器
 *
 * <p>为上层（Handler API / SQL 层）提供访问元数据的统一接口。
 * 管理数据库、表、列等元数据的生命周期。</p>
 *
 * <h3>并发策略</h3>
 * <ul>
 *   <li>读：通过 CatalogCache 的 ConcurrentHashMap 直接查找，无锁</li>
 *   <li>写（DDL）：获取对应 database 名称锁，保证同一库名下 DDL 串行</li>
 *   <li>不同库名的 DDL 允许并发，但共享系统 catalog 页时仍会在页 latch 上串行</li>
 * </ul>
 *
 * <h3>持久化契约</h3>
 * <p>所有写操作必须先持久化 CatalogMetaPage / TableMetaPage，再更新 CatalogCache。
 * 任何对外可见的 Catalog 对象都必须对应已经提交的磁盘状态。</p>
 */
public class CatalogManager {

    private static final Logger log = LoggerFactory.getLogger(CatalogManager.class);

    private static final int SYSTEM_SPACE_ID = 0;
    private static final int TABLE_INDEX_META_PAGE_NO = 3;
    private static final String PRIMARY_INDEX_NAME = "PRIMARY";
    private static final String TABLESPACE_NAME_PREFIX = "table_";

    private final BufferPool bufferPool;
    private final IdGenerator idGenerator;
    private final CatalogCache cache;
    private final DdlLogManager ddlLogManager;

    /**
     * databaseName → DDL 锁。
     * 锁按名称常驻，避免 create/drop 同名数据库时出现“锁被移除后重新创建”的竞态。
     */
    private final ConcurrentHashMap<String, ReentrantLock> dbLocks;

    private volatile boolean initialized;

    public CatalogManager(BufferPool bufferPool) {
        this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
        this.idGenerator = new IdGenerator();
        this.cache = new CatalogCache();
        this.ddlLogManager = new DdlLogManager(bufferPool);
        this.dbLocks = new ConcurrentHashMap<>();
        this.initialized = false;
    }

    // ==================== 启动/关闭 ====================

    /**
     * 首次初始化（创建元数据页）
     */
    public void bootstrap() throws CatalogException {
        CatalogBootstrap bootstrap = new CatalogBootstrap(bufferPool);
        if (!bootstrap.isCatalogInitialized()) {
            bootstrap.initCatalog();
        }
        loadCatalog();
    }

    /**
     * 从磁盘加载 Catalog
     */
    public void loadCatalog() throws CatalogException {
        CatalogBootstrap bootstrap = new CatalogBootstrap(bufferPool);
        bootstrap.ensureDdlLogPageInitialized();
        CatalogBootstrap.CatalogSnapshot snapshot = bootstrap.loadCatalog();

        ddlLogManager.replayPendingIntents(snapshot.tables());
        ddlLogManager.refreshNextOpIdSeed();

        idGenerator.resetFrom(snapshot.idGenerator());

        cache.clear();
        dbLocks.clear();

        for (DatabaseDescriptor db : snapshot.databases()) {
            cache.putDatabase(db);
            getOrCreateDbLock(db.getDatabaseName());
        }

        for (TableDescriptor table : snapshot.tables()) {
            String dbName = findDatabaseName(table.getDatabaseId(), snapshot.databases());
            if (dbName != null) {
                ensureTablespaceOpen(table);
                cache.putTable(dbName, table);
            }
        }

        this.initialized = true;
    }

    /**
     * 关闭 Catalog，刷写 ID 计数器所在元数据页。
     */
    public void close() throws CatalogException {
        if (!initialized) {
            return;
        }
        try {
            persistIdGenerator();
            bufferPool.flushAllPages();
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("close", e);
        }
        initialized = false;
    }

    // ==================== 数据库操作 ====================

    public DatabaseDescriptor createDatabase(String name) throws CatalogException {
        return createDatabase(name, "utf8mb4");
    }

    public DatabaseDescriptor createDatabase(String name, String charset) throws CatalogException {
        ensureInitialized();
        validateIdentifier("database", name);
        validateIdentifier("charset", charset);

        ReentrantLock lock = getOrCreateDbLock(name);
        lock.lock();
        try {
            if (cache.hasDatabase(name)) {
                throw CatalogException.databaseAlreadyExists(name);
            }

            int dbId = Math.toIntExact(idGenerator.allocateDatabaseId());
            long createTime = System.currentTimeMillis();
            DatabaseDescriptor db = new DatabaseDescriptor(dbId, name, charset, createTime);

            persistDatabase(db);
            cache.putDatabase(db);
            return db;
        } finally {
            lock.unlock();
        }
    }

    public DatabaseDescriptor getDatabase(String name) throws CatalogException {
        ensureInitialized();
        DatabaseDescriptor db = cache.getDatabase(name);
        if (db == null) {
            throw CatalogException.databaseNotFound(name);
        }
        return db;
    }

    public void dropDatabase(String name) throws CatalogException {
        ensureInitialized();
        validateIdentifier("database", name);

        ReentrantLock lock = getOrCreateDbLock(name);
        lock.lock();
        try {
            DatabaseDescriptor db = cache.getDatabase(name);
            if (db == null) {
                throw CatalogException.databaseNotFound(name);
            }
            if (db.getTableCount() > 0) {
                throw CatalogException.databaseNotEmpty(name);
            }

            removeDatabaseFromPersistence(db);
            cache.removeDatabase(name);
        } finally {
            lock.unlock();
        }
    }

    public List<DatabaseDescriptor> listDatabases() throws CatalogException {
        ensureInitialized();
        return cache.listDatabases();
    }

    // ==================== 表操作（DDL）====================

    public TableDescriptor createTable(String dbName, String tableName,
                                       List<ColumnMeta> columns) throws CatalogException {
        return createTable(dbName, tableName, columns, List.of());
    }

    public TableDescriptor createTable(String dbName, String tableName,
                                       List<ColumnMeta> columns,
                                       List<IndexDefinition> indexes) throws CatalogException {
        ensureInitialized();
        validateIdentifier("database", dbName);
        validateIdentifier("table", tableName);

        List<ColumnMeta> normalizedColumns = normalizeColumns(columns);
        List<IndexDefinition> normalizedIndexes = normalizeIndexes(normalizedColumns, indexes);
        ReentrantLock lock = getOrCreateDbLock(dbName);
        lock.lock();
        try {
            DatabaseDescriptor db = cache.getDatabase(dbName);
            if (db == null) {
                throw CatalogException.databaseNotFound(dbName);
            }
            if (db.hasTable(tableName)) {
                throw CatalogException.tableAlreadyExists(tableName);
            }

            long tableId = idGenerator.allocateTableId();
            int spaceId = Math.toIntExact(tableId);
            long ddlOpId = ddlLogManager.appendDeleteSpaceIntentAndForce(spaceId, tableId);
            List<ColumnMeta> persistedColumns = assignColumnIds(normalizedColumns);
            RecordSchema schema = buildSchema(persistedColumns);
            SchemaRegistry schemaRegistry = new SchemaRegistry(schema);
            long createTime = System.currentTimeMillis();
            String tablespaceName = tablespaceName(spaceId);
            DiskManager diskManager = bufferPool.getDiskManager();
            boolean tablespaceCreated = false;

            try {
                diskManager.createTablespace(spaceId, tablespaceName);
                tablespaceCreated = true;

                TableDescriptor table;
                try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                    initializeTableStorage(spaceId, mtr);
                    CreatedIndexes createdIndexes = createIndexes(
                            tableId,
                            spaceId,
                            persistedColumns,
                            normalizedIndexes,
                            mtr
                    );

                    table = new TableDescriptor(
                            tableId,
                            tableName,
                            db.getDatabaseId(),
                            spaceId,
                            schemaRegistry,
                            persistedColumns,
                            createdIndexes.primaryIndexId(),
                            createdIndexes.secondaryIndexIds(),
                            createTime,
                            createTime,
                            TableDescriptor.TableState.ACTIVE
                    );

                    int newTableCount = db.getTableCount() + 1;
                    persistTableCreate(table, newTableCount, mtr);
                    mtr.commit();
                }

                db.addTable(tableName, tableId);
                cache.putTable(dbName, table);
                compactIntentBestEffort(ddlOpId, "createTable success");
                return table;
            } catch (Exception e) {
                boolean cleanupCompleted = !tablespaceCreated;
                if (tablespaceCreated) {
                    try {
                        cleanupCompleted = deleteTablespaceIfExists(spaceId, tablespaceName);
                    } catch (MiniDbException cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
                if (cleanupCompleted) {
                    compactIntentBestEffort(ddlOpId, "createTable rollback path");
                }
                if (e instanceof CatalogException catalogException) {
                    throw catalogException;
                }
                if (e instanceof MiniDbException miniDbException) {
                    throw CatalogException.persistenceFailed("createTable", miniDbException);
                }
                throw new CatalogException("Failed to create table: " + tableName, e);
            }
        } catch (ArithmeticException e) {
            throw new CatalogException("tableId exceeds placeholder spaceId range", e);
        } finally {
            lock.unlock();
        }
    }

    public void dropTable(String dbName, String tableName) throws CatalogException {
        ensureInitialized();
        validateIdentifier("database", dbName);
        validateIdentifier("table", tableName);

        ReentrantLock lock = getOrCreateDbLock(dbName);
        lock.lock();
        try {
            DatabaseDescriptor db = cache.getDatabase(dbName);
            if (db == null) {
                throw CatalogException.databaseNotFound(dbName);
            }

            TableDescriptor table = cache.getTable(dbName, tableName);
            if (table == null) {
                throw CatalogException.tableNotFound(tableName);
            }

            long ddlOpId = ddlLogManager.appendDeleteSpaceIntentAndForce(table.getSpaceId(), table.getTableId());
            int newTableCount = db.getTableCount() - 1;
            persistTableDrop(table.getTableId(), db.getDatabaseId(), newTableCount);

            table.setState(TableDescriptor.TableState.DROPPED);
            table.setLastUpdateTime(System.currentTimeMillis());
            db.removeTable(tableName);
            cache.removeTable(dbName, tableName);

            try {
                deleteTablespaceIfExists(table.getSpaceId(), tablespaceName(table.getSpaceId()));
                compactIntentBestEffort(ddlOpId, "dropTable post-DDL cleanup");
            } catch (MiniDbException e) {
                log.warn("Post-DDL cleanup failed for dropped table tableId={}, spaceId={}, reason={}",
                        table.getTableId(), table.getSpaceId(), e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    public TableDescriptor getTable(String dbName, String tableName) throws CatalogException {
        ensureInitialized();
        TableDescriptor table = cache.getTable(dbName, tableName);
        if (table == null) {
            throw CatalogException.tableNotFound(tableName);
        }
        return table;
    }

    public TableDescriptor getTableById(long tableId) throws CatalogException {
        ensureInitialized();
        TableDescriptor table = cache.getTableById(tableId);
        if (table == null) {
            throw CatalogException.tableNotFound(tableId);
        }
        return table;
    }

    public List<TableDescriptor> listTables(String dbName) throws CatalogException {
        ensureInitialized();
        if (!cache.hasDatabase(dbName)) {
            throw CatalogException.databaseNotFound(dbName);
        }
        return cache.listTables(dbName);
    }

    // ==================== Schema 查询（DML 时调用）====================

    public RecordSchema getCurrentSchema(long tableId) throws CatalogException {
        ensureInitialized();
        TableDescriptor table = cache.getTableById(tableId);
        if (table == null) {
            throw CatalogException.tableNotFound(tableId);
        }
        return table.getSchemaRegistry().getCurrentSchema();
    }

    public SchemaRegistry getSchemaRegistry(long tableId) throws CatalogException {
        ensureInitialized();
        TableDescriptor table = cache.getTableById(tableId);
        if (table == null) {
            throw CatalogException.tableNotFound(tableId);
        }
        return table.getSchemaRegistry();
    }

    // ==================== 内部访问 ====================

    public IdGenerator getIdGenerator() {
        return idGenerator;
    }

    public CatalogCache getCache() {
        return cache;
    }

    DdlLogManager getDdlLogManager() {
        return ddlLogManager;
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ==================== 持久化方法 ====================

    private void persistDatabase(DatabaseDescriptor db) throws CatalogException {
        try {
            PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();
                try {
                    if (!CatalogMetaPage.writeDatabase(frame, db)) {
                        throw new CatalogException("No space in CatalogMetaPage for database: " + db.getDatabaseName());
                    }
                    CatalogMetaPage.writeIdGenerator(frame, idGenerator);
                    mtr.markDirty(frame.getPage());
                } finally {
                    frame.writeUnlock();
                }
                mtr.commit();
            }
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("createDatabase", e);
        }
    }

    private void removeDatabaseFromPersistence(DatabaseDescriptor db) throws CatalogException {
        try {
            PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();
                try {
                    List<DatabaseDescriptor> current = CatalogMetaPage.readAllDatabases(frame);
                    List<DatabaseDescriptor> remaining = new ArrayList<>(Math.max(current.size() - 1, 0));
                    boolean found = false;
                    for (DatabaseDescriptor persisted : current) {
                        if (persisted.getDatabaseId() == db.getDatabaseId()) {
                            found = true;
                        } else {
                            remaining.add(persisted);
                        }
                    }
                    if (!found) {
                        throw new CatalogException("Database not found in CatalogMetaPage: " + db.getDatabaseName());
                    }
                    if (!CatalogMetaPage.rewriteAllDatabases(frame, remaining)) {
                        throw new CatalogException("Failed to rewrite databases after drop");
                    }
                    mtr.markDirty(frame.getPage());
                } finally {
                    frame.writeUnlock();
                }
                mtr.commit();
            }
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("dropDatabase", e);
        }
    }

    private void persistTableCreate(TableDescriptor table, int newTableCount) throws CatalogException {
        try {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                persistTableCreate(table, newTableCount, mtr);
                mtr.commit();
            }
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("createTable", e);
        }
    }

    private void persistTableCreate(TableDescriptor table, int newTableCount, MiniTransaction mtr)
            throws MiniDbException {
        PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
        TableMetaPage.TableEntry entry = toTableEntry(table);
        boolean created = false;

        BufferFrame metaFrame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
        metaFrame.writeLock();
        try {
            int firstTableMetaPageNo = CatalogMetaPage.readFirstTableMetaPage(metaFrame);
            if (!databaseExists(metaFrame, table.getDatabaseId())) {
                throw new CatalogException("Database not found in CatalogMetaPage: dbId=" + table.getDatabaseId());
            }
            if (firstTableMetaPageNo == 0) {
                throw new CatalogException("Catalog first TableMetaPage is missing");
            }

            int currentPageNo = firstTableMetaPageNo;
            while (currentPageNo != 0 && !created) {
                BufferFrame frame = mtr.getPageFrame(
                        PageId.of(SYSTEM_SPACE_ID, currentPageNo),
                        BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();
                try {
                    if (TableMetaPage.writeEntry(frame, entry)) {
                        CatalogMetaPage.writeIdGenerator(metaFrame, idGenerator);
                        if (!CatalogMetaPage.updateDatabaseTableCount(
                                metaFrame, table.getDatabaseId(), newTableCount)) {
                            throw new CatalogException("Database not found in CatalogMetaPage: dbId="
                                    + table.getDatabaseId());
                        }
                        mtr.markDirty(frame.getPage());
                        mtr.markDirty(metaFrame.getPage());
                        created = true;
                    } else {
                        int nextPage = TableMetaPage.readNextPage(frame);
                        if (nextPage == 0) {
                            BufferFrame newFrame = mtr.newPageFrame(SYSTEM_SPACE_ID);
                            newFrame.writeLock();
                            try {
                                TableMetaPage.initPage(newFrame);
                                if (!TableMetaPage.writeEntry(newFrame, entry)) {
                                    throw new CatalogException("No space in new TableMetaPage for table: "
                                            + table.getTableName());
                                }
                                TableMetaPage.writeNextPage(frame, newFrame.getPageId().getPageNo());
                                CatalogMetaPage.writeIdGenerator(metaFrame, idGenerator);
                                if (!CatalogMetaPage.updateDatabaseTableCount(
                                        metaFrame, table.getDatabaseId(), newTableCount)) {
                                    throw new CatalogException("Database not found in CatalogMetaPage: dbId="
                                            + table.getDatabaseId());
                                }
                                mtr.markDirty(frame.getPage());
                                mtr.markDirty(newFrame.getPage());
                                mtr.markDirty(metaFrame.getPage());
                                created = true;
                            } finally {
                                newFrame.writeUnlock();
                            }
                        } else {
                            currentPageNo = nextPage;
                        }
                    }
                } finally {
                    frame.writeUnlock();
                }
            }
        } finally {
            metaFrame.writeUnlock();
        }

        if (!created) {
            throw new CatalogException("Catalog first TableMetaPage is missing");
        }
    }

    private void persistTableDrop(long tableId, int databaseId, int newTableCount) throws CatalogException {
        try {
            PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
            boolean deleted = false;

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame metaFrame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
                metaFrame.writeLock();
                try {
                    int firstTableMetaPageNo = CatalogMetaPage.readFirstTableMetaPage(metaFrame);
                    if (!databaseExists(metaFrame, databaseId)) {
                        throw new CatalogException("Database not found in CatalogMetaPage: dbId=" + databaseId);
                    }
                    if (firstTableMetaPageNo == 0) {
                        throw new CatalogException("Catalog first TableMetaPage is missing");
                    }

                    int currentPageNo = firstTableMetaPageNo;
                    while (currentPageNo != 0 && !deleted) {
                        BufferFrame frame = mtr.getPageFrame(
                                PageId.of(SYSTEM_SPACE_ID, currentPageNo),
                                BufferPool.FetchMode.READ_EXISTING);
                        frame.writeLock();
                        try {
                            if (containsTable(frame, tableId)) {
                                if (!TableMetaPage.deleteEntry(frame, tableId)) {
                                    throw new CatalogException("Table not found during delete: tableId=" + tableId);
                                }
                                if (!CatalogMetaPage.updateDatabaseTableCount(metaFrame, databaseId, newTableCount)) {
                                    throw new CatalogException("Database not found in CatalogMetaPage: dbId=" + databaseId);
                                }
                                mtr.markDirty(frame.getPage());
                                mtr.markDirty(metaFrame.getPage());
                                deleted = true;
                            } else {
                                currentPageNo = TableMetaPage.readNextPage(frame);
                            }
                        } finally {
                            frame.writeUnlock();
                        }
                    }
                } finally {
                    metaFrame.writeUnlock();
                }

                if (!deleted) {
                    throw CatalogException.tableNotFound(tableId);
                }
                mtr.commit();
            }
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("dropTable", e);
        }
    }

    private void persistIdGenerator() throws CatalogException {
        try {
            PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();
                try {
                    CatalogMetaPage.writeIdGenerator(frame, idGenerator);
                    mtr.markDirty(frame.getPage());
                } finally {
                    frame.writeUnlock();
                }
                mtr.commit();
            }
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("persistIdGenerator", e);
        }
    }

    // ==================== 工具方法 ====================

    private void ensureInitialized() throws CatalogException {
        if (!initialized) {
            throw CatalogException.catalogNotInitialized();
        }
    }

    private ReentrantLock getOrCreateDbLock(String dbName) {
        return dbLocks.computeIfAbsent(dbName, ignored -> new ReentrantLock());
    }

    private void validateIdentifier(String type, String value) throws CatalogException {
        if (value == null || value.isBlank()) {
            throw new CatalogException(type + " name must not be blank");
        }
    }

    private List<ColumnMeta> normalizeColumns(List<ColumnMeta> columns) throws CatalogException {
        if (columns == null || columns.isEmpty()) {
            throw new CatalogException("Table must contain at least one column");
        }

        List<ColumnMeta> sorted = new ArrayList<>(columns);
        sorted.sort(Comparator.comparingInt(ColumnMeta::getOrdinal));

        Set<String> columnNames = new HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            ColumnMeta column = sorted.get(i);
            if (column.getOrdinal() != i) {
                throw new CatalogException("Column ordinals must be contiguous starting at 0");
            }
            validateIdentifier("column", column.getName());
            if (!columnNames.add(column.getName())) {
                throw new CatalogException("Duplicate column name: " + column.getName());
            }
        }
        return sorted;
    }

    private List<ColumnMeta> assignColumnIds(List<ColumnMeta> columns) {
        List<ColumnMeta> assigned = new ArrayList<>(columns.size());
        for (ColumnMeta column : columns) {
            assigned.add(new ColumnMeta(
                    idGenerator.allocateColumnId(),
                    column.getName(),
                    column.getType(),
                    column.getOrdinal(),
                    column.getDefaultValue()
            ));
        }
        return assigned;
    }

    private List<IndexDefinition> normalizeIndexes(List<ColumnMeta> columns, List<IndexDefinition> indexes)
            throws CatalogException {
        List<IndexDefinition> requested = indexes;
        if (requested == null || requested.isEmpty()) {
            requested = List.of(IndexDefinition.primary(columns.get(0).getName()));
        }

        Map<String, ColumnMeta> columnsByName = indexColumnsByName(columns);
        Set<String> indexNames = new HashSet<>();
        List<IndexDefinition> normalized = new ArrayList<>(requested.size());
        boolean primarySeen = false;

        for (IndexDefinition definition : requested) {
            if (definition == null) {
                throw new CatalogException("Index definition must not be null");
            }
            if (definition.getColumns().isEmpty()) {
                throw new CatalogException("Index must contain at least one column");
            }

            String normalizedName = normalizeIndexName(definition);
            if (!indexNames.add(normalizedName)) {
                throw new CatalogException("Duplicate index name: " + normalizedName);
            }

            boolean primary = definition.getIndexType() == IndexType.PRIMARY;
            if (primary) {
                if (primarySeen) {
                    throw CatalogException.invalidPrimaryKey("multiple PRIMARY indexes");
                }
                primarySeen = true;
            }

            Set<String> seenColumns = new HashSet<>();
            List<IndexDefinition.IndexColumn> normalizedColumns = new ArrayList<>(definition.getColumns().size());
            for (IndexDefinition.IndexColumn indexColumn : definition.getColumns()) {
                validateIdentifier("index column", indexColumn.getColumnName());
                ColumnMeta column = columnsByName.get(indexColumn.getColumnName());
                if (column == null) {
                    throw new CatalogException("Index references unknown column: " + indexColumn.getColumnName());
                }
                if (!seenColumns.add(column.getName())) {
                    throw new CatalogException("Duplicate column in index " + normalizedName + ": " + column.getName());
                }
                if (primary && column.isNullable()) {
                    throw CatalogException.invalidPrimaryKey("column must be NOT NULL: " + column.getName());
                }
                normalizedColumns.add(new IndexDefinition.IndexColumn(column.getName(), indexColumn.isDescending()));
            }

            normalized.add(new IndexDefinition(normalizedName, definition.getIndexType(), normalizedColumns));
        }

        if (!primarySeen) {
            throw CatalogException.invalidPrimaryKey("PRIMARY index is required");
        }
        return normalized;
    }

    private RecordSchema buildSchema(List<ColumnMeta> columns) {
        RecordSchema.Builder schemaBuilder = RecordSchema.builder().version(1);
        for (ColumnMeta column : columns) {
            schemaBuilder.column(column.toColumnDescriptor());
        }
        return schemaBuilder.build();
    }

    private boolean databaseExists(BufferFrame metaFrame, int databaseId) throws MiniDbException {
        for (DatabaseDescriptor db : CatalogMetaPage.readAllDatabases(metaFrame)) {
            if (db.getDatabaseId() == databaseId) {
                return true;
            }
        }
        return false;
    }

    private boolean containsTable(BufferFrame frame, long tableId) throws MiniDbException {
        for (TableMetaPage.TableEntry entry : TableMetaPage.readAllEntries(frame)) {
            if (entry.tableId() == tableId) {
                return true;
            }
        }
        return false;
    }

    private String findDatabaseName(int databaseId, List<DatabaseDescriptor> databases) {
        for (DatabaseDescriptor db : databases) {
            if (db.getDatabaseId() == databaseId) {
                return db.getDatabaseName();
            }
        }
        return null;
    }

    private void ensureTablespaceOpen(TableDescriptor table) throws CatalogException {
        DiskManager diskManager = bufferPool.getDiskManager();
        if (diskManager.tablespaceExists(table.getSpaceId())) {
            return;
        }
        try {
            diskManager.openTablespace(table.getSpaceId(), tablespaceName(table.getSpaceId()));
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("openTablespace tableId=" + table.getTableId(), e);
        }
    }

    private void initializeTableStorage(int spaceId, MiniTransaction mtr) throws MiniDbException {
        TableSpace tableSpace = new TableSpace(spaceId, bufferPool);
        tableSpace.initializeTablespace(mtr);

        BufferFrame metaFrame = mtr.newPageFrame(spaceId);
        if (metaFrame.getPageId().getPageNo() != TABLE_INDEX_META_PAGE_NO) {
            throw new CatalogException("Index meta page must be page " + TABLE_INDEX_META_PAGE_NO
                    + ", got " + metaFrame.getPageId().getPageNo());
        }
    }

    private CreatedIndexes createIndexes(long tableId, int spaceId, List<ColumnMeta> columns,
                                         List<IndexDefinition> indexes, MiniTransaction mtr)
            throws MiniDbException {
        IndexManager indexManager = new IndexManager(bufferPool, spaceId, TABLE_INDEX_META_PAGE_NO);
        indexManager.initialize(mtr);

        Map<String, ColumnMeta> columnsByName = indexColumnsByName(columns);
        long primaryIndexId = 0;
        List<Long> secondaryIndexIds = new ArrayList<>();

        for (IndexDefinition index : indexes) {
            long indexId = idGenerator.allocateIndexId();
            List<IndexDescriptor.ColumnDescriptor> indexColumns = new ArrayList<>(index.getColumns().size());
            for (IndexDefinition.IndexColumn columnRef : index.getColumns()) {
                ColumnMeta column = columnsByName.get(columnRef.getColumnName());
                indexColumns.add(TypeBridge.toIndexColumn(column, columnRef.isDescending()));
            }

            indexManager.createIndex(
                    indexId,
                    index.getIndexName(),
                    tableId,
                    index.getIndexType(),
                    indexColumns,
                    mtr
            );

            if (index.getIndexType() == IndexType.PRIMARY) {
                primaryIndexId = indexId;
            } else {
                secondaryIndexIds.add(indexId);
            }
        }

        if (primaryIndexId == 0) {
            throw CatalogException.invalidPrimaryKey("PRIMARY index is required");
        }
        return new CreatedIndexes(primaryIndexId, secondaryIndexIds);
    }

    private Map<String, ColumnMeta> indexColumnsByName(List<ColumnMeta> columns) {
        Map<String, ColumnMeta> columnsByName = new HashMap<>();
        for (ColumnMeta column : columns) {
            columnsByName.put(column.getName(), column);
        }
        return columnsByName;
    }

    private String normalizeIndexName(IndexDefinition definition) throws CatalogException {
        if (definition.getIndexType() == IndexType.PRIMARY) {
            return PRIMARY_INDEX_NAME;
        }
        validateIdentifier("index", definition.getIndexName());
        return definition.getIndexName();
    }

    private String tablespaceName(int spaceId) {
        return TABLESPACE_NAME_PREFIX + spaceId;
    }

    private boolean deleteTablespaceIfExists(int spaceId, String tablespaceName) throws MiniDbException {
        int pageCount = bufferPool.getDiskManager().getPageCount(spaceId);
        for (int pageNo = 0; pageNo < pageCount; pageNo++) {
            bufferPool.deletePage(PageId.of(spaceId, pageNo));
        }
        return bufferPool.getDiskManager().dropTablespaceIfExists(spaceId, tablespaceName);
    }

    private void compactIntentBestEffort(long ddlOpId, String reason) {
        try {
            ddlLogManager.compactByOpId(ddlOpId);
        } catch (CatalogException e) {
            log.warn("DDL log compaction deferred, ddlOpId={}, reason={}, detail={}",
                    ddlOpId, reason, e.getMessage());
        }
    }

    private static TableMetaPage.TableEntry toTableEntry(TableDescriptor table) {
        return new TableMetaPage.TableEntry(
                table.getTableId(),
                table.getTableName(),
                table.getDatabaseId(),
                table.getSpaceId(),
                table.getColumns(),
                table.getPrimaryIndexId(),
                table.getSecondaryIndexIds(),
                table.getCreateTime(),
                table.getLastUpdateTime(),
                table.getState()
        );
    }

    private record CreatedIndexes(long primaryIndexId, List<Long> secondaryIndexIds) {}
}
