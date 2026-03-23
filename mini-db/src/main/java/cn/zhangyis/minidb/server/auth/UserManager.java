package cn.zhangyis.minidb.server.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存用户凭证管理器。
 *
 * <p>设计模式：单例（Singleton）——整个服务器生命周期只需一个用户管理器实例，
 * 通过 {@link #getInstance()} 获取。也支持通过构造器创建独立实例用于测试。</p>
 *
 * <p>线程安全：使用 ConcurrentHashMap 存储用户信息，支持多连接并发认证。</p>
 *
 * <p>当前实现为内存存储，后续可扩展为持久化（系统表）或外部认证（LDAP/PAM）。</p>
 */
public class UserManager {

    /** 用户名 → 明文密码。ConcurrentHashMap 保证并发安全 */
    private final ConcurrentMap<String, String> users = new ConcurrentHashMap<>();

    /** 全局单例 */
    private static final UserManager INSTANCE = new UserManager();

    static {
        // 默认用户：root 空密码（与 MySQL 默认行为一致）
        INSTANCE.addUser("root", "");
    }

    public UserManager() {}

    public static UserManager getInstance() {
        return INSTANCE;
    }

    /**
     * 添加用户。若用户已存在则更新密码。
     */
    public void addUser(String username, String password) {
        users.put(username, password);
    }

    /**
     * 移除用户。
     */
    public void removeUser(String username) {
        users.remove(username);
    }

    /**
     * 验证客户端认证令牌。
     *
     * @param username    用户名
     * @param clientToken 客户端发送的认证令牌
     * @param challenge   服务端发送的 20 字节挑战
     * @return true 如果认证通过
     */
    public boolean authenticate(String username, byte[] clientToken, byte[] challenge) {
        String storedPassword = users.get(username);
        if (storedPassword == null) {
            return false; // 用户不存在
        }
        return MysqlNativePasswordAuth.verify(clientToken, challenge, storedPassword);
    }

    /**
     * 检查用户是否存在。
     */
    public boolean userExists(String username) {
        return users.containsKey(username);
    }
}
