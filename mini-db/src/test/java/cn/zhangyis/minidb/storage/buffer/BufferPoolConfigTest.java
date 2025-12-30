package cn.zhangyis.minidb.storage.buffer;

import cn.zhangyis.minidb.storage.disk.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BufferPoolConfig 单元测试
 *
 * @author MiniDB
 * @version 1.0
 */
class BufferPoolConfigTest {

    // ==================== 默认配置测试 ====================

    @Test
    void testDefaultConfig() {
        BufferPoolConfig config = BufferPoolConfig.defaultConfig(1000);

        // 验证参数值
        assertEquals(1000, config.getPoolSize());
        assertEquals(BufferPoolConfig.DEFAULT_SEGMENT_COUNT, config.getSegmentCount());
        assertEquals(BufferPoolConfig.DEFAULT_LRU_REORDER_INTERVAL, config.getLruReorderInterval());
        assertEquals(BufferPoolConfig.DEFAULT_OLD_BLOCK_RATIO, config.getOldBlockRatio());
        assertEquals(BufferPoolConfig.DEFAULT_OLD_BLOCK_TIME_MS, config.getOldBlockTimeMs());
        assertNotNull(config.getRetryPolicy());
    }

    @Test
    void testDefaultConfig_SegmentMask() {
        BufferPoolConfig config = BufferPoolConfig.defaultConfig(1000);

        // 64 的掩码应该是 63 (0x3F)
        assertEquals(63, config.getSegmentMask());
    }

    @Test
    void testDefaultConfig_SegmentCapacity() {
        BufferPoolConfig config = BufferPoolConfig.defaultConfig(1000);

        // (1000 / 64) + 1 = 16
        assertEquals(16, config.getSegmentCapacity());
    }

    // ==================== Builder 模式测试 ====================

    @Test
    void testBuilder_AllParameters() {
        RetryPolicy customRetry = RetryPolicy.builder()
            .maxAttempts(5)
            .timeout(Duration.ofSeconds(60))
            .build();

        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(2000)
            .segmentCount(128)
            .lruReorderInterval(Duration.ofMillis(50))
            .oldBlockRatio(0.4)
            .oldBlockTimeMs(2000)
            .retryPolicy(customRetry)
            .build();

        assertEquals(2000, config.getPoolSize());
        assertEquals(128, config.getSegmentCount());
        assertEquals(Duration.ofMillis(50), config.getLruReorderInterval());
        assertEquals(0.4, config.getOldBlockRatio());
        assertEquals(2000, config.getOldBlockTimeMs());
        assertEquals(customRetry, config.getRetryPolicy());
    }

    @Test
    void testBuilder_PartialParameters() {
        // 只设置 poolSize 和 segmentCount，其他使用默认值
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(5000)
            .segmentCount(256)
            .build();

        assertEquals(5000, config.getPoolSize());
        assertEquals(256, config.getSegmentCount());
        assertEquals(BufferPoolConfig.DEFAULT_LRU_REORDER_INTERVAL, config.getLruReorderInterval());
        assertEquals(BufferPoolConfig.DEFAULT_OLD_BLOCK_RATIO, config.getOldBlockRatio());
        assertEquals(BufferPoolConfig.DEFAULT_OLD_BLOCK_TIME_MS, config.getOldBlockTimeMs());
    }

    @Test
    void testBuilder_ChainedCalls() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .segmentCount(64)
            .lruReorderInterval(Duration.ofMillis(100))
            .oldBlockRatio(0.375)
            .oldBlockTimeMs(1000)
            .build();

