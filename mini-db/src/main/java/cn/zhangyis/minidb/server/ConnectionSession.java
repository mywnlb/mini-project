package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.handler.ServerPreparedStatement;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.exec.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每连接会话状态。
 *
 * <p>每个 TCP 连接拥有独立的 ConnectionSession，持有该连接的全部状态：
 * 当前数据库、SQL 会话、事务上下文、预编译语句缓存等。</p>
 *
 * <p>设计模式：会话模式（Session Pattern）——将分散的连接状态聚合为
 * 一个生命周期与 TCP 连接一致的对象，简化资源管理和清理。</p>
 *
 * <p>核心不变式：每个 ConnectionSession 持有独立的 ExecutionContext，
 * 确保事务隔离性——连接 A 的 BEGIN 不影响连接 B。</p>
 */
public class ConnectionSession {

    private final int connectionId;
    private final CatalogSpi catalog;
    private final DataSourceSpi dataSource;
    private final ExecutionContext executionContext;
    private final SqlSession sqlSession;
    private final PlanCache planCache;

    /** 预编译语句缓存：statementId → ServerPreparedStatement */
    private final ConcurrentMap<Integer, ServerPreparedStatement> preparedStatements = new ConcurrentHashMap<>();

    /** 预编译语句 ID 生成器 */
    private final AtomicInteger stmtIdGenerator = new AtomicInteger(1);

    private volatile String currentDatabase;
    private volatile String username;
    private volatile int clientCapabilities;

    public ConnectionSession(int connectionId, CatalogSpi catalog, DataSourceSpi dataSource,
                             ExecutionContext executionContext) {
        this.connectionId = connectionId;
        this.catalog = catalog;
        this.dataSource = dataSource;
        this.executionContext = executionContext;
        this.planCache = new PlanCache(128);
        this.sqlSession = new SqlSession(catalog, executionContext, dataSource);
    }

    /**
     * 分配新的预编译语句 ID。
     */
    public int nextStatementId() {
        return stmtIdGenerator.getAndIncrement();
    }

    /**
     * 注册预编译语句。
     */
    public void registerPreparedStatement(int stmtId, ServerPreparedStatement ps) {
        preparedStatements.put(stmtId, ps);
    }

    /**
     * 获取预编译语句。
     */
    public ServerPreparedStatement getPreparedStatement(int stmtId) {
        return preparedStatements.get(stmtId);
    }

    /**
     * 移除预编译语句。
     */
    public ServerPreparedStatement removePreparedStatement(int stmtId) {
        return preparedStatements.remove(stmtId);
    }

    /**
     * 关闭会话，释放所有资源。
     * 若有活跃事务则回滚，清理所有预编译语句。
     */
    public void close() {
        // 回滚活跃事务
        if (executionContext.inTransaction()) {
            try {
                executionContext.rollback();
            } catch (Exception ignored) {
                // 关闭时忽略回滚异常
            }
        }
        // 清理预编译语句
        preparedStatements.clear();
    }

    // ==================== Getters / Setters ====================

    public int connectionId() { return connectionId; }
    public CatalogSpi catalog() { return catalog; }
    public DataSourceSpi dataSource() { return dataSource; }
    public ExecutionContext executionContext() { return executionContext; }
    public SqlSession sqlSession() { return sqlSession; }
    public PlanCache planCache() { return planCache; }

    public String currentDatabase() { return currentDatabase; }
    public void setCurrentDatabase(String database) { this.currentDatabase = database; }

    public String username() { return username; }
    public void setUsername(String username) { this.username = username; }

    public int clientCapabilities() { return clientCapabilities; }
    public void setClientCapabilities(int clientCapabilities) { this.clientCapabilities = clientCapabilities; }
}
