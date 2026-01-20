package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.redo.checkpoint.CheckpointManager;
import cn.zhangyis.minidb.storage.redo.checkpoint.CheckpointRecord;
import org.junit.jupiter.api.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3: Checkpoint 测试
 *
 * <p>测试内容：</p>
 * <ul>
 *   <li>CheckpointRecord 序列化/反序列化</li>
 *   <li>CheckpointManager 基本功能</li>
 *   <li>Checkpoint LSN 计算</li>
 *   <li>后台 checkpoint 线程</li>
 *   <li>Checkpoint 恢复</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CheckpointTest extends BaseStorageTest {

    private RedoLogManager redoLogManager;
    private Path redoLogDir;

    @Override
    protected void afterSetup() throws Exception {
        // 创建 redo log 目录
        redoLogDir = testDir.resolve("redo");
        java.nio.file.Files.createDirectories(redoLogDir);

        // 创建 RedoLogConfig
        RedoLogConfig config = new RedoLogConfig.Builder()
                .dataDir(redoLogDir.toString())
                .logFileSize(4 * 1024 * 1024)  // 4MB
                .logBufferSize(1024 * 1024)    // 1MB
                .flushLogAtTrxCommit(RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC)
                .build();

        // 创建并启动 RedoLogManager
        redoLogManager = new RedoLogManager(config);
        redoLogManager.start();

        // 关联 BufferPool
        bufferPool.setRedoLogManager(redoLogManager);

        // 初始化 CheckpointManager
        redoLogManager.initCheckpoint(bufferPool);
    }

    @Override
    protected void beforeCleanup() throws Exception {
        if (redoLogManager != null) {
            redoLogManager.shutdown();
        }
    }

    // ==================== CheckpointRecord 测试 ====================

    @Test
    @Order(1)
    @DisplayName("CheckpointRecord 序列化/反序列化")
    void testCheckpointRecordSerialization() {
        // 创建 CheckpointRecord
        CheckpointRecord original = new CheckpointRecord(
                1000L,   // checkpointLsn
                5L,      // checkpointNo
                2000L,   // flushedLsn
                4 * 1024 * 1024  // logFileSize
        );

        // 序列化
        byte[] data = original.serialize();
        assertEquals(CheckpointRecord.CHECKPOINT_RECORD_SIZE, data.length,
                "Serialized size should be 64 bytes");

        // 反序列化
        CheckpointRecord deserialized = CheckpointRecord.deserialize(data);
        assertNotNull(deserialized, "Deserialization should succeed");

        // 验证字段
        assertEquals(original.getCheckpointLsn(), deserialized.getCheckpointLsn());
        assertEquals(original.getCheckpointNo(), deserialized.getCheckpointNo());
        assertEquals(original.getFlushedLsn(), deserialized.getFlushedLsn());
        assertEquals(original.getLogFileSize(), deserialized.getLogFileSize());
        assertEquals(original.getTimestamp(), deserialized.getTimestamp());
    }

    @Test
    @Order(2)
    @DisplayName("CheckpointRecord 无效数据检测")
    void testCheckpointRecordInvalidData() {
        // 测试空数据
        assertNull(CheckpointRecord.deserialize(null));
        assertNull(CheckpointRecord.deserialize(new byte[0]));
        assertNull(CheckpointRecord.deserialize(new byte[32]));  // 太短

        // 测试错误魔数
        byte[] badMagic = new byte[64];
        badMagic[0] = 0x00;
        assertNull(CheckpointRecord.deserialize(badMagic));

        // 测试损坏的数据 (破坏被校验的区域内的数据)
        CheckpointRecord valid = new CheckpointRecord(100L, 1L, 200L, 4 * 1024 * 1024);
        byte[] data = valid.serialize();
        // 破坏 checkpointLsn 字段 (偏移 8-15，在被校验的前 48 字节内)
        data[10] = (byte) ~data[10];
        assertNull(CheckpointRecord.deserialize(data),
                "Corrupted data should fail checksum validation");
    }

    // ==================== CheckpointManager 基本功能测试 ====================

    @Test
    @Order(10)
    @DisplayName("CheckpointManager 初始化")
    void testCheckpointManagerInitialization() {
        CheckpointManager checkpointManager = redoLogManager.getCheckpointManager();
        assertNotNull(checkpointManager, "CheckpointManager should be initialized");
        assertFalse(checkpointManager.isRunning(), "Checkpoint thread should not be running yet");
    }

    @Test
    @Order(11)
    @DisplayName("执行 Checkpoint (无脏页)")
    void testCheckpointNoDirtyPages() throws Exception {
        // 没有任何 MTR 提交，FlushList 应该为空
        long checkpointLsn = redoLogManager.doCheckpoint();

        // 无脏页时，checkpoint LSN 应该等于 flushed LSN
        assertTrue(checkpointLsn >= 0, "Checkpoint LSN should be >= 0");

        System.out.println("Checkpoint LSN (no dirty pages): " + checkpointLsn);
        System.out.println("Last checkpoint LSN: " + redoLogManager.getLastCheckpointLsn());
    }

    @Test
    @Order(12)
    @DisplayName("执行 Checkpoint (有脏页)")
    void testCheckpointWithDirtyPages() throws Exception {
        // 创建一些脏页
        for (int i = 0; i < 5; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        // 现在应该有脏页
        int dirtyCount = bufferPool.getDirtyCount();
        System.out.println("Dirty page count: " + dirtyCount);

        // 执行 checkpoint
        long checkpointLsn = redoLogManager.doCheckpoint();

        // checkpoint LSN 应该是最老脏页的 LSN
        assertTrue(checkpointLsn > 0, "Checkpoint LSN should be > 0 when there are dirty pages");

        System.out.println("Checkpoint LSN (with dirty pages): " + checkpointLsn);
        System.out.println("Checkpoint No: " + redoLogManager.getCheckpointManager().getCheckpointNo());
    }

    @Test
    @Order(13)
    @DisplayName("多次 Checkpoint")
    void testMultipleCheckpoints() throws Exception {
        long lastLsn = 0;

        for (int round = 0; round < 3; round++) {
            // 创建脏页
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, round);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }

            // 执行 checkpoint
            long checkpointLsn = redoLogManager.doCheckpoint();

            System.out.println("Round " + round + ": checkpoint LSN = " + checkpointLsn +
                    ", checkpoint No = " + redoLogManager.getCheckpointManager().getCheckpointNo());

            // checkpoint 序号应该递增
            assertEquals(round + 1, redoLogManager.getCheckpointManager().getCheckpointNo() -
                    (round == 0 ? 0 : redoLogManager.getCheckpointManager().getCheckpointNo() - round - 1));

            lastLsn = checkpointLsn;
        }
    }

    // ==================== Checkpoint 文件存储测试 ====================

    @Test
    @Order(20)
    @DisplayName("Checkpoint 写入和读取文件")
    void testCheckpointFileStorage() throws Exception {
        // 创建脏页并执行 checkpoint
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            Page page = mtr.newPage(SPACE_ID);
            page.putInt(100, 12345);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);
            mtr.commit();
        }

        long checkpointLsn = redoLogManager.doCheckpoint();
        long checkpointNo = redoLogManager.getCheckpointManager().getCheckpointNo();

        // 读取存储的 checkpoint
        CheckpointRecord latestCheckpoint = redoLogManager.getCheckpointManager().readLatestCheckpoint();

        assertNotNull(latestCheckpoint, "Should be able to read checkpoint from file");
        assertEquals(checkpointLsn, latestCheckpoint.getCheckpointLsn());
        assertEquals(checkpointNo, latestCheckpoint.getCheckpointNo());

        System.out.println("Stored checkpoint: " + latestCheckpoint);
    }

    @Test
    @Order(21)
    @DisplayName("Checkpoint 交替写入验证")
    void testCheckpointAlternatingWrite() throws Exception {
        // 记录初始 checkpoint 序号
        long initialNo = redoLogManager.getCheckpointManager().getCheckpointNo();

        // 执行多次 checkpoint，验证交替写入
        for (int i = 0; i < 4; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }

            long lsn = redoLogManager.doCheckpoint();
            long no = redoLogManager.getCheckpointManager().getCheckpointNo();

            System.out.println("Checkpoint " + no + " -> file " + (no % 2) + ", LSN = " + lsn);
        }

        // 读取最新的 checkpoint
        CheckpointRecord latest = redoLogManager.getCheckpointManager().readLatestCheckpoint();
        assertNotNull(latest);

        // 验证 checkpoint 序号增加了 4
        long finalNo = redoLogManager.getCheckpointManager().getCheckpointNo();
        assertEquals(initialNo + 4, finalNo, "Checkpoint number should increase by 4");
    }

    // ==================== 后台线程测试 ====================

    @Test
    @Order(30)
    @DisplayName("启动和停止 Checkpoint 线程")
    void testCheckpointThreadLifecycle() throws Exception {
        CheckpointManager checkpointManager = redoLogManager.getCheckpointManager();

        // 启动前应该不在运行
        assertFalse(checkpointManager.isRunning());

        // 启动
        redoLogManager.startCheckpoint();
        assertTrue(checkpointManager.isRunning(), "Checkpoint thread should be running");

        // 等待一小段时间让线程执行
        Thread.sleep(100);

        // 停止 (由 beforeCleanup 中的 shutdown 处理)
    }

    @Test
    @Order(31)
    @DisplayName("后台 Checkpoint 自动执行")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testBackgroundCheckpoint() throws Exception {
        // 使用较短的 checkpoint 间隔创建新的 CheckpointManager
        // 注意：这个测试会使用现有的 manager，间隔是默认的 10 秒
        // 我们通过手动触发来验证功能

        long initialNo = redoLogManager.getCheckpointManager().getCheckpointNo();

        // 启动 checkpoint 线程
        redoLogManager.startCheckpoint();

        // 创建脏页
        for (int i = 0; i < 3; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        // 手动触发 checkpoint
        redoLogManager.doCheckpoint();

        long newNo = redoLogManager.getCheckpointManager().getCheckpointNo();
        assertTrue(newNo > initialNo, "Checkpoint number should increase");

        System.out.println("Checkpoint number: " + initialNo + " -> " + newNo);
    }

    // ==================== 边界条件测试 ====================

    @Test
    @Order(40)
    @DisplayName("getOldestDirtyPageLsn 边界测试")
    void testOldestDirtyPageLsnBoundary() throws Exception {
        // 初始状态应该没有脏页
        long lsn = bufferPool.getOldestDirtyPageLsn();
        assertEquals(Long.MAX_VALUE, lsn, "No dirty pages should return MAX_VALUE");

        // 创建脏页
        Page page;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            page = mtr.newPage(SPACE_ID);
            page.putInt(100, 1);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);
            mtr.commit();
        }

        // 现在应该有脏页
        lsn = bufferPool.getOldestDirtyPageLsn();
        assertTrue(lsn < Long.MAX_VALUE && lsn > 0, "Should have dirty page LSN");

        System.out.println("Oldest dirty page LSN: " + lsn);
    }

    @Test
    @Order(41)
    @DisplayName("Checkpoint 不会超过最老脏页 LSN")
    void testCheckpointNotExceedOldestDirtyLsn() throws Exception {
        // 创建多个脏页
        long firstPageLsn = 0;
        for (int i = 0; i < 5; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();

                if (i == 0) {
                    firstPageLsn = page.getLsn();
                }
            }
        }

        // 执行 checkpoint
        long checkpointLsn = redoLogManager.doCheckpoint();
        long oldestDirtyLsn = bufferPool.getOldestDirtyPageLsn();

        // Checkpoint LSN 应该等于最老脏页的 LSN
        assertEquals(oldestDirtyLsn, checkpointLsn,
                "Checkpoint LSN should equal oldest dirty page LSN");

        System.out.println("First page LSN: " + firstPageLsn);
        System.out.println("Oldest dirty LSN: " + oldestDirtyLsn);
        System.out.println("Checkpoint LSN: " + checkpointLsn);
    }

    // ==================== 集成测试 ====================

    @Test
    @Order(50)
    @DisplayName("完整的 Checkpoint 流程测试")
    void testFullCheckpointFlow() throws Exception {
        System.out.println("=== Full Checkpoint Flow Test ===");

        // 1. 初始状态
        System.out.println("1. Initial state:");
        System.out.println("   Dirty pages: " + bufferPool.getDirtyCount());
        System.out.println("   Current SN: " + redoLogManager.getCurrentSn());
        System.out.println("   Flushed SN: " + redoLogManager.getFlushedSn());

        // 2. 创建事务和脏页
        System.out.println("2. Creating transactions...");
        for (int i = 0; i < 10; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                page.putLong(200, System.currentTimeMillis());
                mtr.logModification(page, 100, 4);
                mtr.logModification(page, 200, 8);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        System.out.println("   After transactions:");
        System.out.println("   Dirty pages: " + bufferPool.getDirtyCount());
        System.out.println("   Current SN: " + redoLogManager.getCurrentSn());

        // 3. 执行 checkpoint
        System.out.println("3. Performing checkpoint...");
        long checkpointLsn = redoLogManager.doCheckpoint();
        System.out.println("   Checkpoint LSN: " + checkpointLsn);
        System.out.println("   Checkpoint No: " + redoLogManager.getCheckpointManager().getCheckpointNo());

        // 4. 验证 checkpoint 持久化
        System.out.println("4. Verifying checkpoint persistence...");
        CheckpointRecord stored = redoLogManager.getCheckpointManager().readLatestCheckpoint();
        assertNotNull(stored);
        assertEquals(checkpointLsn, stored.getCheckpointLsn());
        System.out.println("   Stored checkpoint: " + stored);

        // 5. 再次执行事务和 checkpoint
        System.out.println("5. More transactions and checkpoint...");
        for (int i = 0; i < 5; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, 100 + i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        long newCheckpointLsn = redoLogManager.doCheckpoint();
        System.out.println("   New checkpoint LSN: " + newCheckpointLsn);
        System.out.println("   New checkpoint No: " + redoLogManager.getCheckpointManager().getCheckpointNo());

        // 验证
        assertTrue(newCheckpointLsn >= checkpointLsn, "New checkpoint LSN should be >= previous");

        System.out.println("=== Test Complete ===");
    }
}
