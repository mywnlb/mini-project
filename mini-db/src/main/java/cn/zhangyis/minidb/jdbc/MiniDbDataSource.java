package cn.zhangyis.minidb.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * mini-db javax.sql.DataSource 实现。
 *
 * <p>提供标准连接池入口，通过 host/port/database/username/password
 * 属性配置，每次 {@link #getConnection()} 创建新的 {@link MiniDbConnection}。</p>
 */
public class MiniDbDataSource implements DataSource {

    private String host = "localhost";
    private int port = 3307;
    private String database = "";
    private String username = "root";
    private String password = "";
    private PrintWriter logWriter;
    private int loginTimeout = 0;

    public MiniDbDataSource() {}

    public MiniDbDataSource(String host, int port, String database) {
        this.host = host;
        this.port = port;
        this.database = database;
    }

    // ==================== DataSource 接口 ====================

    @Override
    public Connection getConnection() throws SQLException {
        return new MiniDbConnection(host, port, database, username, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return new MiniDbConnection(host, port, database, username, password);
    }

    @Override
    public PrintWriter getLogWriter() { return logWriter; }

    @Override
    public void setLogWriter(PrintWriter out) { this.logWriter = out; }

    @Override
    public void setLoginTimeout(int seconds) { this.loginTimeout = seconds; }

    @Override
    public int getLoginTimeout() { return loginTimeout; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) { return false; }

    // ==================== 属性 Getter/Setter ====================

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
