package cn.zhangyis.minidb.storage.disk;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryPolicy 单元测试
 *
 * @author MiniDB
 * @version 1.0
 */
class RetryPolicyTest {

    @Test
    void testDefaultPolicy() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertEquals(3, policy.getMaxAttempts());
        assertEquals(Duration.ofMillis(100), policy.getInitialDelay());
        assertEquals(Duration.ofSeconds(5), policy.getMaxDelay());
        assertEquals(Duration.ofSeconds(30), policy.getTimeout());
        assertTrue(policy.isJitterEnabled());
    }

    @Test
    void testCustomPolicy() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(5)
            .initialDelay(Duration.ofMillis(50))
            .maxDelay(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(1))
            .jitterEnabled(false)
            .build();

        assertEquals(5, policy.getMaxAttempts());
        assertEquals(Duration.ofMillis(50), policy.getInitialDelay());
        assertEquals(Duration.ofSeconds(10), policy.getMaxDelay());
        assertEquals(Duration.ofMinutes(1), policy.getTimeout());
        assertFalse(policy.isJitterEnabled());
    }

    @Test
    void testCalculateDelayWithoutJitter() {
        RetryPolicy policy = RetryPolicy.builder()
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(5))
            .jitterEnabled(false)
            .build();

        // 指数退避: 100 * 2^n
        Duration delay1 = policy.calculateDelay(1);
        assertEquals(200, delay1.toMillis()); // 100 * 2^1

        Duration delay2 = policy.calculateDelay(2);
        assertEquals(400, delay2.toMillis()); // 100 * 2^2

        Duration delay3 = policy.calculateDelay(3);
        assertEquals(800, delay3.toMillis()); // 100 * 2^3

        // 测试上限: 100 * 2^10 = 102400ms > 5000ms, 应该被限制为 5000ms
        Duration delay10 = policy.calculateDelay(10);
        assertEquals(5000, delay10.toMillis()); // maxDelay
    }

    @Test
    void testCalculateDelayWithJitter() {
        RetryPolicy policy = RetryPolicy.builder()
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(5))
            .jitterEnabled(true)
            .build();

        // 抖动范围: 50%-100%
        for (int i = 1; i <= 5; i++) {
            Duration delay = policy.calculateDelay(i);
            long baseDelay = 100L * (1L << i); // 100 * 2^i
            long minDelay = (long) (baseDelay * 0.5);
            long maxDelay = baseDelay;

            // 验证抖动范围
            assertTrue(delay.toMillis() >= minDelay,
                "delay should be >= " + minDelay + " but was " + delay.toMillis());
            assertTrue(delay.toMillis() <= maxDelay,
                "delay should be <= " + maxDelay + " but was " + delay.toMillis());
        }
    }

    @Test
    void testShouldRetry_MaxAttempts() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .timeout(Duration.ofMinutes(1))
            .build();

        // attempt 1, 2: 应该重试
        assertTrue(policy.shouldRetry(1, Duration.ZERO));
        assertTrue(policy.shouldRetry(2, Duration.ZERO));

        // attempt 3: 达到最大次数，不应该重试
        assertFalse(policy.shouldRetry(3, Duration.ZERO));
        assertFalse(policy.shouldRetry(4, Duration.ZERO));
    }

    @Test
    void testShouldRetry_Timeout() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(10)
            .timeout(Duration.ofSeconds(5))
            .build();

        // 未超时: 应该重试
        assertTrue(policy.shouldRetry(1, Duration.ofSeconds(1)));
        assertTrue(policy.shouldRetry(2, Duration.ofSeconds(3)));

        // 超时: 不应该重试
        assertFalse(policy.shouldRetry(3, Duration.ofSeconds(5)));
        assertFalse(policy.shouldRetry(4, Duration.ofSeconds(10)));
    }

    @Test
    void testShouldRetry_Combined() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .timeout(Duration.ofSeconds(10))
            .build();

        // 在范围内
        assertTrue(policy.shouldRetry(1, Duration.ofSeconds(2)));
        assertTrue(policy.shouldRetry(2, Duration.ofSeconds(5)));

        // 达到最大尝试次数 (即使未超时)
        assertFalse(policy.shouldRetry(3, Duration.ofSeconds(5)));

        // 超时 (即使未达最大尝试次数)
        assertFalse(policy.shouldRetry(2, Duration.ofSeconds(10)));
    }

    @Test
    void testBuilder_InvalidMaxAttempts() {
        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().maxAttempts(0).build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().maxAttempts(-1).build()
        );
    }

    @Test
    void testBuilder_InvalidInitialDelay() {
        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().initialDelay(Duration.ZERO).build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().initialDelay(Duration.ofMillis(-100)).build()
        );
    }

    @Test
    void testBuilder_InvalidMaxDelay() {
        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().maxDelay(Duration.ZERO).build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().maxDelay(Duration.ofMillis(-1000)).build()
        );
    }

    @Test
    void testBuilder_InvalidTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().timeout(Duration.ZERO).build()
        );

        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder().timeout(Duration.ofSeconds(-30)).build()
        );
    }

    @Test
    void testBuilder_MaxDelayLessThanInitialDelay() {
        assertThrows(IllegalArgumentException.class, () ->
            RetryPolicy.builder()
                .initialDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(5))
                .build()
        );
    }

    @Test
    void testToString() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(5))
            .timeout(Duration.ofSeconds(30))
            .jitterEnabled(true)
            .build();

        String str = policy.toString();
        assertTrue(str.contains("maxAttempts=3"));
        assertTrue(str.contains("initialDelay=100ms"));
        assertTrue(str.contains("maxDelay=5000ms"));
        assertTrue(str.contains("timeout=30000ms"));
        assertTrue(str.contains("jitter=true"));
    }
}
