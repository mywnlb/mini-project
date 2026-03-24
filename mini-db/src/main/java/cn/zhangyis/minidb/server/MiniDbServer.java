package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.server.netty.MysqlPacketDecoder;
import cn.zhangyis.minidb.server.netty.MysqlPacketEncoder;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * mini-db MySQL 协议服务器。
 *
 * <p>基于 Netty NIO 实现，监听 TCP 端口，接受标准 MySQL 客户端连接。</p>
 *
 * <p>设计模式：Builder 模式——通过 {@link MiniDbServerBuilder} 构造，
 * 避免构造器参数过多。服务器本身是不可变的（构造后配置不可更改）。</p>
 *
 * <p>线程模型：
 * <ul>
 *   <li>Boss Group（1线程）：接受新连接</li>
 *   <li>Worker Group（CPU核心数线程）：处理 I/O 和协议编解码</li>
 *   <li>SQL Executor（独立线程池）：执行 SQL 查询（阻塞操作隔离）</li>
 * </ul></p>
 *
 * <p>核心不变式：每个连接的 SQL 执行不在 EventLoop 线程中进行。</p>
 */
public class MiniDbServer {

    private static final Logger log = LoggerFactory.getLogger(MiniDbServer.class);

    private final int port;
    private final CatalogSpi catalog;
    private final DataSourceSpi dataSource;
    private final TransactionManager txnManager;
    private final UserManager userManager;
    private final int sqlThreadPoolSize;
    private final String defaultDatabase;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ExecutorService sqlExecutor;
    private Channel serverChannel;

    MiniDbServer(int port, CatalogSpi catalog, DataSourceSpi dataSource,
                 TransactionManager txnManager, UserManager userManager,
                 int sqlThreadPoolSize, String defaultDatabase) {
        this.port = port;
        this.catalog = catalog;
        this.dataSource = dataSource;
        this.txnManager = txnManager;
        this.userManager = userManager;
        this.sqlThreadPoolSize = sqlThreadPoolSize;
        this.defaultDatabase = defaultDatabase;
    }

    /**
     * 启动服务器，绑定端口并开始接受连接。
     * 此方法会阻塞直到服务器关闭。
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        sqlExecutor = Executors.newFixedThreadPool(sqlThreadPoolSize, r -> {
            Thread t = new Thread(r, "minidb-sql-worker");
            t.setDaemon(true);
            return t;
        });

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("decoder", new MysqlPacketDecoder());
                        pipeline.addLast("encoder", new MysqlPacketEncoder());
                        pipeline.addLast("handler", new MysqlConnectionHandler(
                                catalog, dataSource, txnManager, userManager, sqlExecutor, defaultDatabase));
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        log.info("mini-db 服务器已启动，监听端口: {}", port);

        serverChannel.closeFuture().sync();
    }

    /**
     * 异步启动服务器（不阻塞调用线程）。
     * 适用于测试场景和嵌入式启动。
     */
    public void startAsync() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        sqlExecutor = Executors.newFixedThreadPool(sqlThreadPoolSize, r -> {
            Thread t = new Thread(r, "minidb-sql-worker");
            t.setDaemon(true);
            return t;
        });

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("decoder", new MysqlPacketDecoder());
                        pipeline.addLast("encoder", new MysqlPacketEncoder());
                        pipeline.addLast("handler", new MysqlConnectionHandler(
                                catalog, dataSource, txnManager, userManager, sqlExecutor, defaultDatabase));
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        log.info("mini-db 服务器已启动（异步模式），监听端口: {}", port);
    }

    /**
     * 优雅关闭服务器。
     */
    public void stop() {
        log.info("正在关闭 mini-db 服务器...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (sqlExecutor != null) {
            sqlExecutor.shutdown();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("mini-db 服务器已关闭");
    }

    public int port() { return port; }
}
