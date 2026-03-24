package cn.zhangyis.minidb.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * mini-db 服务器配置（YAML 格式）。
 *
 * <p>配置加载优先级（高 → 低）：
 * <ol>
 *   <li>外部文件：工作目录下的 {@code minidb.yml}</li>
 *   <li>classpath：{@code minidb.yml}（打包在 jar 内的默认配置）</li>
 *   <li>代码内置默认值</li>
 * </ol>
 *
 * <p>使用方式：
 * <pre>{@code
 * ServerConfig config = ServerConfig.load();
 * int port = config.getPort();
 * }</pre>
 */
public class ServerConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    private static final String CONFIG_FILE = "minidb.yml";

    // ==================== 默认值 ====================
    private static final int DEFAULT_PORT = 3307;
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "";
    private static final String DEFAULT_DATA_DIR = "data";
    private static final String DEFAULT_SYSTEM_TABLESPACE = "system";
    private static final int DEFAULT_BUFFER_POOL_PAGES = 1024;
    private static final String DEFAULT_DATABASE_NAME = "default";

    // ==================== 配置项 ====================
    private final int port;
    private final String user;
    private final String password;
    private final String dataDir;
    private final String systemTablespace;
    private final int bufferPoolPages;
    private final String defaultDatabaseName;

    @SuppressWarnings("unchecked")
    private ServerConfig(Map<String, Object> root) {
        Map<String, Object> server = getSection(root, "server");
        Map<String, Object> storage = getSection(root, "storage");
        Map<String, Object> database = getSection(root, "database");

        this.port = getInt(server, "port", DEFAULT_PORT);
        this.user = getString(server, "user", DEFAULT_USER);
        this.password = getString(server, "password", DEFAULT_PASSWORD);
        this.dataDir = getString(storage, "data-dir", DEFAULT_DATA_DIR);
        this.systemTablespace = getString(storage, "system-tablespace", DEFAULT_SYSTEM_TABLESPACE);
        this.bufferPoolPages = getInt(storage, "buffer-pool-pages", DEFAULT_BUFFER_POOL_PAGES);
        this.defaultDatabaseName = getString(database, "default-name", DEFAULT_DATABASE_NAME);
    }

    /**
     * 加载配置。
     *
     * <p>优先从工作目录读取 {@code minidb.yml}，
     * 不存在则从 classpath 读取，都没有则使用默认值。</p>
     */
    @SuppressWarnings("unchecked")
    public static ServerConfig load() {
        Yaml yaml = new Yaml();

        // 1. 尝试从外部文件加载
        Path externalFile = Path.of(CONFIG_FILE);
        if (Files.exists(externalFile)) {
            try (InputStream in = Files.newInputStream(externalFile)) {
                Map<String, Object> root = yaml.load(in);
                log.info("配置已加载: {}", externalFile.toAbsolutePath());
                return new ServerConfig(root != null ? root : Map.of());
            } catch (IOException e) {
                log.warn("读取外部配置文件失败: {}, 回退到 classpath", externalFile, e);
            }
        }

        // 2. 从 classpath 加载
        try (InputStream in = ServerConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                Map<String, Object> root = yaml.load(in);
                log.info("配置已加载: classpath:{}", CONFIG_FILE);
                return new ServerConfig(root != null ? root : Map.of());
            }
        } catch (IOException e) {
            log.warn("读取 classpath 配置失败, 使用默认值", e);
        }

        // 3. 全部使用默认值
        log.info("未找到配置文件, 使用默认值");
        return new ServerConfig(Map.of());
    }

    // ==================== Getters ====================

    public int getPort() { return port; }

    public String getUser() { return user; }

    public String getPassword() { return password; }

    public String getDataDir() { return dataDir; }

    public String getSystemTablespace() { return systemTablespace; }

    public int getBufferPoolPages() { return bufferPoolPages; }

    public String getDefaultDatabaseName() { return defaultDatabaseName; }

    // ==================== YAML 解析辅助 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getSection(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    private static String getString(Map<String, Object> section, String key, String defaultValue) {
        Object value = section.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private static int getInt(Map<String, Object> section, String key, int defaultValue) {
        Object value = section.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                log.warn("配置项 {} 值非法: '{}', 使用默认值: {}", key, value, defaultValue);
            }
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "port=" + port +
                ", user='" + user + '\'' +
                ", dataDir='" + dataDir + '\'' +
                ", systemTablespace='" + systemTablespace + '\'' +
                ", bufferPoolPages=" + bufferPoolPages +
                ", defaultDatabase='" + defaultDatabaseName + '\'' +
                '}';
    }
}
