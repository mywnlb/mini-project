package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Undo 压缩故障恢复测试
 *
 * <p>验证压缩中断和异常场景下的安全性：
 * <ul>
 *   <li>appendMergedUndo 失败时原链不受影响</li>
 *   <li>ABA 冲突检测正确拦截</li>
 *   <li>异常传播正确且不破坏统计</li>
 *   <li>部分批量失败不影响其他任务</li>
 * </ul>
 * </p>
 */
@DisplayName("Undo Compression Failure & Recovery Tests")
class UndoCompressionFailureRecoveryTest {

    private static final int TABLE_ID = 1;

    private UndoLogManager undoLogManager;
    private PurgeCoordinator purgeCoordinator;
    private UndoCompressionManager compressionManager;
    private MiniTransaction mtr;

    @BeforeEach
    void setUp() throws Exception {
        undoLogManager = mock(UndoLogManager.class);
        purgeCoordinator = mock(PurgeCoordinator.class);
        compressionManager = new UndoCompressionManager(undoLogManager, purgeCoordinator);
        mtr = mock(MiniTransaction.class);

        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(100_000));
    }

    // ==================== 3.3 故障恢复测试 ====================

    @Test
    @DisplayName("故障：appendMergedUndo 返回 null 时压缩失败但不破坏状态")
    void testAppendMergedUndoReturnsNull() throws Exception {
        // Mock: appendMergedUndo 返回 null（模拟写入失败）
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenReturn(null);

        byte[] pk = new byte[]{0x01, 0x02};
        List<UpdateUndoRecord> chain = buildChain(pk, 5, 3, 32);
        RollbackPointer originalPtr = RollbackPointer.forInsert(0, 10, 100);

        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                pk, TABLE_ID, chain, originalPtr, mtr);

        assertFalse(result.isSuccessful(), "写入失败时压缩应标记为失败");
        assertNotNull(result.getFailureReason(), "应包含失败原因");

        // 统计应正确反映失败
        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(0, stats.successfulCompressions);
        assertTrue(stats.failedCompressions > 0, "失败数应 > 0");
    }

    @Test
    @DisplayName("故障：appendMergedUndo 抛异常时安全降级")
    void testAppendMergedUndoThrowsException() throws Exception {
        // Mock: appendMergedUndo 抛异常
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenThrow(new RuntimeException("Simulated I/O error"));

        byte[] pk = new byte[]{0x01, 0x02};
        List<UpdateUndoRecord> chain = buildChain(pk, 5, 3, 32);
        RollbackPointer originalPtr = RollbackPointer.forInsert(0, 10, 100);

        // 不应抛出异常到调用者
        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                pk, TABLE_ID, chain, originalPtr, mtr);

        assertFalse(result.isSuccessful(), "异常时压缩应标记为失败");

        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(0, stats.successfulCompressions);
        assertTrue(stats.failedCompressions > 0);
    }

    @Test
    @DisplayName("故障：批量压缩中部分失败不影响其他任务")
    void testBatchPartialFailure() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        // Mock: 偶数次调用成功，奇数次调用返回 null（模拟间歇性失败）
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(invocation -> {
                    int count = callCount.getAndIncrement();
                    if (count % 2 == 0) {
                        return RollbackPointer.forInsert(0, 2000 + count, 128);
                    } else {
                        return null; // 模拟失败
                    }
                });

        int taskCount = 10;
        List<UndoCompressionManager.CompressionTask> tasks = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            byte[] pk = new byte[]{(byte) i};
            List<UpdateUndoRecord> chain = buildChain(pk, 5, 2, 32);
            RollbackPointer ptr = RollbackPointer.forInsert(0, 50 + i, 100);
            tasks.add(new UndoCompressionManager.CompressionTask(pk, TABLE_ID, chain, ptr));
        }

        List<UndoCompressionManager.CompressionResult> results =
                compressionManager.batchCompress(tasks, mtr);

        assertEquals(taskCount, results.size(), "结果数应等于任务数");

        long successCount = results.stream()
                .filter(UndoCompressionManager.CompressionResult::isSuccessful).count();
        long failedCount = results.size() - successCount;

        assertTrue(successCount > 0, "应有部分成功");
        assertTrue(failedCount > 0, "应有部分失败");

        System.out.printf("批量部分失败: total=%d, success=%d, failed=%d%n",
                taskCount, successCount, failedCount);

        // 统计一致
        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(successCount, stats.successfulCompressions);
        assertEquals(failedCount, stats.failedCompressions);
    }

    @Test
    @DisplayName("故障：连续失败后成功恢复")
    void testRecoveryAfterConsecutiveFailures() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        // 前 3 次失败，后面成功
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(invocation -> {
                    int count = callCount.getAndIncrement();
                    if (count < 3) {
                        throw new RuntimeException("Simulated failure #" + count);
                    }
                    return RollbackPointer.forInsert(0, 3000 + count, 128);
                });

        byte[] pk = new byte[]{0x01};

        // 前 3 次应失败
        for (int i = 0; i < 3; i++) {
            List<UpdateUndoRecord> chain = buildChain(pk, 5, 2, 32);
            RollbackPointer ptr = RollbackPointer.forInsert(0, 50 + i, 100);
            UndoCompressionManager.CompressionResult result =
                    compressionManager.compressUndoChain(pk, TABLE_ID, chain, ptr, mtr);
            assertFalse(result.isSuccessful(), "第 " + (i + 1) + " 次应失败");
        }

        // 第 4 次应成功
        List<UpdateUndoRecord> chain = buildChain(pk, 5, 2, 32);
        RollbackPointer ptr = RollbackPointer.forInsert(0, 53, 100);
        UndoCompressionManager.CompressionResult result =
                compressionManager.compressUndoChain(pk, TABLE_ID, chain, ptr, mtr);

        assertTrue(result.isSuccessful(), "恢复后应成功");

        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(1, stats.successfulCompressions, "应有 1 次成功");
        assertEquals(3, stats.failedCompressions, "应有 3 次失败");
    }

    @Test
    @DisplayName("故障：purge limit 变更后的安全性")
    void testPurgeLimitChangesDuringCompression() throws Exception {
        AtomicInteger seq = new AtomicInteger(1);
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(inv -> RollbackPointer.forInsert(0, 4000 + seq.getAndIncrement(), 128));

        byte[] pk = new byte[]{0x01};

        // 第一次压缩: purge limit = 100_000，链 trx_id 范围 [1..5]，应成功
        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(100_000));
        List<UpdateUndoRecord> chain1 = buildChain(pk, 5, 2, 32);
        RollbackPointer ptr1 = RollbackPointer.forInsert(0, 60, 100);
        UndoCompressionManager.CompressionResult result1 =
                compressionManager.compressUndoChain(pk, TABLE_ID, chain1, ptr1, mtr);
        assertTrue(result1.isSuccessful(), "高 purge limit 应成功");

        // 第二次压缩: purge limit 降低到 2，链 trx_id [1..5] 中部分超出，应失败
        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(2));
        List<UpdateUndoRecord> chain2 = buildChain(pk, 5, 2, 32);
        RollbackPointer ptr2 = RollbackPointer.forInsert(0, 61, 100);
        UndoCompressionManager.CompressionResult result2 =
                compressionManager.compressUndoChain(pk, TABLE_ID, chain2, ptr2, mtr);
        assertFalse(result2.isSuccessful(), "低 purge limit 应导致失败");

        // 第三次压缩: purge limit 恢复高位，应成功
        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(200_000));
        List<UpdateUndoRecord> chain3 = buildChain(pk, 5, 2, 32);
        RollbackPointer ptr3 = RollbackPointer.forInsert(0, 62, 100);
        UndoCompressionManager.CompressionResult result3 =
                compressionManager.compressUndoChain(pk, TABLE_ID, chain3, ptr3, mtr);
        assertTrue(result3.isSuccessful(), "恢复后应成功");

        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(2, stats.successfulCompressions);
        assertEquals(1, stats.failedCompressions);
    }

    @Test
    @DisplayName("故障：空主键的链压缩")
    void testEmptyPrimaryKeyChain() {
        byte[] emptyPk = new byte[0];
        List<UpdateUndoRecord> chain = buildChain(emptyPk, 5, 2, 32);
        RollbackPointer ptr = RollbackPointer.forInsert(0, 10, 100);

        // 不应抛异常
        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                emptyPk, TABLE_ID, chain, ptr, mtr);

        // 结果不论成功或失败都不应抛异常
        assertNotNull(result, "应返回结果");
    }

    // ==================== 辅助方法 ====================

    private List<UpdateUndoRecord> buildChain(byte[] pk, int chainLength,
                                               int columnCount, int valueLen) {
        List<UpdateUndoRecord> chain = new ArrayList<>();
        for (int i = chainLength; i >= 1; i--) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                byte[] val = new byte[valueLen];
                val[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(c, val));
            }
            RollbackPointer prevPtr = i > 1
                    ? RollbackPointer.forInsert(0, 60 + i, 300 + i)
                    : RollbackPointer.NULL;
            chain.add(new UpdateUndoRecord(
                    new TransactionId(i), TABLE_ID, prevPtr, pk, cols));
        }
        return chain;
    }
}
