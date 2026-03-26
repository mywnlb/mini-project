package cn.zhangyis.minidb.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class MysqlTableSizeQueryTest {

    private static final String URL_KEY = "mysql.table.size.url";
    private static final String USERNAME_KEY = "mysql.table.size.username";
    private static final String PASSWORD_KEY = "mysql.table.size.password";
    private static final String SCHEMA_KEY = "mysql.table.size.schema";

    private static final String TABLE_SIZE_SQL = """
            SELECT
                TABLE_SCHEMA,
                TABLE_NAME,
                ENGINE,
                TABLE_ROWS,
                DATA_LENGTH,
                INDEX_LENGTH,
                (DATA_LENGTH + INDEX_LENGTH) AS TOTAL_BYTES
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = ?
            ORDER BY TOTAL_BYTES DESC, TABLE_NAME ASC
            """;

    @Test
    void queryEachTableSize() throws Exception {
        assumeFalse(isMissingRequiredProperties(),
                "Skipping MySQL table size query test because mysql.table.size.* system properties are not set");
        MysqlConnectionConfig config = MysqlConnectionConfig.fromSystemProperties();

        try (Connection connection = DriverManager.getConnection(config.url(), config.properties());
             PreparedStatement statement = connection.prepareStatement(TABLE_SIZE_SQL)) {
            statement.setString(1, config.schema(connection));

            List<String> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(String.format(
                            "%s.%s | engine=%s | rows=%d | data=%d | index=%d | total=%d",
                            rs.getString("TABLE_SCHEMA"),
                            rs.getString("TABLE_NAME"),
                            rs.getString("ENGINE"),
                            rs.getLong("TABLE_ROWS"),
                            rs.getLong("DATA_LENGTH"),
                            rs.getLong("INDEX_LENGTH"),
                            rs.getLong("TOTAL_BYTES")));
                }
            }

            assertFalse(rows.isEmpty(), "No tables found for schema " + config.schema(connection));
            rows.forEach(System.out::println);
        }
    }

    private static boolean isMissingRequiredProperties() {
        return System.getProperty(URL_KEY, "").trim().isEmpty()
                || System.getProperty(USERNAME_KEY, "").trim().isEmpty()
                || System.getProperty(PASSWORD_KEY, "").trim().isEmpty();
    }

    private record MysqlConnectionConfig(String url, String username, String password, String schema) {

        private static MysqlConnectionConfig fromSystemProperties() {
            String url = requiredProperty(URL_KEY);
            String username = requiredProperty(USERNAME_KEY);
            String password = requiredProperty(PASSWORD_KEY);
            String schema = System.getProperty(SCHEMA_KEY, "").trim();
            return new MysqlConnectionConfig(url, username, password, schema);
        }

        private static String requiredProperty(String key) {
            String value = System.getProperty(key, "").trim();
            if (value.isEmpty()) {
                throw new IllegalStateException("Missing required system property: " + key);
            }
            return value;
        }

        private Properties properties() {
            Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password);
            return properties;
        }

        private String schema(Connection connection) throws SQLException {
            if (!schema.isEmpty()) {
                return schema;
            }
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                throw new IllegalStateException(
                        "Unable to resolve schema; set system property " + SCHEMA_KEY);
            }
            return catalog;
        }
    }
}
