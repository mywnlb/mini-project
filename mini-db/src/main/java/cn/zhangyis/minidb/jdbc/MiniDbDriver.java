package cn.zhangyis.minidb.jdbc;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mini-db JDBC 驱动入口。
 *
 * <p>URL 格式：{@code jdbc:minidb://host:port/database}</p>
 *
 * <p>设计模式：工厂方法（Factory Method）——{@link #connect} 方法根据 URL
 * 创建对应的 {@link MiniDbConnection} 实例。通过 SPI（META-INF/services）
 * 和 static 块自动注册到 {@link DriverManager}。</p>
 *
 * <p>使用方式：
 * <pre>
 * Connection conn = DriverManager.getConnection(
 *     "jdbc:minidb://localhost:3307/test", "root", "");
 * </pre></p>
 */
public class MiniDbDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:minidb://";
    private static final Pattern URL_PATTERN = Pattern.compile(
            "jdbc:minidb://([^:/]+)(?::(\\d+))?(?:/([^?]*))?");

    static {
        try {
            DriverManager.registerDriver(new MiniDbDriver());
        } catch (SQLException e) {
            throw new RuntimeException("注册 MiniDbDriver 失败", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null; // 非本驱动的 URL，返回 null（JDBC 规范）
        }
        Matcher matcher = URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new SQLException("无效的 URL 格式: " + url);
        }

        String host = matcher.group(1);
        int port = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 3307;
        String database = matcher.group(3) != null ? matcher.group(3) : "";
        String username = info != null ? info.getProperty("user", "root") : "root";
        String password = info != null ? info.getProperty("password", "") : "";

        return new MiniDbConnection(host, port, database, username, password);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() { return 1; }

    @Override
    public int getMinorVersion() { return 0; }

    @Override
    public boolean jdbcCompliant() { return false; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
