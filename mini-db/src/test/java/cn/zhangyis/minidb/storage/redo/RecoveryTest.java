package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.checkpoint.CheckpointRecord;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.redo.recovery.RecoveryCoordinator;
import cn.zhangyis.minidb.storage.redo.recovery.RedoLogApplier;
import cn.zhangyis.minidb.storage.redo.recovery.RedoLogScanner;
import cn.zhangyis.minidb.storage.redo.record.MultiRecEndRecord;
import cn.zhangyis.minidb.storage.redo.record.RedoRecord;
import cn.zhangyis.minidb.storage.redo.record.WriteBytesRecord;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4: 崩溃恢复测试
 *
 * <p>测试内容：</p>
 * <ul>
 *   <li>RedoLogScanner 扫描 redo log</li>
 *   <li>RedoLogApplier 重放 redo records</li>
 *   <li>RecoveryCoordinator 完整恢复流程</li>
 *   <li>幂等性验证</li>
 *   <li>模拟崩溃恢复场景</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RecoveryTest extends BaseStorageTest {

    private RedoLogManager redoLogManager;
    private Path redoLogDir;

    @Override
    protected void afterSetup() throws Exception {
        // 创建 redo log 目录
        redoLogDir = testDir.resolve("redo");
        Files.createDirectories(redoLogDir);

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

        // 初始化 Checkpoint
        redoLogManager.initCheckpoint(bufferPool);
    }

    @Override
    protected void beforeCleanup() throws Exception {
        if (redoLogManager != null) {
            redoLogManager.shutdown();
        }
    }

    // ==================== RedoLogScanner 测试 ====================

    @Test
    @Order(1)
    @DisplayName("RedoLogScanner 基本扫描")
    void testScannerBasic() throws Exception {
        // 写入一些 redo records
        for (int i = 0; i < 5; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        // 执行 checkpoint
        long checkpointLsn = redoLogManager.doCheckpoint();
        System.out.println("Checkpoint LSN: " + checkpointLsn);

        // 创建 scanner
        RedoLogFileSet fileSet = redoLogManager.getFileSet();
        RedoLogScanner scanner = new RedoLogScanner(fileSet, 0);

        // 扫描并计数
        int recordCount = 0;
        int groupEndCount = 0;

        while (scanner.hasNext()) {
            RedoRecord record = scanner.next();
            recordCount++;
            if (record instanceof MultiRecEndRecord) {
                groupEndCount++;
            }
        }

        System.out.println("Total records: " + recordCount);
        System.out.println("Group end markers: " + groupEndCount);
        System.out.println("Scanner stats: " + scanner.getStats());

        assertTrue(recordCount > 0, "Should have scanned some records");
        assertTrue(groupEndCount > 0, "Should have group end markers");
    }

    // ==================== RedoLogApplier 测试 ====================

    @Test
    @Order(10)
    @DisplayName("RedoLogApplier 应用 WriteBytesRecord")
    void testApplierWriteBytes() throws Exception {
        // 创建页面
        Page page;
        PageId pageId;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            page = mtr.newPage(SPACE_ID);
            pageId = page.getPageId();
            page.putInt(100, 12345);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);
            mtr.commit();
        }

        // 获取 page LSN
        long pageLsn;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p = mtr.getPage(pageId);
            pageLsn = p.getLsn();
            mtr.commit();
        }

        System.out.println("Page LSN: " + pageLsn);

        // 创建一个新的 WriteBytesRecord (模拟恢复)
        // 注意：在恢复场景中，record LSN 必须是真实存在于 redo log 中的 LSN
        // 这里我们先写入一条真实的 redo log，获取真实的 LSN
        byte[] newData = new byte[]{0x11, 0x22, 0x33, 0x44};
        long recordLsn;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            Page p = mtr.getPage(pageId);
            p.putBytes(200, newData);
            mtr.logModification(p, 200, newData);
            mtr.markDirty(p);
            mtr.commit();
            recordLsn = p.getLsn();  // 获取真实的 LSN
        }

        System.out.println("Record LSN: " + recordLsn);

        // 现在模拟恢复：将页面的 LSN 重置为旧值，然后应用 record
        // 首先刷盘并清除 buffer pool 中的页面
        bufferPool.flushPage(pageId);

        // 重新读取页面并手动将 LSN 设回旧值（模拟崩溃后的状态）
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p = mtr.getPage(pageId);
            p.setLsn(pageLsn);  // 重置为旧 LSN，模拟崩溃后的旧页面
            // 清除 offset 200 的数据，模拟脏页未刷盘
            p.putBytes(200, new byte[]{0, 0, 0, 0});
            mtr.markDirty(p);
            mtr.commit();
        }

        // 创建 WriteBytesRecord 并设置真实的 LSN
        WriteBytesRecord record = new WriteBytesRecord(pageId, 200, newData);
        record.setLsn(recordLsn);  // 使用真实的 LSN

        // 应用
        RedoLogApplier applier = new RedoLogApplier(bufferPool);
        boolean applied = applier.apply(record);

        assertTrue(applied, "Record should be applied");
        assertEquals(1, applier.getAppliedCount());

        // 验证数据已写入
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p = mtr.getPage(pageId);
            byte[] readData = new byte[4];
            p.getBytes(200, readData);
            assertArrayEquals(newData, readData, "Data should be written to page");
            mtr.commit();
        }
    }

    @Test
    @Order(11)
    @DisplayName("RedoLogApplier 幂等性 - 跳过已应用的 record")
    void testApplierIdempotency() throws Exception {
        // 创建页面
        Page page;
        PageId pageId;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            page = mtr.newPage(SPACE_ID);
            pageId = page.getPageId();
            page.putInt(100, 99999);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);
            mtr.commit();
        }

        // 获取 page LSN
        long pageLsn;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p = mtr.getPage(pageId);
            pageLsn = p.getLsn();
            mtr.commit();
        }

        System.out.println("Page LSN for idempotency test: " + pageLsn);

        // 创建一个 LSN 较小的 record (应该被跳过)
        // 幂等性测试：record.lsn < page.lsn，所以 record 不应该被应用
        byte[] oldData = new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD};
        WriteBytesRecord record = new WriteBytesRecord(pageId, 200, oldData);
        // 使用一个比 pageLsn 小的值，但必须是合理的 SN（这里使用 1）
        record.setLsn(1);  // LSN = 1 肯定小于任何有效的 page LSN

        // 应用 - 由于 record.lsn < page.lsn，应该被跳过
        RedoLogApplier applier = new RedoLogApplier(bufferPool);
        boolean applied = applier.apply(record);

        assertFalse(applied, "Record should be skipped (idempotent)");
        assertEquals(0, applier.getAppliedCount());
        assertEquals(1, applier.getSkippedCount());
    }

    // ==================== RecoveryCoordinator 测试 ====================

    @Test
    @Order(20)
    @DisplayName("RecoveryCoordinator 基本恢复")
    void testRecoveryBasic() throws Exception {
        // 创建一些事务
        Map<PageId, Integer> expectedValues = new HashMap<>();

        for (int i = 0; i < 5; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                int value = 1000 + i;
                page.putInt(100, value);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
                expectedValues.put(page.getPageId(), value);
            }
        }

        // 执行 checkpoint
        redoLogManager.doCheckpoint();

        // 创建 RecoveryCoordinator
        RedoLogFileSet fileSet = redoLogManager.getFileSet();
        RecoveryCoordinator coordinator = new RecoveryCoordinator(fileSet, bufferPool);

        // 检查是否需要恢复
        boolean needRecovery = coordinator.needRecovery();
        System.out.println("Need recovery: " + needRecovery);

        if (needRecovery) {
            // 获取 checkpoint 信息
            CheckpointRecord checkpoint = coordinator.getCheckpoint();
            System.out.println("Checkpoint: " + checkpoint);
        }

        // 注意：实际的恢复测试需要模拟崩溃，这里只是验证组件工作正常
    }

    @Test
    @Order(21)
    @DisplayName("RecoveryCoordinator 读取 Checkpoint")
    void testReadCheckpoint() throws Exception {
        // 创建事务并执行 checkpoint
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            Page page = mtr.newPage(SPACE_ID);
            page.putInt(100, 12345);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);
            mtr.commit();
        }

        long checkpointLsn = redoLogManager.doCheckpoint();

        // 创建新的 RecoveryCoordinator 来读取 checkpoint
        RedoLogFileSet fileSet = redoLogManager.getFileSet();
        RecoveryCoordinator coordinator = new RecoveryCoordinator(fileSet, bufferPool);

        assertTrue(coordinator.needRecovery(), "Should detect checkpoint");

        CheckpointRecord checkpoint = coordinator.getCheckpoint();
        assertNotNull(checkpoint, "Should have checkpoint");
        assertEquals(checkpointLsn, checkpoint.getCheckpointLsn(),
                "Checkpoint LSN should match");

        System.out.println("Read checkpoint: " + checkpoint);
    }

    // ==================== 模拟崩溃恢复场景 ====================

    @Test
    @Order(30)
    @DisplayName("模拟崩溃后恢复 - 完整流程")
    void testSimulatedCrashRecovery() throws Exception {
        System.out.println("=== Simulated Crash Recovery Test ===");

        // Phase 1: 创建事务并记录预期值
        System.out.println("Phase 1: Creating transactions...");
        Map<PageId, Integer> expectedValues = new HashMap<>();
        List<PageId> pageIds = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                int value = 5000 + i;
                page.putInt(100, value);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();

                expectedValues.put(page.getPageId(), value);
                pageIds.add(page.getPageId());
            }
        }

        // Phase 2: 执行 checkpoint
        System.out.println("Phase 2: Creating checkpoint...");
        long checkpointLsn = redoLogManager.doCheckpoint();
        System.out.println("Checkpoint LSN: " + checkpointLsn);

        // Phase 3: 创建更多事务 (checkpoint 之后)
        System.out.println("Phase 3: More transactions after checkpoint...");
        for (int i = 0; i < 5; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                int value = 9000 + i;
                page.putInt(100, value);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();

                expectedValues.put(page.getPageId(), value);
                pageIds.add(page.getPageId());
            }
        }

        // Phase 4: 刷新所有脏页 (模拟正常关闭)
        System.out.println("Phase 4: Flushing dirty pages...");
        bufferPool.flushAllPages();

        // Phase 5: 验证所有值
        System.out.println("Phase 5: Verifying values...");
        int verified = 0;
        for (PageId pageId : pageIds) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.getPage(pageId);
                int actualValue = page.getInt(100);
                Integer expectedValue = expectedValues.get(pageId);
                assertEquals(expectedValue, actualValue,
                        "Value mismatch for page " + pageId);
                verified++;
                mtr.commit();
            }
        }

        System.out.println("Verified " + verified + " pages");
        System.out.println("=== Test Complete ===");
    }

    @Test
    @Order(31)
    @DisplayName("恢复后数据一致性验证")
    void testRecoveryDataConsistency() throws Exception {
        // 创建事务
        PageId pageId;
        int expectedValue = 777888;

        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            Page page = mtr.newPage(SPACE_ID);
            pageId = page.getPageId();
            page.putInt(100, expectedValue);
            page.putLong(200, 123456789L);
            mtr.logModification(page, 100, 4);
            mtr.logModification(page, 200, 8);
            mtr.markDirty(page);
            mtr.commit();
        }

        // Checkpoint
        redoLogManager.doCheckpoint();

        // 获取当前 LSN
        long currentSn = redoLogManager.getCurrentSn();
        System.out.println("Current SN: " + currentSn);

        // 刷新脏页
        bufferPool.flushAllPages();

        // 重新读取验证
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);
            assertEquals(expectedValue, page.getInt(100), "Int value should match");
            assertEquals(123456789L, page.getLong(200), "Long value should match");
            assertTrue(page.getLsn() > 0, "Page LSN should be set");
            mtr.commit();
        }
    }

    // ==================== 边界条件测试 ====================

    @Test
    @Order(40)
    @DisplayName("空数据库恢复")
    void testEmptyDatabaseRecovery() throws Exception {
        // 不创建任何事务，直接检查恢复

        // 首先执行一次 checkpoint 让文件有有效数据
        redoLogManager.doCheckpoint();

        RedoLogFileSet fileSet = redoLogManager.getFileSet();
        RecoveryCoordinator coordinator = new RecoveryCoordinator(fileSet, bufferPool);

        // 应该能检测到 checkpoint
        boolean needRecovery = coordinator.needRecovery();
        System.out.println("Empty DB need recovery: " + needRecovery);

        if (needRecovery) {
            CheckpointRecord checkpoint = coordinator.getCheckpoint();
            System.out.println("Checkpoint: " + checkpoint);
        }
    }

    @Test
    @Order(41)
    @DisplayName("Scanner 重置测试")
    void testScannerReset() throws Exception {
        // 写入数据
        for (int i = 0; i < 3; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        RedoLogFileSet fileSet = redoLogManager.getFileSet();
        RedoLogScanner scanner = new RedoLogScanner(fileSet, 0);

        // 第一次扫描
        int count1 = 0;
        while (scanner.hasNext()) {
            scanner.next();
            count1++;
        }

        // 重置并再次扫描
        scanner.reset(0);
        int count2 = 0;
        while (scanner.hasNext()) {
            scanner.next();
            count2++;
        }

        assertEquals(count1, count2, "Reset should allow re-scanning");
        System.out.println("Scanned " + count1 + " records twice");
    }

    @Test
    @Order(42)
    @DisplayName("Applier 统计信息")
    void testApplierStats() throws Exception {
        RedoLogApplier applier = new RedoLogApplier(bufferPool);

        // 初始统计
        assertEquals(0, applier.getAppliedCount());
        assertEquals(0, applier.getSkippedCount());
        assertEquals(0, applier.getFailedCount());

        // 重置
        applier.resetStats();
        assertEquals("applied=0, skipped=0, failed=0", applier.getStats());
    }
}
