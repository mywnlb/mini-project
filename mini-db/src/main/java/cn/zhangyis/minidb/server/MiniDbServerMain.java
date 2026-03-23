package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import cn.zhangyis.minidb.sql.exec.MockDataSourceAdapter;
import cn.zhangyis.minidb.storage.DatabaseBootstrap;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mini-db 服务器启动入口。
 *
 * <p>整合存储引擎（{@link DatabaseBootstrap}）和 MySQL 协议服务器（{@link MiniDbServer}），
 * 提供完整的数据库服务。</p>
 *
 * <p>启动流程：
 * <ol>
 *   <li>初始化存储引擎组件（DiskManager、BufferPool、CatalogManager）</li>
 *   <li>执行 crash recovery（DatabaseBootstrap.start()）</li>
 *   <li>启动 MySQL 协议服务器</li>
 * </ol></p>
 *
 * <p>用法：{@code java cn.zhangyis.minidb.server.MiniDbServerMain [port] [dataDir]}</p>
 */
public class MiniDbServerMain {

    private static final Logger log = LoggerFactory.getLogger(MiniDbServerMain.class);

    private static final int DEFAULT_PORT = 3307;
    private static final String DEFAULT_DATA_DIR = "data";
    private static final int DEFAULT_BUFFER_POOL_PAGES = 1024;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String dataDir = DEFAULT_DATA_DIR;

        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            dataDir = args[1];
        }

        log.info("mini-db 启动中... port={}, dataDir={}", port, dataDir);

        try {
            // 初始化存储引擎
            DiskManager diskManager = new DiskManager(dataDir);
            BufferPool bufferPool = new BufferPool(DEFAULT_BUFFER_POOL_PAGES, diskManager);
            CatalogManager catalogManager = new CatalogManager(bufferPool);

            // Crash recovery
            DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                    diskManager, bufferPool, catalogManager, "system");
            bootstrap.start();

            // 注册 shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("收到关闭信号，正在停止...");
                try {
                    bootstrap.shutdown();
                } catch (Exception e) {
                    log.error("关闭失败", e);
                }
            }));

            // 启动 MySQL 协议服务器
            // 注意：当前使用 MockDataSourceAdapter 作为占位。
            // 真实存储模式下 StorageDataSource 需要每连接的 ExecutionContext，
            // 后续需要在 MysqlConnectionHandler 中集成 StorageDataSource 的创建。
            StorageCatalog catalog = new StorageCatalog(catalogManager, "default", bufferPool);
            MiniDbServer server = new MiniDbServerBuilder()
                    .port(port)
                    .catalog(catalog)
                    .dataSource(MockDataSourceAdapter.INSTANCE)
                    .user("root", "")
                    .build();

            server.start(); // 阻塞直到服务器关闭
        } catch (Exception e) {
            log.error("mini-db 启动失败", e);
            System.exit(1);
        }
    }
}
