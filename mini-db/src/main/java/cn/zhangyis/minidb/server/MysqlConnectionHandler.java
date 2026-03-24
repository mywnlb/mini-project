package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.server.handler.CommandDispatcher;
import cn.zhangyis.minidb.server.handler.ErrorMapping;
import cn.zhangyis.minidb.server.handler.StatusFlagBuilder;
import cn.zhangyis.minidb.server.netty.PacketWriter;
import cn.zhangyis.minidb.server.netty.RawMysqlPacket;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.packets.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.sql.exec.ExecutionContext;
import cn.zhangyis.minidb.sql.exec.MockDataSourceAdapter;
import cn.zhangyis.minidb.sql.exec.StorageDataSource;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MySQL 连接处理器——每连接一个实例。
 *
 * <p>设计模式：状态机（State Machine）——连接生命周期分为三个状态：
 * <ul>
 *   <li>HANDSHAKE：等待客户端认证响应</li>
 *   <li>AUTH：验证认证数据</li>
 *   <li>COMMAND：正常命令处理阶段</li>
 * </ul></p>
 *
 * <p>核心不变式：
 * <ul>
 *   <li>SQL 执行通过 {@code sqlExecutor} 线程池异步执行，不阻塞 Netty EventLoop</li>
 *   <li>每个连接有独立的 ConnectionSession，包含独立的事务上下文</li>
 * </ul></p>
 */
public class MysqlConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MysqlConnectionHandler.class);

    /** 全局连接 ID 计数器 */
    private static final AtomicInteger CONNECTION_ID_GEN = new AtomicInteger(1);

    private enum State { HANDSHAKE, COMMAND }

    private final CatalogSpi catalog;
    private final DataSourceSpi dataSource;
    private final TransactionManager txnManager;
    private final UserManager userManager;
    private final ExecutorService sqlExecutor;
    private final String defaultDatabase;

    private State state = State.HANDSHAKE;
    private byte[] authChallenge;
    private int connectionId;
    private ConnectionSession session;
    private PacketWriter writer;
    private CommandDispatcher dispatcher;

    public MysqlConnectionHandler(CatalogSpi catalog, DataSourceSpi dataSource,
                                   TransactionManager txnManager, UserManager userManager,
                                   ExecutorService sqlExecutor, String defaultDatabase) {
        this.catalog = catalog;
        this.dataSource = dataSource;
        this.txnManager = txnManager;
        this.userManager = userManager;
        this.sqlExecutor = sqlExecutor;
        this.defaultDatabase = defaultDatabase;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.connectionId = CONNECTION_ID_GEN.getAndIncrement();
        this.writer = new PacketWriter(ctx);

        // 生成 20 字节随机认证挑战
        this.authChallenge = new byte[20];
        new SecureRandom().nextBytes(authChallenge);

        log.info("新连接: connectionId={}, remote={}", connectionId, ctx.channel().remoteAddress());

        // 发送握手包（sequence id = 0）
        HandshakePacket handshake = new HandshakePacket(
                connectionId, authChallenge, MysqlConstants.SERVER_DEFAULT_CAPABILITIES);
        writer.writeHandshake(handshake);
        writer.flush();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        RawMysqlPacket packet = (RawMysqlPacket) msg;
        ByteBuf payload = packet.payload();

        try {
            switch (state) {
                case HANDSHAKE -> handleAuth(ctx, packet);
                case COMMAND -> handleCommand(ctx, packet);
            }
        } finally {
            payload.release();
        }
    }

    /**
     * 处理认证阶段。
     */
    private void handleAuth(ChannelHandlerContext ctx, RawMysqlPacket packet) {
        ByteBuf payload = packet.payload();
        writer.setSequenceId(packet.sequenceId() + 1);

        HandshakeResponsePacket response = HandshakeResponsePacket.decode(
                payload, MysqlConstants.SERVER_DEFAULT_CAPABILITIES);

        String username = response.username();
        byte[] authResponse = response.authResponse();

        // 验证认证
        if (!userManager.authenticate(username, authResponse, authChallenge)) {
            log.warn("认证失败: user={}, connectionId={}", username, connectionId);
            writer.writeErr(new ErrPacket(ErrorMapping.ER_ACCESS_DENIED, "28000",
                    "Access denied for user '" + username + "'"));
            writer.flush();
            ctx.close();
            return;
        }

        log.info("认证成功: user={}, connectionId={}", username, connectionId);

        // 创建会话——每连接独立的 ExecutionContext 保证事务隔离
        ExecutionContext execCtx = new ExecutionContext(txnManager);

        // 如果传入的是 StorageCatalog，则创建真实的 StorageDataSource
        DataSourceSpi effectiveDataSource = dataSource;
        if (catalog instanceof StorageCatalog storageCatalog &&
            (dataSource == null || dataSource instanceof MockDataSourceAdapter)) {
            effectiveDataSource = storageCatalog.createStorageDataSource(execCtx);
            log.info("使用真实 StorageDataSource for connectionId={}", connectionId);
        }

        this.session = new ConnectionSession(connectionId, catalog, effectiveDataSource, execCtx);

        // 设置默认数据库：优先使用配置文件的默认值
        // 注意：不直接使用 response.database()，因为某些客户端（如 Navicat）的握手包
        // 解析可能因 auth response 长度差异导致 database 字段错位读取到无关字符串
        if (defaultDatabase != null && !defaultDatabase.isEmpty()) {
            session.setCurrentDatabase(defaultDatabase);
        }
        String clientDb = response.database();
        if (clientDb != null && !clientDb.isEmpty()) {
            log.debug("Client requested database in handshake: '{}'", clientDb);
        }

        this.dispatcher = new CommandDispatcher(session, writer);

        // 发送 OK
        writer.writeOk(OkPacket.ok(StatusFlagBuilder.autoCommit()));
        writer.flush();

        state = State.COMMAND;
    }

    /**
     * 处理命令阶段。
     * 命令执行在独立线程池中进行，避免阻塞 EventLoop。
     */
    private void handleCommand(ChannelHandlerContext ctx, RawMysqlPacket packet) {
        ByteBuf payload = packet.payload();
        byte commandByte = payload.readByte();

        // COM_QUIT：直接在 EventLoop 中处理
        if (commandByte == MysqlConstants.COM_QUIT) {
            log.info("客户端断开: connectionId={}", connectionId);
            ctx.close();
            return;
        }

        // 重置序号为请求序号+1
        writer.setSequenceId(packet.sequenceId() + 1);

        // 保留 payload 引用，防止在异步执行前被释放
        payload.retain();

        // 提交到 SQL 执行线程池
        sqlExecutor.execute(() -> {
            try {
                dispatcher.dispatch(commandByte, payload);
            } catch (Exception e) {
                log.error("命令处理异常: connectionId={}", connectionId, e);
                try {
                    writer.writeErr(ErrorMapping.fromException(e));
                    writer.flush();
                } catch (Exception writeErr) {
                    log.error("写入错误响应失败", writeErr);
                    ctx.close();
                }
            } finally {
                payload.release();
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("连接断开: connectionId={}", connectionId);
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("连接异常: connectionId={}", connectionId, cause);
        if (session != null) {
            session.close();
        }
        ctx.close();
    }
}
