package cn.zhangyis.minidb.storage.catalog.persist;

import cn.zhangyis.minidb.common.exception.DiskIOException;
import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogException;
import cn.zhangyis.minidb.storage.catalog.ColumnMeta;
import cn.zhangyis.minidb.storage.catalog.DatabaseDescriptor;
import cn.zhangyis.minidb.storage.catalog.IdGenerator;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import cn.zhangyis.minidb.storage.catalog.ddl.DdlLogPage;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.record.schema.SchemaRegistry;
import cn.zhangyis.minidb.storage.transaction.core.TransactionSysPage;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalog 引导加载器
 *
 * <p>负责数据库启动时的 Catalog 引导与恢复流程：</p>
 * <ol>
 *   <li>打开系统表空间 (space 0)</li>
 *   <li>读取 Page 3 (CatalogMetaPage)</li>
 *   <li>恢复 IdGenerator 的计数器</li>
 *   <li>遍历所有 DatabaseEntry → 构建 DatabaseDescriptor</li>
 *   <li>遍历 TableMetaPage 链 → 构建 TableDescriptor</li>
 *   <li>对每个 TableDescriptor：从列定义重建 RecordSchema + SchemaRegistry</li>
 * </ol>
 *
 * <h3>锁序规则</h3>
 * <p>Catalog 固定页必须按页号升序加 latch：CatalogMetaPage(page 3)
 * → TableMetaPage(page 4+)。Bootstrap 初始化和后续 DDL 都必须遵循该顺序，
 * 避免出现 page 4 先于 page 3 的反向锁序。</p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * CatalogBootstrap bootstrap = new CatalogBootstrap(bufferPool);
 *
 * // 首次启动：初始化元数据页
 * if (!bootstrap.isCatalogInitialized()) {
 *     bootstrap.initCatalog();
 * }
 *
 * // 加载 Catalog
 * CatalogSnapshot snapshot = bootstrap.loadCatalog();
 * </pre>
 */
public class CatalogBootstrap {

    private static final int SYSTEM_SPACE_ID = 0;

    private final BufferPool bufferPool;

    public CatalogBootstrap(BufferPool bufferPool) {
        this.bufferPool = bufferPool;
    }

