package cn.zhangyis.minidb.server.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * mysql_native_password 认证算法实现。
 *
 * <p>算法流程：
 * <pre>
 * 服务端持有：storedPassword（明文或 SHA1(SHA1(password)) 的 hex）
 * 服务端发送：20 字节随机挑战 challenge
 *
 * 客户端计算：
 *   stage1 = SHA1(password)
 *   stage2 = SHA1(stage1)
 *   scramble = SHA1(challenge + stage2)
 *   token = stage1 XOR scramble
 *
 * 服务端验证：
 *   stage2_stored = SHA1(SHA1(storedPassword))  -- 或直接存储的 double-SHA1
 *   scramble = SHA1(challenge + stage2_stored)
 *   recovered_stage1 = token XOR scramble
 *   验证 SHA1(recovered_stage1) == stage2_stored
 * </pre></p>
 *
 * <p>设计决策：同时提供 computeToken（客户端/JDBC驱动用）
 * 和 verify（服务端用），避免两端各自实现导致不一致。</p>
 */
public final class MysqlNativePasswordAuth {

    private MysqlNativePasswordAuth() {}

    /**
     * 客户端计算认证令牌。
     *
     * @param password  明文密码
     * @param challenge 服务端发送的 20 字节挑战
     * @return 20 字节认证令牌
     */
    public static byte[] computeToken(String password, byte[] challenge) {
        if (password == null || password.isEmpty()) {
            return new byte[0]; // 空密码返回空 token
        }
        byte[] stage1 = sha1(password.getBytes());
        byte[] stage2 = sha1(stage1);
        byte[] scramble = sha1(concat(challenge, stage2));
        return xor(stage1, scramble);
    }

    /**
     * 服务端验证客户端认证令牌。
     *
     * @param clientToken    客户端发送的认证令牌
     * @param challenge      服务端发送的 20 字节挑战
     * @param storedPassword 存储的明文密码
     * @return true 如果认证通过
     */
    public static boolean verify(byte[] clientToken, byte[] challenge, String storedPassword) {
        // 空密码：客户端应发送空 token
        if (storedPassword == null || storedPassword.isEmpty()) {
            return clientToken == null || clientToken.length == 0;
        }
        // 空 token 但密码非空：认证失败
        if (clientToken == null || clientToken.length == 0) {
            return false;
        }
        // 重新计算期望的 token 并比较
        byte[] expectedToken = computeToken(storedPassword, challenge);
        return constantTimeEquals(expectedToken, clientToken);
    }

    // ==================== 内部工具方法 ====================

    private static byte[] sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 在所有 JVM 中必须可用
            throw new RuntimeException("SHA-1 算法不可用", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    /**
     * 常量时间比较，防止时序攻击。
     * 无论哪一位不同，都遍历完所有字节再返回结果。
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