        assertNotNull(config);
        assertEquals(1000, config.getPoolSize());
    }

    // ==================== 参数验证测试 ====================

    @Test
    void testValidation_PoolSizeMissing() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder().build();
        });

        assertTrue(exception.getMessage().contains("poolSize is required"));
    }

    @Test
    void testValidation_PoolSizeNegative() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(-100)
                .build();
        });

        assertTrue(exception.getMessage().contains("poolSize must be positive"));
    }

    @Test
    void testValidation_PoolSizeZero() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(0)
                .build();
        });

        assertTrue(exception.getMessage().contains("poolSize must be positive"));
    }

    @Test
    void testValidation_PoolSizeTooLarge() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(20_000_000)  // 超过 10M
                .build();
        });

        assertTrue(exception.getMessage().contains("poolSize too large"));
    }

    @Test
    void testValidation_SegmentCountNotPowerOf2() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .segmentCount(100)  // 不是 2 的幂次方
                .build();
        });

        assertTrue(exception.getMessage().contains("must be a power of 2"));
    }

    @Test
    void testValidation_SegmentCountNegative() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .segmentCount(-64)
                .build();
        });

        assertTrue(exception.getMessage().contains("must be a power of 2"));
    }

    @Test
    void testValidation_SegmentCountTooLarge() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .segmentCount(2048)  // 超过 1024
                .build();
        });

        assertTrue(exception.getMessage().contains("segmentCount too large"));
    }

    @Test
    void testValidation_SegmentCount_ValidPowersOf2() {
        // 测试所有合法的 2 的幂次方
        int[] validCounts = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};

        for (int count : validCounts) {
            BufferPoolConfig config = BufferPoolConfig.builder()
                .poolSize(1000)
                .segmentCount(count)
                .build();

            assertEquals(count, config.getSegmentCount());
        }
    }

    @Test
    void testValidation_LruReorderIntervalNegative() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .lruReorderInterval(Duration.ofMillis(-100))
                .build();
        });

        assertTrue(exception.getMessage().contains("lruReorderInterval must be positive"));
    }

    @Test
    void testValidation_LruReorderIntervalZero() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .lruReorderInterval(Duration.ZERO)
                .build();
        });

        assertTrue(exception.getMessage().contains("lruReorderInterval must be positive"));
    }

    @Test
    void testValidation_LruReorderIntervalTooLong() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .lruReorderInterval(Duration.ofSeconds(120))  // 超过 60 秒
                .build();
        });

        assertTrue(exception.getMessage().contains("lruReorderInterval too long"));
    }

    @Test
    void testValidation_OldBlockRatioNegative() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .oldBlockRatio(-0.1)
                .build();
        });

        assertTrue(exception.getMessage().contains("oldBlockRatio must be in [0.0, 1.0]"));
    }

    @Test
    void testValidation_OldBlockRatioTooLarge() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .oldBlockRatio(1.5)
                .build();
        });

        assertTrue(exception.getMessage().contains("oldBlockRatio must be in [0.0, 1.0]"));
    }

    @Test
    void testValidation_OldBlockRatioBoundaryValues() {
        // 测试边界值: 0.0 和 1.0 应该合法
        BufferPoolConfig config1 = BufferPoolConfig.builder()
            .poolSize(1000)
            .oldBlockRatio(0.0)
            .build();
        assertEquals(0.0, config1.getOldBlockRatio());

        BufferPoolConfig config2 = BufferPoolConfig.builder()
            .poolSize(1000)
            .oldBlockRatio(1.0)
            .build();
        assertEquals(1.0, config2.getOldBlockRatio());
    }

    @Test
    void testValidation_OldBlockTimeMsNegative() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .oldBlockTimeMs(-1000)
                .build();
        });

        assertTrue(exception.getMessage().contains("oldBlockTimeMs must be non-negative"));
    }

    @Test
    void testValidation_OldBlockTimeMsTooLarge() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            BufferPoolConfig.builder()
                .poolSize(1000)
                .oldBlockTimeMs(7200_000)  // 超过 1 小时
                .build();
        });

        assertTrue(exception.getMessage().contains("oldBlockTimeMs too long"));
    }

    @Test
    void testValidation_OldBlockTimeMsZero() {
        // 0 应该是合法的（禁用 Old 区晋升延迟）
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .oldBlockTimeMs(0)
            .build();

        assertEquals(0, config.getOldBlockTimeMs());
    }

    // ==================== 辅助方法测试 ====================

    @Test
    void testGetSegmentMask() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .segmentCount(64)
            .build();

        // 64 - 1 = 63 (0x3F)
        assertEquals(63, config.getSegmentMask());
    }

    @Test
    void testGetSegmentMask_DifferentCounts() {
        assertEquals(15, BufferPoolConfig.builder().poolSize(1000).segmentCount(16).build().getSegmentMask());
        assertEquals(31, BufferPoolConfig.builder().poolSize(1000).segmentCount(32).build().getSegmentMask());
        assertEquals(127, BufferPoolConfig.builder().poolSize(1000).segmentCount(128).build().getSegmentMask());
        assertEquals(255, BufferPoolConfig.builder().poolSize(1000).segmentCount(256).build().getSegmentMask());
    }

    @Test
    void testGetSegmentCapacity() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .segmentCount(64)
            .build();

        // (1000 / 64) + 1 = 16
        assertEquals(16, config.getSegmentCapacity());
    }

    @Test
    void testGetSegmentCapacity_DifferentSizes() {
        // poolSize=1000, segmentCount=32: (1000/32)+1 = 32
        assertEquals(32, BufferPoolConfig.builder().poolSize(1000).segmentCount(32).build().getSegmentCapacity());

        // poolSize=10000, segmentCount=64: (10000/64)+1 = 157
        assertEquals(157, BufferPoolConfig.builder().poolSize(10000).segmentCount(64).build().getSegmentCapacity());

        // poolSize=128, segmentCount=16: (128/16)+1 = 9
        assertEquals(9, BufferPoolConfig.builder().poolSize(128).segmentCount(16).build().getSegmentCapacity());
    }

    // ==================== toString 测试 ====================

    @Test
    void testToString() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .segmentCount(64)
            .lruReorderInterval(Duration.ofMillis(100))
            .oldBlockRatio(0.375)
            .oldBlockTimeMs(1000)
            .build();

        String str = config.toString();

        // 验证包含关键信息
        assertTrue(str.contains("poolSize=1000"));
        assertTrue(str.contains("pages"));
        assertTrue(str.contains("MB"));
        assertTrue(str.contains("segmentCount=64"));
        assertTrue(str.contains("lruReorderInterval=100"));
        assertTrue(str.contains("oldBlockRatio=0.375"));
        assertTrue(str.contains("oldBlockTimeMs=1000"));
        assertTrue(str.contains("retryPolicy="));
    }

    @Test
    void testToString_MemoryCalculation() {
        BufferPoolConfig config = BufferPoolConfig.defaultConfig(1000);
        String str = config.toString();

        // 1000 pages * 16KB/page = 16MB
        // 1000 * 16 / 1024 = 15 MB (整数除法)
        assertTrue(str.contains("15 MB"));
    }

    // ==================== 典型配置场景测试 ====================

    @Test
    void testConfig_Development() {
        // 开发环境: 小内存，快速启动
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(100)
            .segmentCount(16)
            .lruReorderInterval(Duration.ofMillis(200))
            .build();

        assertNotNull(config);
        assertEquals(100, config.getPoolSize());
    }

    @Test
    void testConfig_Testing() {
        // 测试环境: 中等配置
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .segmentCount(64)
            .lruReorderInterval(Duration.ofMillis(100))
            .build();

        assertNotNull(config);
        assertEquals(1000, config.getPoolSize());
    }

    @Test
    void testConfig_Production_OLTP() {
        // 生产环境 OLTP: 高并发随机访问
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(50000)
            .segmentCount(128)
            .lruReorderInterval(Duration.ofMillis(100))
            .oldBlockRatio(0.375)  // 默认值
            .oldBlockTimeMs(1000)
            .build();

        assertNotNull(config);
        assertEquals(50000, config.getPoolSize());
        assertEquals(128, config.getSegmentCount());
    }

    @Test
    void testConfig_Production_OLAP() {
        // 生产环境 OLAP: 大量扫描操作
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(100000)
            .segmentCount(256)
            .lruReorderInterval(Duration.ofMillis(200))
            .oldBlockRatio(0.5)  // 增大 Old 区以应对扫描
            .oldBlockTimeMs(2000)  // 延长晋升时间
            .build();

        assertNotNull(config);
        assertEquals(0.5, config.getOldBlockRatio());
        assertEquals(2000, config.getOldBlockTimeMs());
    }

    @Test
    void testConfig_CloudStorage() {
        // 云存储环境: 需要更多重试
        RetryPolicy cloudRetry = RetryPolicy.builder()
            .maxAttempts(5)
            .timeout(Duration.ofSeconds(60))
            .build();

        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(10000)
            .retryPolicy(cloudRetry)
            .build();

        assertNotNull(config);
        assertEquals(5, config.getRetryPolicy().getMaxAttempts());
    }

    // ==================== 边界值和极端情况测试 ====================

    @Test
    void testConfig_MinimumPoolSize() {
        BufferPoolConfig config = BufferPoolConfig.defaultConfig(1);
        assertEquals(1, config.getPoolSize());
    }

    @Test
    void testConfig_LargePoolSize() {
        BufferPoolConfig config = BufferPoolConfig.defaultConfig(1_000_000);
        assertEquals(1_000_000, config.getPoolSize());
    }

    @Test
    void testConfig_MinimumSegmentCount() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(100)
            .segmentCount(1)
            .build();

        assertEquals(1, config.getSegmentCount());
        assertEquals(0, config.getSegmentMask());
    }

    @Test
    void testConfig_MaximumSegmentCount() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(100000)
            .segmentCount(1024)
            .build();

        assertEquals(1024, config.getSegmentCount());
        assertEquals(1023, config.getSegmentMask());
    }

    @Test
    void testConfig_MinimumLruInterval() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .lruReorderInterval(Duration.ofMillis(1))
            .build();

        assertEquals(1, config.getLruReorderInterval().toMillis());
    }

    @Test
    void testConfig_MaximumLruInterval() {
        BufferPoolConfig config = BufferPoolConfig.builder()
            .poolSize(1000)
            .lruReorderInterval(Duration.ofSeconds(60))
            .build();

        assertEquals(60000, config.getLruReorderInterval().toMillis());
    }
}