    /**
     * 检查 Catalog 是否已初始化
     *
     * @return true 如果 CatalogMetaPage 已存在且有效
     * @throws CatalogException 读取失败时
     */
    public boolean isCatalogInitialized() throws CatalogException {
        PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                return CatalogMetaPage.isValid(frame);
            } finally {
                frame.readUnlock();
            }
        } catch (MiniDbException e) {
            if (isMissingPageError(e)) {
                return false;
            }
            throw new CatalogException("Failed to check catalog initialization", e);
        }
    }

    /**
     * 首次初始化 Catalog 元数据页
     *
     * <p>创建 CatalogMetaPage (Page 3)、第一个 TableMetaPage (Page 4) 和 DDL Log head page (Page 5)。</p>
     *
     * @throws CatalogException 初始化失败时
     */
    public void initCatalog() throws CatalogException {
        PageId catalogPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
        PageId tableMetaPageId = PageId.of(SYSTEM_SPACE_ID, TableMetaPage.FIRST_TABLE_META_PAGE_NO);
        PageId ddlLogPageId = PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO);

        try {
            ensureSystemCatalogPagesAllocated(DdlLogPage.DDL_LOG_PAGE_NO);

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame catalogFrame = mtr.getPageFrame(catalogPageId, BufferPool.FetchMode.READ_EXISTING);
                BufferFrame tableMetaFrame = mtr.getPageFrame(tableMetaPageId, BufferPool.FetchMode.READ_EXISTING);
                BufferFrame ddlLogFrame = mtr.getPageFrame(ddlLogPageId, BufferPool.FetchMode.READ_EXISTING);

                catalogFrame.writeLock();
                tableMetaFrame.writeLock();
                ddlLogFrame.writeLock();
                try {
                    // 按 pageNo 顺序持锁；发布顺序仍然让 page 3 最后完成。
                    TableMetaPage.initPage(tableMetaFrame);
                    DdlLogPage.initPage(ddlLogFrame);
                    CatalogMetaPage.initPage(catalogFrame);
                    CatalogMetaPage.writeFirstTableMetaPage(catalogFrame, tableMetaPageId.getPageNo());

                    mtr.markDirty(tableMetaFrame.getPage());
                    mtr.markDirty(ddlLogFrame.getPage());
                    mtr.markDirty(catalogFrame.getPage());
                } finally {
                    ddlLogFrame.writeUnlock();
                    tableMetaFrame.writeUnlock();
                    catalogFrame.writeUnlock();
                }

                mtr.commit();
            }

            // 强制刷盘，确保 catalog 页面持久化到磁盘
            bufferPool.flushPage(catalogPageId);
            bufferPool.flushPage(tableMetaPageId);
            bufferPool.flushPage(ddlLogPageId);
        } catch (MiniDbException e) {
            throw new CatalogException("Failed to initialize catalog", e);
        }
    }

    /**
     * 兼容升级路径：为已存在的 catalog 补齐 DDL Log head page。
     */
    public void ensureDdlLogPageInitialized() throws CatalogException {
        PageId ddlLogPageId = PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO);
        try {
            ensureSystemCatalogPagesAllocated(DdlLogPage.DDL_LOG_PAGE_NO);
            List<PageId> pagesToFlush = new ArrayList<>();
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame ddlLogFrame = mtr.getPageFrame(ddlLogPageId, BufferPool.FetchMode.READ_EXISTING);
                ddlLogFrame.writeLock();
                try {
                    if (DdlLogPage.isValid(ddlLogFrame)) {
                        // 已是新布局，无需处理
                    } else if (TransactionSysPage.isTrxSysPage(ddlLogFrame.buffer())) {
                        BufferFrame trxSysFrame = mtr.newPageFrame(SYSTEM_SPACE_ID);
                        trxSysFrame.writeLock();
                        try {
                            if (trxSysFrame.getPageId().getPageNo() < TransactionSysPage.DEFAULT_PAGE_NO) {
                                throw new MiniDbException("Legacy TRX_SYS migration allocated reserved page: "
                                        + trxSysFrame.getPageId());
                            }
                            migrateLegacyTrxSysPage(ddlLogFrame, trxSysFrame);
                            mtr.markDirty(trxSysFrame.getPage());
                            pagesToFlush.add(trxSysFrame.getPageId());
                        } finally {
                            trxSysFrame.writeUnlock();
                        }

                        DdlLogPage.initPage(ddlLogFrame);
                        mtr.markDirty(ddlLogFrame.getPage());
                        pagesToFlush.add(ddlLogPageId);
                    } else {
                        DdlLogPage.initPage(ddlLogFrame);
                        mtr.markDirty(ddlLogFrame.getPage());
                        pagesToFlush.add(ddlLogPageId);
                    }
                } finally {
                    ddlLogFrame.writeUnlock();
                }
                mtr.commit();
            }
            for (PageId pageId : pagesToFlush) {
                bufferPool.flushPage(pageId);
            }
        } catch (MiniDbException e) {
            throw new CatalogException("Failed to ensure DDL log page", e);
        }
    }

    private void migrateLegacyTrxSysPage(BufferFrame legacyFrame, BufferFrame newFrame) {
        TransactionSysPage.init(newFrame.buffer(), SYSTEM_SPACE_ID);
        TransactionSysPage.copyHeader(legacyFrame.buffer(), newFrame.buffer());
    }

    /**
     * 从磁盘加载完整的 Catalog 快照
     *
     * @return CatalogSnapshot 包含所有恢复的元数据
     * @throws CatalogException 加载失败时
     */
    public CatalogSnapshot loadCatalog() throws CatalogException {
        try {
            PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);

            IdGenerator idGenerator;
            List<DatabaseDescriptor> databases;
            int firstTableMetaPageNo;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame metaFrame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
                metaFrame.readLock();
                try {
                    CatalogMetaPage.assertValid(metaFrame);
                    idGenerator = CatalogMetaPage.readIdGenerator(metaFrame);
                    databases = CatalogMetaPage.readAllDatabases(metaFrame);
                    firstTableMetaPageNo = CatalogMetaPage.readFirstTableMetaPage(metaFrame);
                } finally {
                    metaFrame.readUnlock();
                }
            }

            List<TableDescriptor> tables = new ArrayList<>();
            int currentPageNo = firstTableMetaPageNo;

            while (currentPageNo != 0) {
                PageId pageId = PageId.of(SYSTEM_SPACE_ID, currentPageNo);
                List<TableMetaPage.TableEntry> entries;
                int nextPageNo;

                try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                    BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
                    frame.readLock();
                    try {
                        entries = TableMetaPage.readAllEntries(frame);
                        nextPageNo = TableMetaPage.readNextPage(frame);
                    } finally {
                        frame.readUnlock();
                    }
                }

                for (TableMetaPage.TableEntry entry : entries) {
                    TableDescriptor tableDesc = rebuildTableDescriptor(entry);
                    tables.add(tableDesc);

                    for (DatabaseDescriptor db : databases) {
                        if (db.getDatabaseId() == entry.databaseId()) {
                            db.addTable(entry.tableName(), entry.tableId());
                            break;
                        }
                    }
                }

                currentPageNo = nextPageNo;
            }

            return new CatalogSnapshot(idGenerator, databases, tables);
        } catch (MiniDbException e) {
            throw new CatalogException("Failed to load catalog", e);
        }
    }

    private TableDescriptor rebuildTableDescriptor(TableMetaPage.TableEntry entry) {
        RecordSchema.Builder builder = RecordSchema.builder().version(1);
        for (ColumnMeta col : entry.columns()) {
            builder.column(col.toColumnDescriptor());
        }
        RecordSchema schema = builder.build();
        SchemaRegistry registry = new SchemaRegistry(schema);

        return new TableDescriptor(
                entry.tableId(),
                entry.tableName(),
                entry.databaseId(),
                entry.spaceId(),
                registry,
                entry.columns(),
                entry.primaryIndexId(),
                entry.secondaryIndexIds(),
                entry.createTime(),
                entry.lastUpdateTime(),
                entry.state()
        );
    }

    private void ensureSystemCatalogPagesAllocated(int requiredPageNo) throws MiniDbException {
        if (pageExists(requiredPageNo)) {
            return;
        }

        int allocatedPageNo = -1;
        while (allocatedPageNo < requiredPageNo) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame frame = mtr.newPageFrame(SYSTEM_SPACE_ID);
                allocatedPageNo = frame.getPageId().getPageNo();
                mtr.commit();
            }
        }
    }

    private boolean pageExists(int pageNo) throws MiniDbException {
        PageId pageId = PageId.of(SYSTEM_SPACE_ID, pageNo);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
            return true;
        } catch (MiniDbException e) {
            if (isMissingPageError(e)) {
                return false;
            }
            throw e;
        }
    }

    private boolean isMissingPageError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DiskIOException diskIOException) {
                int errorCode = diskIOException.getErrorCode();
                if (errorCode == DiskIOException.ERR_READ_EOF
                        || errorCode == DiskIOException.ERR_FILE_OPEN) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Catalog 快照（加载结果）
     */
    public record CatalogSnapshot(
            IdGenerator idGenerator,
            List<DatabaseDescriptor> databases,
            List<TableDescriptor> tables
    ) {}
}
