package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import cn.zhangyis.minidb.storage.DatabaseBootstrap;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mini-db 服务器启动入口。
 *
 * <p>从 {@code minidb.yml} 加载配置，整合存储引擎（{@link DatabaseBootstrap}）
 * 和 MySQL 协议服务器（{@link MiniDbServer}），提供完整的数据库服务。</p>
 *
 * <p>启动流程：
 * <ol>
 *   <li>加载配置（{@link ServerConfig}）</li>
 *   <li>初始化存储引擎组件（DiskManager、BufferPool、CatalogManager）</li>
 *   <li>执行 crash recovery（DatabaseBootstrap.start()）</li>
 *   <li>启动 MySQL 协议服务器</li>
 * </ol></p>
 */
public class MiniDbServerMain {

    private static final Logger log = LoggerFactory.getLogger(MiniDbServerMain.class);

    public static void main(String[] args) {
        // 加载配置
        ServerConfig config = ServerConfig.load();
        log.info("mini-db 启动中... {}", config);

        try {
            // 初始化存储引擎
            DiskManager diskManager = new DiskManager(config.getDataDir());
            BufferPool bufferPool = new BufferPool(config.getBufferPoolPages(), diskManager);
            CatalogManager catalogManager = new CatalogManager(bufferPool);

            // Crash recovery
            DatabaseBootstrap bootstrap = new DatabaseBootstrap(
                    diskManager, bufferPool, catalogManager, config.getSystemTablespace());
            bootstrap.configureDefaultDatabase(config.getDefaultDatabaseName());
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
            StorageCatalog catalog = new StorageCatalog(
                    catalogManager, config.getDefaultDatabaseName(), bufferPool);
            MiniDbServer server = new MiniDbServerBuilder()
                    .port(config.getPort())
                    .catalog(catalog)
                    .dataSource(null)  // 由 MysqlConnectionHandler 动态创建 StorageDataSource
                    .transactionManager(bootstrap.getTransactionManager())
                    .user(config.getUser(), config.getPassword())
                    .defaultDatabase(config.getDefaultDatabaseName())
                    .build();

            server.start(); // 阻塞直到服务器关闭
        } catch (Exception e) {
            log.error("mini-db 启动失败", e);
            System.exit(1);
        }
    }
}
