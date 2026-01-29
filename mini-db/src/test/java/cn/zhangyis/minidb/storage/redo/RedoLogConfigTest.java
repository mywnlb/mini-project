package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.redo.buffer.LockBasedRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.buffer.LockFreeRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBufferApi;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBufferFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedoLogConfig 和 RedoLogBufferFactory 测试
 */
@DisplayName("RedoLogConfig 和工厂测试")
class RedoLogConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("默认配置测试")
    void testDefaultConfig() {
        RedoLogConfig config = RedoLogConfig.defaultConfig(tempDir.toString());

        assertEquals(tempDir.toString(), config.getDataDir());
        assertEquals(RedoLogConfig.DEFAULT_LOG_FILE_SIZE, config.getLogFileSize());
        assertEquals(RedoLogConfig.DEFAULT_LOG_BUFFER_SIZE, config.getLogBufferSize());
        assertEquals(RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC, config.getFlushLogAtTrxCommit());
        assertEquals(RedoLogConfig.BufferMode.LOCK_BASED, config.getBufferMode());
        assertFalse(config.isLockFree());
    }

    @Test
    @DisplayName("Builder 测试 - 有锁模式")
    void testBuilderLockBased() {
        RedoLogConfig config = RedoLogConfig.builder()
                .dataDir(tempDir.toString())
                .logBufferSize(8 * 1024 * 1024)
                .lockBased()
                .build();

        assertEquals(RedoLogConfig.BufferMode.LOCK_BASED, config.getBufferMode());
        assertFalse(config.isLockFree());
        assertEquals(8 * 1024 * 1024, config.getLogBufferSize());
    }

    @Test
    @DisplayName("Builder 测试 - 无锁模式")
    void testBuilderLockFree() {
        RedoLogConfig config = RedoLogConfig.builder()
                .dataDir(tempDir.toString())
                .logBufferSize(16 * 1024 * 1024)
                .lockFree()
                .linkBufCapacityRatio(8)
                .waitSlotCount(64)
                .waitSlotGranularity(4096)
                .build();

        assertEquals(RedoLogConfig.BufferMode.LOCK_FREE, config.getBufferMode());
        assertTrue(config.isLockFree());
        assertEquals(8, config.getLinkBufCapacityRatio());
        assertEquals(64, config.getWaitSlotCount());
        assertEquals(4096, config.getWaitSlotGranularity());
    }

    @Test
    @DisplayName("LinkBuf 容量计算")
    void testLinkBufCapacityCalculation() {
        // 16MB buffer, ratio=8, granularity=8
        // capacity = 16MB / 8 / 8 = 256K slots
        // 最接近的 2 的幂 = 262144
        RedoLogConfig config = RedoLogConfig.builder()
                .dataDir(tempDir.toString())
                .logBufferSize(16 * 1024 * 1024)
                .linkBufCapacityRatio(8)
                .linkBufGranularity(8)
                .build();

        int capacity = config.getLinkBufCapacity();
        assertTrue(capacity > 0);
        assertEquals(0, capacity & (capacity - 1), "Capacity should be power of 2");
    }

    @Test
    @DisplayName("参数验证 - 无效 dataDir")
    void testInvalidDataDir() {
        assertThrows(IllegalArgumentException.class, () ->
                RedoLogConfig.builder()
                        .dataDir(null)
                        .build());

        assertThrows(IllegalArgumentException.class, () ->
                RedoLogConfig.builder()
                        .dataDir("  ")
                        .build());
    }

    @Test
    @DisplayName("参数验证 - 无效 logBufferSize")
    void testInvalidLogBufferSize() {
        // 必须是 2 的幂
        assertThrows(IllegalArgumentException.class, () ->
                RedoLogConfig.builder()
                        .dataDir(tempDir.toString())
                        .logBufferSize(1000)
                        .build());

        // 太小
        assertThrows(IllegalArgumentException.class, () ->
                RedoLogConfig.builder()
                        .dataDir(tempDir.toString())
                        .logBufferSize(1024)
                        .build());
    }

    @Test
    @DisplayName("参数验证 - 无效 waitSlotCount")
    void testInvalidWaitSlotCount() {
        // 必须是 2 的幂
        assertThrows(IllegalArgumentException.class, () ->
                RedoLogConfig.builder()
                        .dataDir(tempDir.toString())
                        .waitSlotCount(100)
                        .build());
    }

    @Test
    @DisplayName("工厂创建 - 有锁模式")
    void testFactoryCreateLockBased() {
        RedoLogConfig config = RedoLogConfig.builder()
                .dataDir(tempDir.toString())
                .lockBased()
                .logBufferSize(4 * 1024 * 1024)
                .build();

        RedoLogBufferApi buffer = RedoLogBufferFactory.create(config);

        assertNotNull(buffer);
        assertInstanceOf(LockBasedRedoLogBuffer.class, buffer);
        assertEquals(4 * 1024 * 1024, buffer.getCapacity());
    }

    @Test
    @DisplayName("工厂创建 - 无锁模式")
    void testFactoryCreateLockFree() {
        RedoLogConfig config = RedoLogConfig.builder()
                .dataDir(tempDir.toString())
                .lockFree()
                .logBufferSize(4 * 1024 * 1024)
                .build();

        RedoLogBufferApi buffer = RedoLogBufferFactory.create(config);

        assertNotNull(buffer);
        assertInstanceOf(LockFreeRedoLogBuffer.class, buffer);
        assertEquals(4 * 1024 * 1024, buffer.getCapacity());
    }

    @Test
    @DisplayName("toString 测试")
    void testToString() {
        RedoLogConfig config = RedoLogConfig.builder()
                .dataDir(tempDir.toString())
                .lockFree()
                .build();

        String str = config.toString();
        assertTrue(str.contains("LOCK_FREE"));
        assertTrue(str.contains(tempDir.toString()));
    }
}
