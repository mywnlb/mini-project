package cn.zhangyis.minidb.server.auth;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 测试：mysql_native_password 认证算法。
 * 验证 computeToken / verify 的正确性和边界情况。
 */
class MysqlNativePasswordAuthTest {

    // ==================== 基本 round-trip ====================

    @Test
    void computeToken_verify_roundTrip() {
        byte[] challenge = randomChallenge();
        String password = "secret123";

        byte[] token = MysqlNativePasswordAuth.computeToken(password, challenge);
        assertTrue(MysqlNativePasswordAuth.verify(token, challenge, password));
    }

    @Test
    void computeToken_verify_differentPasswords() {
        byte[] challenge = randomChallenge();
        byte[] token = MysqlNativePasswordAuth.computeToken("correct", challenge);

        assertTrue(MysqlNativePasswordAuth.verify(token, challenge, "correct"));
        assertFalse(MysqlNativePasswordAuth.verify(token, challenge, "wrong"));
    }

    @Test
    void computeToken_verify_differentChallenges() {
        byte[] challenge1 = randomChallenge();
        byte[] challenge2 = randomChallenge();
        String password = "mypass";

        byte[] token = MysqlNativePasswordAuth.computeToken(password, challenge1);
        assertTrue(MysqlNativePasswordAuth.verify(token, challenge1, password));
        // 用不同 challenge 验证应失败
        assertFalse(MysqlNativePasswordAuth.verify(token, challenge2, password));
    }

    // ==================== 空密码 ====================

    @Test
    void emptyPassword_returnsEmptyToken() {
        byte[] challenge = randomChallenge();
        byte[] token = MysqlNativePasswordAuth.computeToken("", challenge);
        assertEquals(0, token.length);
    }

    @Test
    void nullPassword_returnsEmptyToken() {
        byte[] challenge = randomChallenge();
        byte[] token = MysqlNativePasswordAuth.computeToken(null, challenge);
        assertEquals(0, token.length);
    }

    @Test
    void emptyPassword_verify_emptyToken() {
        byte[] challenge = randomChallenge();
        // 空密码 + 空 token → 通过
        assertTrue(MysqlNativePasswordAuth.verify(new byte[0], challenge, ""));
        assertTrue(MysqlNativePasswordAuth.verify(null, challenge, ""));
    }

    @Test
    void emptyPassword_verify_nonEmptyToken_fails() {
        byte[] challenge = randomChallenge();
        // 空密码但发了非空 token → 失败
        assertFalse(MysqlNativePasswordAuth.verify(new byte[]{1, 2, 3}, challenge, ""));
    }

    @Test
    void nonEmptyPassword_verify_emptyToken_fails() {
        byte[] challenge = randomChallenge();
        // 非空密码但发了空 token → 失败
        assertFalse(MysqlNativePasswordAuth.verify(new byte[0], challenge, "secret"));
        assertFalse(MysqlNativePasswordAuth.verify(null, challenge, "secret"));
    }

    // ==================== token 长度 ====================

    @Test
    void computeToken_always20bytes() {
        byte[] challenge = randomChallenge();
        byte[] token = MysqlNativePasswordAuth.computeToken("password", challenge);
        assertEquals(20, token.length); // SHA-1 输出固定 20 字节
    }

    // ==================== 已知向量验证 ====================

    @Test
    void knownVector_deterministicChallenge() throws Exception {
        // 使用固定 challenge 和密码，验证 token 可重复计算
        byte[] challenge = new byte[20];
        for (int i = 0; i < 20; i++) challenge[i] = (byte) i;
        String password = "test";

        byte[] token1 = MysqlNativePasswordAuth.computeToken(password, challenge);
        byte[] token2 = MysqlNativePasswordAuth.computeToken(password, challenge);
        assertArrayEquals(token1, token2);

        // 手动计算验证：
        // stage1 = SHA1("test")
        // stage2 = SHA1(stage1)
        // scramble = SHA1(challenge + stage2)
        // token = stage1 XOR scramble
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] stage1 = md.digest("test".getBytes());
        md.reset();
        byte[] stage2 = md.digest(stage1);
        md.reset();
        byte[] combined = new byte[20 + 20];
        System.arraycopy(challenge, 0, combined, 0, 20);
        System.arraycopy(stage2, 0, combined, 20, 20);
        byte[] scramble = md.digest(combined);
        byte[] expected = new byte[20];
        for (int i = 0; i < 20; i++) expected[i] = (byte) (stage1[i] ^ scramble[i]);

        assertArrayEquals(expected, token1);
    }

    // ==================== UserManager 集成 ====================

    @Test
    void userManager_authenticate_defaultRoot() {
        UserManager um = new UserManager();
        um.addUser("root", "");
        byte[] challenge = randomChallenge();
        // 空密码空 token
        assertTrue(um.authenticate("root", new byte[0], challenge));
    }

    @Test
    void userManager_authenticate_withPassword() {
        UserManager um = new UserManager();
        um.addUser("admin", "admin123");
        byte[] challenge = randomChallenge();
        byte[] token = MysqlNativePasswordAuth.computeToken("admin123", challenge);
        assertTrue(um.authenticate("admin", token, challenge));
    }

    @Test
    void userManager_authenticate_wrongPassword() {
        UserManager um = new UserManager();
        um.addUser("admin", "admin123");
        byte[] challenge = randomChallenge();
        byte[] token = MysqlNativePasswordAuth.computeToken("wrongpass", challenge);
        assertFalse(um.authenticate("admin", token, challenge));
    }

    @Test
    void userManager_authenticate_unknownUser() {
        UserManager um = new UserManager();
        byte[] challenge = randomChallenge();
        assertFalse(um.authenticate("nobody", new byte[0], challenge));
    }

    @Test
    void userManager_removeUser() {
        UserManager um = new UserManager();
        um.addUser("temp", "pass");
        assertTrue(um.userExists("temp"));
        um.removeUser("temp");
        assertFalse(um.userExists("temp"));
    }

    // ==================== 工具方法 ====================

    private static byte[] randomChallenge() {
        byte[] challenge = new byte[20];
        new SecureRandom().nextBytes(challenge);
        return challenge;
    }
}
