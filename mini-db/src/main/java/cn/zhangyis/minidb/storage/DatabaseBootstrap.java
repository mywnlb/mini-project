package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.space.FspHeaderPage;
import cn.zhangyis.minidb.storage.redo.recovery.RecoveryCoordinator;
import cn.zhangyis.minidb.storage.redo.recovery.RecoveryException;
import cn.zhangyis.minidb.storage.space.TableSpace;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.recovery.UndoRecoveryManager;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据库启动引导器 —— Crash Recovery 端到端入口。
 *
 * <p>将 open system space → redo recovery → catalog load（含 DDL log replay）
 * → transaction subsystem → ready 五个阶段按正确顺序串联。</p>
 *
 * <h3>启动顺序不变量</h3>
 * <ol>
 *   <li>打开系统表空间（system space, spaceId=0）</li>
 *   <li>Redo recovery：从 checkpoint 重放 redo log，将页面恢复到 crash-consistent 状态</li>
 *   <li>Catalog bootstrap：加载 catalog snapshot + DDL log replay + undo recovery（可选）</li>
 *   <li>Transaction subsystem：初始化 UndoLogManager + TransactionManager</li>
 *   <li>标记 ready</li>
 * </ol>
 *
 * <p>Redo recovery 必须在 catalog load 之前完成，否则读到的 catalog 页面可能是过期数据。
 * DDL log replay 在 {@link CatalogManager#loadCatalog()} 内部自动执行，
 * 依赖 durable catalog snapshot 判定，不需要外部单独调用。</p>
 *
 * <h3>首次启动</h3>
 * <p>如果数据目录中尚无任何 .ibd 文件，Phase 1 会先创建并初始化 system space；
 * 如果系统表空间文件缺失但目录内已存在其他表空间，则按损坏实例 fail-stop。
 * 没有配置 {@link RedoLogFileSet} 或没有有效 checkpoint 时，跳过 redo/undo recovery。</p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * DatabaseBootstrap bootstrap = new DatabaseBootstrap(diskManager, bufferPool, catalogManager, "system");
 * bootstrap.configureRedoRecovery(redoLogFileSet);
 * bootstrap.start();
 * // ... 正常服务 ...
 * bootstrap.shutdown();
 * </pre>
 */
public class DatabaseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

    private static final int SYSTEM_SPACE_ID = 0;

    // ==================== 必选依赖 ====================

    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final CatalogManager catalogManager;
    private final String systemSpaceName;

    // ==================== 可选：默认数据库 ====================

    private String defaultDatabaseName;

    // ==================== 可选：Redo recovery ====================

    private RedoLogFileSet redoLogFileSet;

    // ==================== 可选：Undo recovery ====================

    private int undoSpaceId = -1;
    private int undoPageStart;
    private int undoPageEnd;
    private UndoRecoveryManager.UndoRecordApplier undoRecordApplier;

    // ==================== 启动后可用的组件 ====================

    private TransactionManager transactionManager;

    // ==================== 状态 ====================

    private volatile boolean started;

    /** Redo recovery 统计（仅在执行了 redo recovery 后非 null） */
    private RecoveryCoordinator.RecoveryStats redoStats;

    // ==================== 构造函数 ====================

    /**
     * 创建 DatabaseBootstrap
     *
     * @param diskManager      磁盘管理器
     * @param bufferPool       缓冲池
     * @param catalogManager   Catalog 管理器
     * @param systemSpaceName  系统表空间名称（不含 .ibd 后缀）
     */
    public DatabaseBootstrap(DiskManager diskManager, BufferPool bufferPool,
                             CatalogManager catalogManager, String systemSpaceName) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.catalogManager = catalogManager;
        this.systemSpaceName = systemSpaceName;
    }

    // ==================== 可选配置 ====================

    /**
     * 配置默认数据库。
     *
     * <p>如果配置了默认数据库名，启动时在 catalog bootstrap 之后自动创建（如果不存在）。</p>
     *
     * @param name 默认数据库名称
     * @return this
     */
    public DatabaseBootstrap configureDefaultDatabase(String name) {
        this.defaultDatabaseName = name;
        return this;
    }

    /**
     * 配置 Redo recovery。
     *
     * <p>如果不调用此方法，启动时跳过 redo recovery 阶段。</p>
     *
     * @param fileSet Redo Log 文件集
     * @return this
     */
    public DatabaseBootstrap configureRedoRecovery(RedoLogFileSet fileSet) {
        this.redoLogFileSet = fileSet;
        return this;
    }

    /**
     * 配置 Undo recovery 参数。
     *
     * <p>Undo recovery 在 redo recovery 内部执行（{@link RecoveryCoordinator} 负责协调）。
     * 如果不调用此方法，跳过 undo 回滚阶段。</p>
     *
     * @param undoSpaceId       Undo 表空间 ID
     * @param undoPageStart     Undo Page 起始页号
     * @param undoPageEnd       Undo Page 结束页号（不包含）
     * @param undoRecordApplier Undo 记录应用器
     * @return this
     */
    public DatabaseBootstrap configureUndoRecovery(int undoSpaceId, int undoPageStart,
                                                    int undoPageEnd,
                                                    UndoRecoveryManager.UndoRecordApplier undoRecordApplier) {
        this.undoSpaceId = undoSpaceId;
        this.undoPageStart = undoPageStart;
        this.undoPageEnd = undoPageEnd;
        this.undoRecordApplier = undoRecordApplier;
        return this;
    }

    // ==================== 核心入口 ====================

    /**
     * 启动数据库。
     *
     * <p>按照 open system space → redo recovery → catalog bootstrap 的顺序执行。
     * 任何阶段失败都会抛出异常，中止启动（fail-stop 语义）。</p>
     *
     * @throws MiniDbException 如果启动失败
     */
    public void start() throws MiniDbException {
        if (started) {
            log.warn("DatabaseBootstrap.start() called but already started, skipping");
            return;
        }

        long t0 = System.currentTimeMillis();
        log.info("=== Database startup begin ===");

        // Phase 1: 打开系统表空间
        ensureSystemSpaceOpen();

        // Phase 2: Redo recovery（含可选的 Undo recovery）
        performRedoRecovery();

        // Phase 3: Catalog bootstrap（含 DDL log replay）
        bootstrapCatalog();

        // Phase 3.5: 确保默认数据库存在
        ensureDefaultDatabase();

        // Phase 4: 事务子系统
        initTransactionSubsystem();

        started = true;
        long elapsed = System.currentTimeMillis() - t0;
        log.info("=== Database startup completed in {} ms ===", elapsed);
    }

    /**
     * 关闭数据库。
     *
     * @throws MiniDbException 如果关闭失败
     */
    public void shutdown() throws MiniDbException {
        if (!started) {
            return;
        }
        log.info("=== Database shutdown begin ===");
        catalogManager.close();
        started = false;
        log.info("=== Database shutdown completed ===");
    }

    // ==================== 内部阶段 ====================

    /**
     * Phase 1: 确保系统表空间已就绪。
     *
     * <p>空 data dir 视为首次启动，创建并初始化 system space；
     * 如果目录中已有其他 .ibd 但缺失 system space，则 fail-stop。</p>
     */
    private void ensureSystemSpaceOpen() throws MiniDbException {
        if (diskManager.tablespaceExists(SYSTEM_SPACE_ID)) {
            if (isSystemTablespaceInitialized()) {
                log.debug("System space already open");
                return;
            }

            log.warn("System tablespace already open but FSP Header is uninitialized, recreating...");
            recreateSystemTablespace();
            return;
        }

        if (!diskManager.tablespaceFileExists(systemSpaceName)) {
            if (diskManager.hasAnyTablespaceFiles()) {
                throw new MiniDbException("System tablespace file missing in non-empty data directory: "
                        + systemSpaceName + ".ibd");
            }

            log.info("System tablespace file missing in empty data directory; creating new system space: {}",
                    systemSpaceName);
            createAndInitializeSystemTablespace();
            return;
        }

        // 尝试打开已有文件
        log.info("Opening system tablespace: {}", systemSpaceName);
        diskManager.openTablespace(SYSTEM_SPACE_ID, systemSpaceName);

        // 文件存在时检查 FSP Header 是否已正确初始化
        if (isSystemTablespaceInitialized()) {
            log.info("System tablespace opened successfully");
            return;
        }

        // 文件存在但未初始化（上次启动失败残留），删除后重建
        log.warn("System tablespace corrupted (uninitialized FSP Header), recreating...");
        recreateSystemTablespace();
    }

    private boolean isSystemTablespaceInitialized() {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SYSTEM_SPACE_ID, 0)));
            return fsp.getNextSegmentId() >= 1;
        } catch (MiniDbException e) {
            log.debug("System tablespace FSP Header is not readable yet", e);
            return false;
        }
    }

    private void recreateSystemTablespace() throws MiniDbException {
        clearSystemSpacePagesFromBufferPool();
        if (diskManager.tablespaceExists(SYSTEM_SPACE_ID)) {
            diskManager.dropTablespace(SYSTEM_SPACE_ID, systemSpaceName);
        }
        createAndInitializeSystemTablespace();
    }

    private void clearSystemSpacePagesFromBufferPool() {
        int pageCount = diskManager.getPageCount(SYSTEM_SPACE_ID);
        for (int pageNo = 0; pageNo < pageCount; pageNo++) {
            bufferPool.deletePage(PageId.of(SYSTEM_SPACE_ID, pageNo));
        }
    }

    private void createAndInitializeSystemTablespace() throws MiniDbException {
        log.info("Creating system tablespace: {}", systemSpaceName);
        diskManager.createTablespace(SYSTEM_SPACE_ID, systemSpaceName);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SYSTEM_SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);
            mtr.commit();
        }
        log.info("System tablespace initialized: {}", systemSpaceName);
    }

    /**
     * Phase 2: 执行 Redo recovery。
     *
     * <p>如果未配置 {@link RedoLogFileSet} 或没有有效 checkpoint，跳过。
     * Undo recovery 由 {@link RecoveryCoordinator} 内部协调。</p>
     */
    private void performRedoRecovery() throws MiniDbException {
        if (redoLogFileSet == null) {
            log.info("Redo recovery skipped: no RedoLogFileSet configured");
            return;
        }

        RecoveryCoordinator coordinator = new RecoveryCoordinator(redoLogFileSet, bufferPool);

        if (undoSpaceId >= 0 && undoRecordApplier != null) {
            coordinator.configureUndoRecovery(undoSpaceId, undoPageStart, undoPageEnd, undoRecordApplier);
        }

        if (!coordinator.needRecovery()) {
            log.info("Redo recovery skipped: no valid checkpoint found");
            return;
        }

        log.info("Starting redo recovery...");
        try {
            coordinator.recover();
        } catch (RecoveryException e) {
            throw new MiniDbException("Redo recovery failed, cannot start database", e);
        }

        redoStats = coordinator.getStats();
        log.info("Redo recovery completed: {}", redoStats);
    }

    /**
     * Phase 3: Catalog bootstrap。
     *
     * <p>内部调用 {@link CatalogManager#bootstrap()}，该方法会：
     * <ol>
     *   <li>首次启动时初始化 page 3/4/5</li>
     *   <li>加载 catalog snapshot</li>
     *   <li>执行 DDL log replay</li>
     *   <li>重建内存 cache</li>
     * </ol>
     * </p>
     */
    private void bootstrapCatalog() throws MiniDbException {
        log.info("Bootstrapping catalog...");
        catalogManager.bootstrap();
        log.info("Catalog bootstrap completed");
    }

    /**
     * Phase 3.5: 确保默认数据库存在。
     *
     * <p>如果配置了 {@link #defaultDatabaseName}，在 catalog bootstrap 之后检查并自动创建。
     * 已存在则跳过，保证幂等。</p>
     */
    private void ensureDefaultDatabase() throws MiniDbException {
        if (defaultDatabaseName == null || defaultDatabaseName.isEmpty()) {
            return;
        }
        if (catalogManager.hasDatabase(defaultDatabaseName)) {
            log.debug("Default database '{}' already exists", defaultDatabaseName);
            return;
        }
        log.info("Creating default database: {}", defaultDatabaseName);
        catalogManager.createDatabase(defaultDatabaseName);
        log.info("Default database '{}' created", defaultDatabaseName);
    }

    /**
     * Phase 4: 初始化事务子系统。
     *
     * <p>创建 UndoLogManager 和 TransactionManager，
     * 必须在 catalog bootstrap 之后执行（依赖系统表空间已就绪）。</p>
     */
    private void initTransactionSubsystem() throws MiniDbException {
        log.info("Initializing transaction subsystem...");
        UndoLogManager undoLogManager = new UndoLogManager(bufferPool, SYSTEM_SPACE_ID);
        this.transactionManager = new TransactionManager(bufferPool, undoLogManager);
        this.transactionManager.initialize();
        log.info("Transaction subsystem initialized");
    }

    // ==================== 状态查询 ====================

    /**
     * 数据库是否已启动
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * 获取事务管理器（启动完成后可用）
     */
    public TransactionManager getTransactionManager() {
        if (!started) {
            throw new IllegalStateException("Database not started yet");
        }
        return transactionManager;
    }

    /**
     * 获取 Redo recovery 统计（仅在执行了 redo recovery 后非 null）
     */
    public RecoveryCoordinator.RecoveryStats getRedoStats() {
        return redoStats;
    }
}
