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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Undo 压缩混合工作负载集成测试。
 *
 * <p>覆盖场景：可压缩链、短链、超出 purge 边界链混合执行。</p>
 */
@DisplayName("Undo Compression Mixed Workload Integration Test")
class UndoCompressionMixedWorkloadIntegrationTest {

    private UndoLogManager undoLogManager;
    private PurgeCoordinator purgeCoordinator;
    private UndoCompressionManager compressionManager;
    private MiniTransaction mtr;

    @BeforeEach
    void setUp() {
        undoLogManager = mock(UndoLogManager.class);
        purgeCoordinator = mock(PurgeCoordinator.class);
        compressionManager = new UndoCompressionManager(undoLogManager, purgeCoordinator);
        mtr = mock(MiniTransaction.class);

        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(1000));

        AtomicInteger seq = new AtomicInteger(1);
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(invocation -> RollbackPointer.forInsert(
                        0, 100 + seq.getAndIncrement(), 64
                ));
    }

    @Test
    @DisplayName("混合工作负载：可压缩链和不可压缩链混合")
    void testMixedWorkloadBatchCompression() {
        List<UndoCompressionManager.CompressionTask> tasks = new ArrayList<>();

        // 可压缩链：长度 4，trx_id 全部 < purge_limit
        tasks.add(createTask(1, 10, 100, 4, 3, 32));

        // 不可压缩：短链（长度 1）
        tasks.add(createTask(2, 10, 200, 1, 1, 16));

        // 不可压缩：trx_id 超出 purge_limit
        tasks.add(createTask(3, 10, 1200, 3, 2, 16));

        // 可压缩链：长度 5
        tasks.add(createTask(4, 10, 300, 5, 4, 48));

        List<UndoCompressionManager.CompressionResult> results =
                compressionManager.batchCompress(tasks, mtr);

        long successCount = results.stream().filter(UndoCompressionManager.CompressionResult::isSuccessful).count();
        long failedCount = results.size() - successCount;

        assertEquals(2, successCount, "应有 2 条可压缩链压缩成功");
        assertEquals(2, failedCount, "应有 2 条链因边界条件失败");

        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(2, stats.successfulCompressions);
        assertEquals(2, stats.failedCompressions);
        assertTrue(stats.totalSpaceSavings > 0, "混合负载下应产生正向空间节省");
    }

    private UndoCompressionManager.CompressionTask createTask(int pkSeed,
                                                              int tableId,
                                                              int trxStart,
                                                              int chainLength,
                                                              int columnCount,
                                                              int valueLen) {
        byte[] pk = new byte[]{(byte) pkSeed, (byte) (pkSeed + 1), (byte) (pkSeed + 2)};
        List<UpdateUndoRecord> chain = createUndoChain(pk, tableId, trxStart, chainLength, columnCount, valueLen);
        RollbackPointer currentRollPtr = RollbackPointer.forInsert(0, 20, 1000 + pkSeed);
        return new UndoCompressionManager.CompressionTask(pk, tableId, chain, currentRollPtr);
    }

    private List<UpdateUndoRecord> createUndoChain(byte[] pk,
                                                   int tableId,
                                                   int trxStart,
                                                   int chainLength,
                                                   int columnCount,
                                                   int valueLen) {
        List<UpdateUndoRecord> chain = new ArrayList<>();

        // 按“从新到旧”构造，满足 pruner 期望
        for (int i = chainLength; i >= 1; i--) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int c = 1; c <= columnCount; c++) {
                byte[] oldVal = new byte[valueLen];
                oldVal[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(c, oldVal));
            }

            RollbackPointer prevPtr = i > 1
                    ? RollbackPointer.forInsert(0, 30 + i - 1, 200 + i - 1)
                    : RollbackPointer.NULL;

            UpdateUndoRecord undo = new UpdateUndoRecord(
                    new TransactionId(trxStart + i),
                    tableId,
                    prevPtr,
                    pk,
                    cols
            );
            chain.add(undo);
        }

        return chain;
    }
}
