package cn.zhangyis.minidb.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 测试：JDBC 驱动 URL 解析和驱动注册。
 */
class MiniDbDriverTest {

    @Test
    void acceptsURL_validMiniDbUrl() throws Exception {
        MiniDbDriver driver = new MiniDbDriver();
        assertTrue(driver.acceptsURL("jdbc:minidb://localhost:3307/test"));
        assertTrue(driver.acceptsURL("jdbc:minidb://127.0.0.1:3307/mydb"));
        assertTrue(driver.acceptsURL("jdbc:minidb://host/db"));
        assertTrue(driver.acceptsURL("jdbc:minidb://host"));
    }

    @Test
    void acceptsURL_rejectsOtherUrls() throws Exception {
        MiniDbDriver driver = new MiniDbDriver();
        assertFalse(driver.acceptsURL("jdbc:mysql://localhost:3306/test"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/test"));
        assertFalse(driver.acceptsURL(null));
        assertFalse(driver.acceptsURL(""));
    }

    @Test
    void connect_returnsNullForNonMiniDbUrl() throws Exception {
        MiniDbDriver driver = new MiniDbDriver();
        assertNull(driver.connect("jdbc:mysql://localhost/test", new Properties()));
    }

    @Test
    void connect_invalidUrlFormat_throwsSQLException() {
        MiniDbDriver driver = new MiniDbDriver();
        assertThrows(SQLException.class, () ->
                driver.connect("jdbc:minidb://", new Properties()));
    }

    @Test
    void driverRegisteredWithDriverManager() throws Exception {
        // MiniDbDriver 的 static 块会自动注册
        Class.forName("cn.zhangyis.minidb.jdbc.MiniDbDriver");
        var driver = DriverManager.getDriver("jdbc:minidb://localhost:3307/test");
        assertNotNull(driver);
        assertInstanceOf(MiniDbDriver.class, driver);
    }

    @Test
    void driverVersion() {
        MiniDbDriver driver = new MiniDbDriver();
        assertEquals(1, driver.getMajorVersion());
        assertEquals(0, driver.getMinorVersion());
        assertFalse(driver.jdbcCompliant());
    }
}
