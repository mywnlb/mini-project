package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.catalog.ColumnMeta;
import cn.zhangyis.minidb.storage.catalog.ddl.DdlLogPage;
import cn.zhangyis.minidb.storage.catalog.persist.CatalogBootstrap;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import cn.zhangyis.minidb.storage.record.schema.FieldType;
import cn.zhangyis.minidb.storage.space.FspHeaderPage;
import cn.zhangyis.minidb.storage.space.TableSpace;
import cn.zhangyis.minidb.storage.transaction.core.TransactionSysPage;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DatabaseBootstrap 单元测试
 *
 * <p>验证 crash recovery 端到端启动流程的正确性：</p>
 * <ul>
 *   <li>首次启动：空 data dir 自动初始化 system space，随后初始化 catalog</li>
 *   <li>幂等性：重复 start() 不会重复执行</li>
 *   <li>system space 缺失但 data dir 非空时 fail-stop</li>
 *   <li>shutdown 后可重新 start</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DatabaseBootstrapTest {

    private static final int SYSTEM_SPACE_ID = 0;
    private static final String SYSTEM_SPACE_NAME = "system";
    private static final int BUFFER_POOL_SIZE = 1024;

    private Path testDir;
    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;

    @BeforeEach
    void setup() throws Exception {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        Files.createDirectories(tmpRoot);
        testDir = Files.createTempDirectory(tmpRoot, "minidb_bootstrap_test_");
        diskManager = new DiskManager(testDir);
        bufferPool = new BufferPool(BUFFER_POOL_SIZE, diskManager);
        catalogManager = new CatalogManager(bufferPool);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
        BaseStorageTest.deleteRecursively(testDir);
    }

    // ==================== 首次启动 ====================

    @Test
    @Order(1)
    @DisplayName("首次启动：跳过 redo recovery，初始化 catalog")
    void start_firstBoot_initsCatalogAndSkipsRedo() throws Exception {
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);
        // 不配置 RedoLogFileSet → 跳过 redo

        bootstrap.start();

        assertTrue(bootstrap.isStarted(), "应标记为已启动");
        assertNull(bootstrap.getRedoStats(), "首次启动不应有 redo stats");
        assertTrue(readNextSegmentId(PageId.of(SYSTEM_SPACE_ID, 0)) >= 1L,
                "首次启动后 FSP Header 必须处于已初始化状态，后续 phase 可继续推进 nextSegmentId");

        bootstrap.shutdown();
        assertFalse(bootstrap.isStarted(), "shutdown 后应标记为未启动");
    }

    // ==================== 幂等性 ====================

    @Test
    @Order(2)
    @DisplayName("重复调用 start() 不会重复执行")
    void start_isIdempotent() throws Exception {
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);

        bootstrap.start();
        assertTrue(bootstrap.isStarted());

        // 第二次调用应静默返回
        bootstrap.start();
        assertTrue(bootstrap.isStarted());

        bootstrap.shutdown();
    }

    // ==================== system space 已打开 ====================

    @Test
    @Order(3)
    @DisplayName("system space 已打开时跳过 open 步骤")
    void start_systemSpaceAlreadyOpen_skipsOpen() throws Exception {
        createAndInitializeSystemTablespace();
        assertTrue(diskManager.tablespaceExists(SYSTEM_SPACE_ID));

        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);
        bootstrap.start();

        assertTrue(bootstrap.isStarted());
        bootstrap.shutdown();
    }

    // ==================== system space 打开失败 ====================

    @Test
    @Order(4)
    @DisplayName("空 data dir 首次启动时自动初始化 system space")
    void start_initializesSystemSpaceWhenDataDirIsEmpty() throws Exception {
        Path emptyDir = createLocalTempDir("minidb_empty_");
        try {
            DiskManager emptyDiskManager = new DiskManager(emptyDir);
            BufferPool emptyBufferPool = new BufferPool(BUFFER_POOL_SIZE, emptyDiskManager);
            CatalogManager emptyCatalogManager = new CatalogManager(emptyBufferPool);

            DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                    emptyDiskManager, emptyBufferPool, emptyCatalogManager, SYSTEM_SPACE_NAME);

            try {
                assertDoesNotThrow(bootstrap::start);
                assertTrue(bootstrap.isStarted());
                assertTrue(Files.exists(emptyDir.resolve(SYSTEM_SPACE_NAME + ".ibd")));
                assertEquals(PageType.FIL_PAGE_DDL_LOG,
                        readPageType(emptyBufferPool, PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO)));
                assertNotNull(findTrxSysPageId(emptyBufferPool, emptyDiskManager));
                assertTrue(readNextSegmentId(emptyBufferPool, PageId.of(SYSTEM_SPACE_ID, 0)) >= 1L,
                        "system space 完成启动链后必须保持已初始化状态");
            } finally {
                if (bootstrap.isStarted()) {
                    bootstrap.shutdown();
                }
                emptyBufferPool.close();
                emptyDiskManager.close();
            }
        } finally {
            BaseStorageTest.deleteRecursively(emptyDir);
        }
    }

    @Test
    @Order(5)
    @DisplayName("system space 缺失但 data dir 非空时启动失败")
    void start_failsIfSystemSpaceMissingInNonEmptyDataDir() throws Exception {
        Path brokenDir = createLocalTempDir("minidb_missing_system_");
        try {
            DiskManager brokenDiskManager = new DiskManager(brokenDir);
            brokenDiskManager.createTablespace(1, "orphan");
            BufferPool brokenBufferPool = new BufferPool(BUFFER_POOL_SIZE, brokenDiskManager);
            CatalogManager brokenCatalogManager = new CatalogManager(brokenBufferPool);

            DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                    brokenDiskManager, brokenBufferPool, brokenCatalogManager, SYSTEM_SPACE_NAME);

            try {
                assertThrows(MiniDbException.class, bootstrap::start,
                        "非空 data dir 缺失 system space 时应 fail-stop");
                assertFalse(bootstrap.isStarted());
            } finally {
                brokenBufferPool.close();
                brokenDiskManager.close();
            }
        } finally {
            BaseStorageTest.deleteRecursively(brokenDir);
        }
    }

    // ==================== shutdown 后可重新 start ====================

    @Test
    @Order(6)
    @DisplayName("shutdown 后可重新 start")
    void start_afterShutdown_canRestartSuccessfully() throws Exception {
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);

        bootstrap.start();
        assertTrue(bootstrap.isStarted());

        bootstrap.shutdown();
        assertFalse(bootstrap.isStarted());

        // 重新启动
        bootstrap.start();
        assertTrue(bootstrap.isStarted());

        bootstrap.shutdown();
    }

    // ==================== shutdown 未启动时静默返回 ====================

    @Test
    @Order(7)
    @DisplayName("未启动时 shutdown 静默返回")
    void shutdown_notStarted_silentlyReturns() throws Exception {
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);

        // 不调用 start，直接 shutdown 不应抛异常
        assertDoesNotThrow(bootstrap::shutdown);
        assertFalse(bootstrap.isStarted());
    }

    @Test
    @Order(8)
    @DisplayName("启动后 page 5 保持 DDL log，TRX_SYS 使用独立页")
    void start_keepsDdlLogHeadSeparateFromTrxSysPage() throws Exception {
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);

        try {
            bootstrap.start();

            assertEquals(PageType.FIL_PAGE_DDL_LOG,
                    readPageType(PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO)));

            PageId trxSysPageId = findTrxSysPageId();
            assertNotNull(trxSysPageId, "TRX_SYS page should exist after startup");
            assertNotEquals(DdlLogPage.DDL_LOG_PAGE_NO, trxSysPageId.getPageNo());
            assertTrue(trxSysPageId.getPageNo() >= TransactionSysPage.DEFAULT_PAGE_NO);
        } finally {
            if (bootstrap.isStarted()) {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    @Order(9)
    @DisplayName("启动后 CREATE TABLE 可正常追加 DDL log")
    void start_allowsCreateTableAfterTransactionSubsystemInit() throws Exception {
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);

        try {
            bootstrap.start();
            catalogManager.createDatabase("sql_mode");

            assertDoesNotThrow(() -> catalogManager.createTable(
                    "sql_mode",
                    "tb_person",
                    List.of(
                            new ColumnMeta(1, "id", FieldType.bigint(false), 0, null),
                            new ColumnMeta(2, "age", FieldType.intType(true), 1, null)
                    )));

            assertEquals(PageType.FIL_PAGE_DDL_LOG,
                    readPageType(PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO)));
            assertNotNull(catalogManager.getTable("sql_mode", "tb_person"));
        } finally {
            if (bootstrap.isStarted()) {
                bootstrap.shutdown();
            }
        }
    }

    @Test
    @Order(10)
    @DisplayName("升级路径：legacy page 5 TRX_SYS 迁移后保留 nextTrxId")
    void start_migratesLegacyTrxSysPageAndPreservesNextTrxId() throws Exception {
        long legacyNextTrxId = 123L;
        seedLegacyCatalogWithTrxSysPage(legacyNextTrxId);

        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);

        try {
            bootstrap.start();

            assertEquals(PageType.FIL_PAGE_DDL_LOG,
                    readPageType(PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO)));

            PageId trxSysPageId = findTrxSysPageId();
            assertNotNull(trxSysPageId, "migrated TRX_SYS page should exist");
            assertNotEquals(DdlLogPage.DDL_LOG_PAGE_NO, trxSysPageId.getPageNo());
            assertEquals(legacyNextTrxId, readNextTrxId(trxSysPageId));
            assertEquals(legacyNextTrxId, bootstrap.getTransactionManager().getNextTrxId());
        } finally {
            if (bootstrap.isStarted()) {
                bootstrap.shutdown();
            }
        }
    }

    private void seedLegacyCatalogWithTrxSysPage(long nextTrxId) throws Exception {
        createAndInitializeSystemTablespace();
        CatalogBootstrap catalogBootstrap = new CatalogBootstrap(bufferPool);
        catalogBootstrap.initCatalog();

        PageId legacyPageId = PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(legacyPageId, BufferPool.FetchMode.READ_EXISTING);
            frame.writeLock();
            try {
                TransactionSysPage.init(frame.buffer(), SYSTEM_SPACE_ID);
                TransactionSysPage.setNextTrxId(frame.buffer(), nextTrxId);
                mtr.markDirty(frame.getPage());
            } finally {
                frame.writeUnlock();
            }
            mtr.commit();
        }
        bufferPool.flushPage(legacyPageId);
    }

    private void initializeSystemTablespace() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SYSTEM_SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);
            mtr.commit();
        }
    }

    private void createAndInitializeSystemTablespace() throws Exception {
        if (!diskManager.tablespaceExists(SYSTEM_SPACE_ID)) {
            diskManager.createTablespace(SYSTEM_SPACE_ID, SYSTEM_SPACE_NAME);
        }
        if (!isSystemTablespaceInitialized(bufferPool)) {
            initializeSystemTablespace();
        }
    }

    private PageType readPageType(PageId pageId) throws Exception {
        return readPageType(bufferPool, pageId);
    }

    private PageType readPageType(BufferPool targetBufferPool, PageId pageId) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(targetBufferPool)) {
            BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                return frame.getPage().getPageType();
            } finally {
                frame.readUnlock();
            }
        }
    }

    private boolean isSystemTablespaceInitialized(BufferPool targetBufferPool) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(targetBufferPool)) {
            FspHeaderPage fsp = FspHeaderPage.fromExistingPage(
                    mtr.getPage(PageId.of(SYSTEM_SPACE_ID, 0), BufferPool.FetchMode.READ_EXISTING));
            return fsp.getNextSegmentId() >= 1;
        } catch (Exception e) {
            return false;
        }
    }

    private PageId findTrxSysPageId() throws Exception {
        return findTrxSysPageId(bufferPool, diskManager);
    }

    private PageId findTrxSysPageId(BufferPool targetBufferPool, DiskManager targetDiskManager) throws Exception {
        int pageCount = targetDiskManager.getPageCount(SYSTEM_SPACE_ID);
        for (int pageNo = TransactionSysPage.DEFAULT_PAGE_NO; pageNo < pageCount; pageNo++) {
            PageId pageId = PageId.of(SYSTEM_SPACE_ID, pageNo);
            try (MiniTransaction mtr = new MiniTransaction(targetBufferPool)) {
                BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.readLock();
                try {
                    if (TransactionSysPage.isTrxSysPage(frame.buffer())) {
                        return pageId;
                    }
                } finally {
                    frame.readUnlock();
                }
            }
        }
        return null;
    }

    private long readNextTrxId(PageId pageId) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                return TransactionSysPage.getNextTrxId(frame.buffer());
            } finally {
                frame.readUnlock();
            }
        }
    }

    private long readNextSegmentId(PageId pageId) throws Exception {
        return readNextSegmentId(bufferPool, pageId);
    }

    private long readNextSegmentId(BufferPool targetBufferPool, PageId pageId) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(targetBufferPool)) {
            FspHeaderPage fsp = FspHeaderPage.fromExistingPage(
                    mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING));
            return fsp.getNextSegmentId();
        }
    }

    private Path createLocalTempDir(String prefix) throws IOException {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        Files.createDirectories(tmpRoot);
        return Files.createTempDirectory(tmpRoot, prefix);
    }
}
