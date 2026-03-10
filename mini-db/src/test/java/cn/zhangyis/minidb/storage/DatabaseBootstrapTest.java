package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DatabaseBootstrap 单元测试
 *
 * <p>验证 crash recovery 端到端启动流程的正确性：</p>
 * <ul>
 *   <li>首次启动：跳过 redo，初始化 catalog</li>
 *   <li>幂等性：重复 start() 不会重复执行</li>
 *   <li>system space 打开失败时 fail-stop</li>
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
        testDir = Files.createTempDirectory("minidb_bootstrap_test_");
        diskManager = new DiskManager(testDir);
        // 预先创建系统表空间文件，模拟已有数据库
        diskManager.createTablespace(SYSTEM_SPACE_ID, SYSTEM_SPACE_NAME);
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
        if (testDir != null) {
            Files.walk(testDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        }
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
        // system space 在 setup 中已经通过 createTablespace 打开
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
    @DisplayName("system space 文件不存在时启动失败")
    void start_failsIfSystemSpaceCannotOpen() throws Exception {
        // 创建一个新的 DiskManager，不创建 system space 文件
        Path emptyDir = Files.createTempDirectory("minidb_empty_");
        try {
            DiskManager emptyDiskManager = new DiskManager(emptyDir);
            BufferPool emptyBufferPool = new BufferPool(BUFFER_POOL_SIZE, emptyDiskManager);
            CatalogManager emptyCatalogManager = new CatalogManager(emptyBufferPool);

            DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                    emptyDiskManager, emptyBufferPool, emptyCatalogManager, SYSTEM_SPACE_NAME);

            // system space 文件不存在，openTablespace 应抛异常
            assertThrows(MiniDbException.class, bootstrap::start,
                    "system space 不存在时应抛出异常");
            assertFalse(bootstrap.isStarted());

            emptyBufferPool.close();
            emptyDiskManager.close();
        } finally {
            Files.walk(emptyDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                    });
        }
    }

    // ==================== shutdown 后可重新 start ====================

    @Test
    @Order(5)
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
    @Order(6)
    @DisplayName("未启动时 shutdown 静默返回")
    void shutdown_notStarted_silentlyReturns() throws Exception {
        DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);

        // 不调用 start，直接 shutdown 不应抛异常
        assertDoesNotThrow(bootstrap::shutdown);
        assertFalse(bootstrap.isStarted());
    }
}
