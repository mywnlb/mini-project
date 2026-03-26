package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import cn.zhangyis.minidb.storage.space.FspHeaderPage;
import cn.zhangyis.minidb.storage.space.TableSpace;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 存储层测试基类
 *
 * <p>提供统一的测试环境初始化和清理逻辑，包括：</p>
 * <ul>
 *   <li>临时测试目录创建</li>
 *   <li>DiskManager 初始化</li>
 *   <li>表空间创建（关键步骤）</li>
 *   <li>BufferPool 初始化</li>
 *   <li>测试后资源清理</li>
 * </ul>
 *
 * <h2>使用方法</h2>
 * <pre>
 * class MyStorageTest extends BaseStorageTest {
 *     &#64;Test
 *     void testSomething() throws Exception {
 *         // 可以直接使用 diskManager, bufferPool, SPACE_ID 等
 *         Page page = bufferPool.newPage(SPACE_ID).getPage();
 *         // ...
 *     }
 *
 *     &#64;Override
 *     protected void afterSetup() throws Exception {
 *         // 可选：子类额外的初始化逻辑
 *     }
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public abstract class BaseStorageTest {

    protected static final int SYSTEM_SPACE_ID = 0;
    protected static final String SYSTEM_SPACE_NAME = "system";

    /**
     * 默认测试表空间ID
     */
    protected static final int SPACE_ID = 1;

    /**
     * 默认表空间名称
     */
    protected static final String SPACE_NAME = "test_space";

    protected static final int UNDO_SPACE_ID = 2;
    protected static final String UNDO_SPACE_NAME = "undo_space";

    /**
     * 默认BufferPool大小（1024页 = 16MB）
     *
     * <p>MySQL InnoDB 默认 128MB，生产环境通常是系统内存的 70-80%。
     * 测试环境 16MB 足够大多数集成测试使用。</p>
     */
    protected static final int BUFFER_POOL_SIZE = 4096;

    /**
     * 临时测试目录
     */
    protected Path testDir;

    /**
     * 磁盘管理器
     */
    protected DiskManager diskManager;

    /**
     * 缓冲池
     */
    protected BufferPool bufferPool;

    /**
     * Undo 管理器（按需初始化）
     */
    protected UndoLogManager undoLogManager;

    /**
     * 事务管理器（按需初始化）
     */
    protected TransactionManager transactionManager;

    /**
     * 测试前初始化
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>创建临时测试目录</li>
     *   <li>初始化 DiskManager</li>
     *   <li>创建表空间（关键！）</li>
     *   <li>初始化 BufferPool</li>
     *   <li>调用子类的 afterSetup() 钩子方法</li>
     * </ol>
     *
     * @throws Exception 如果初始化失败
     */
    @BeforeEach
    void setup() throws Exception {
        // 1. 在受控工作目录下创建临时测试目录，避免依赖系统默认 temp 路径
        testDir = createTempTestDir("minidb_test_");

        // 2. 初始化 DiskManager
        diskManager = new DiskManager(testDir);

        // 3. 初始化 BufferPool
        bufferPool = new BufferPool(BUFFER_POOL_SIZE, diskManager);

        // 4. 提供一个默认的已初始化用户表空间，保证测试基类本身安全
        initUserSpace();

        // 5. 调用子类的额外初始化（钩子方法）
        afterSetup();
    }

    /**
     * 测试后清理资源
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>调用子类的 beforeCleanup() 钩子方法</li>
     *   <li>关闭 BufferPool</li>
     *   <li>关闭 DiskManager</li>
     *   <li>删除临时测试目录及所有文件</li>
     * </ol>
     *
     * @throws Exception 如果清理失败
     */
    @AfterEach
    void cleanup() throws Exception {
        // 1. 调用子类的清理前钩子
        beforeCleanup();

        // 2. 关闭事务子系统
        if (transactionManager != null) {
            transactionManager.close();
            transactionManager = null;
        }
        if (undoLogManager != null) {
            undoLogManager.close();
            undoLogManager = null;
        }

        // 3. 关闭 BufferPool
        if (bufferPool != null) {
            bufferPool.close();
        }

        // 4. 关闭 DiskManager
        if (diskManager != null) {
            diskManager.close();
        }

        // 5. 清理测试文件
        deleteRecursively(testDir);
    }

    /**
     * 钩子方法：setup完成后调用
     *
     * <p>子类可以覆盖此方法进行额外的初始化，例如：</p>
     * <ul>
     *   <li>创建额外的表空间</li>
     *   <li>初始化测试数据</li>
     *   <li>创建MiniTransaction</li>
     * </ul>
     *
     * @throws Exception 如果初始化失败
     */
    protected void afterSetup() throws Exception {
        // 默认为空，子类可以覆盖
    }

    /**
     * 钩子方法：cleanup开始前调用
     *
     * <p>子类可以覆盖此方法进行额外的清理，例如：</p>
     * <ul>
     *   <li>提交或回滚事务</li>
     *   <li>关闭额外的资源</li>
     *   <li>验证最终状态</li>
     * </ul>
     *
     * @throws Exception 如果清理失败
     */
    protected void beforeCleanup() throws Exception {
        // 默认为空，子类可以覆盖
    }

    protected void initSystemSpace() throws Exception {
        initializeTablespace(SYSTEM_SPACE_ID, SYSTEM_SPACE_NAME, false);
    }

    protected void initUserSpace() throws Exception {
        initializeTablespace(SPACE_ID, SPACE_NAME, false);
    }

    protected void initUndoSpace() throws Exception {
        initializeTablespace(UNDO_SPACE_ID, UNDO_SPACE_NAME, true);
    }

    protected void initSystemCatalog() throws Exception {
        initSystemSpace();
        CatalogManager catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
        catalogManager.close();
    }

    protected TransactionManager bootTransactionSubsystem() throws Exception {
        initSystemCatalog();
        undoLogManager = new UndoLogManager(bufferPool, SYSTEM_SPACE_ID);
        transactionManager = new TransactionManager(bufferPool, undoLogManager);
        transactionManager.initialize();
        return transactionManager;
    }

    protected TransactionManager bootInMemoryTransactionSubsystem(int undoSpaceId, int rollbackSegments)
            throws Exception {
        if (undoLogManager != null) {
            undoLogManager.close();
        }
        undoLogManager = new UndoLogManager(bufferPool, undoSpaceId, rollbackSegments);
        transactionManager = new TransactionManager(bufferPool, undoLogManager);
        transactionManager.initializeInMemory();
        return transactionManager;
    }

    protected boolean isTablespaceInitialized(int spaceId) throws Exception {
        if (!diskManager.tablespaceExists(spaceId)) {
            return false;
        }
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = FspHeaderPage.fromExistingPage(
                    mtr.getPage(PageId.of(spaceId, 0), BufferPool.FetchMode.READ_EXISTING));
            return fsp.getNextSegmentId() >= 1;
        } catch (Exception e) {
            return false;
        }
    }

    protected PageType readPageType(PageId pageId) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            return mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING).getPageType();
        }
    }

    protected FspHeaderPage readFspHeader(int spaceId) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            return FspHeaderPage.fromExistingPage(
                    mtr.getPage(PageId.of(spaceId, 0), BufferPool.FetchMode.READ_EXISTING));
        }
    }

    private void initializeTablespace(int spaceId, String name, boolean undoTablespace) throws Exception {
        if (!diskManager.tablespaceExists(spaceId)) {
            diskManager.createTablespace(spaceId, name);
        }
        if (isTablespaceInitialized(spaceId)) {
            return;
        }
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            if (undoTablespace) {
                UndoLogManager.initializeUndoTablespace(mtr, bufferPool, spaceId);
            } else {
                TableSpace tableSpace = new TableSpace(spaceId, bufferPool);
                tableSpace.initializeTablespace(mtr);
            }
            mtr.commit();
        }
        // 夹具中的新表空间必须以“已持久化的物理结构”暴露给测试，避免初始化脏页污染后续断言。
        bufferPool.flushAllPages();
    }

    private Path createTempTestDir(String prefix) throws IOException {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        Files.createDirectories(tmpRoot);
        return Files.createTempDirectory(tmpRoot, prefix);
    }

    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    /**
     * 获取 BufferPool 统计信息（便捷方法）
     *
     * @return BufferPool 统计信息
     */
    protected BufferPool.BufferPoolStats getBufferPoolStats() {
        return bufferPool.getStats();
    }

    /**
     * 打印 BufferPool 统计信息（便捷方法）
     */
    protected void printBufferPoolStats() {
        System.out.println("BufferPool Stats: " + getBufferPoolStats());
    }
}
