package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;

/**
 * MiniDbServer 构建器。
 *
 * <p>设计模式：Builder 模式——将 MiniDbServer 的复杂构造过程分解为
 * 可读的链式调用，每个方法只设置一个属性，最终由 {@link #build()} 验证并创建实例。</p>
 *
 * <p>示例：
 * <pre>
 * MiniDbServer server = new MiniDbServerBuilder()
 *     .port(3307)
 *     .catalog(catalog)
 *     .dataSource(dataSource)
 *     .user("root", "123456")
 *     .build();
 * </pre></p>
 */
public class MiniDbServerBuilder {

    private int port = 3307; // 默认端口（避开 MySQL 的 3306）
    private CatalogSpi catalog;
    private DataSourceSpi dataSource;
    private TransactionManager txnManager;
    private UserManager userManager = UserManager.getInstance();
    private int sqlThreadPoolSize = Runtime.getRuntime().availableProcessors();

    public MiniDbServerBuilder port(int port) {
        this.port = port;
        return this;
    }

    public MiniDbServerBuilder catalog(CatalogSpi catalog) {
        this.catalog = catalog;
        return this;
    }

    public MiniDbServerBuilder dataSource(DataSourceSpi dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    public MiniDbServerBuilder transactionManager(TransactionManager txnManager) {
        this.txnManager = txnManager;
        return this;
    }

    public MiniDbServerBuilder userManager(UserManager userManager) {
        this.userManager = userManager;
        return this;
    }

    /** 添加用户（快捷方法） */
    public MiniDbServerBuilder user(String username, String password) {
        this.userManager.addUser(username, password);
        return this;
    }

    public MiniDbServerBuilder sqlThreadPoolSize(int size) {
        this.sqlThreadPoolSize = size;
        return this;
    }

    public MiniDbServer build() {
        if (catalog == null) {
            throw new IllegalStateException("CatalogSpi 不能为空");
        }
        if (dataSource == null) {
            throw new IllegalStateException("DataSourceSpi 不能为空");
        }
        return new MiniDbServer(port, catalog, dataSource, txnManager, userManager, sqlThreadPoolSize);
    }
}
