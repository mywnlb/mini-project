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
 * Undo 压缩性能基准测试。
 *
 * <p>说明：该类用于建立压缩吞吐和空间节省的基线指标。</p>
 */
@DisplayName("Undo Compression Performance Benchmark")
class UndoCompressionPerformanceBenchmarkTest {

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

        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(10_000));

        AtomicInteger seq = new AtomicInteger(1);
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(invocation -> RollbackPointer.forInsert(
                        0, 1000 + seq.getAndIncrement(), 128
                ));
    }

    @Test
    @DisplayName("基准：批量压缩吞吐量")
    void benchmarkBatchCompressionThroughput() {
        int tasks = 500;
        int chainLength = 6;
        List<UndoCompressionManager.CompressionTask> work = buildWorkload(tasks, chainLength, 64);

        long startNs = System.nanoTime();
        List<UndoCompressionManager.CompressionResult> results = compressionManager.batchCompress(work, mtr);
        long durationNs = System.nanoTime() - startNs;

        long success = results.stream().filter(UndoCompressionManager.CompressionResult::isSuccessful).count();
        double durationMs = durationNs / 1_000_000.0;
        double throughput = (success * 1000.0) / Math.max(durationMs, 1.0);

        System.out.printf("Undo compression throughput: tasks=%d, success=%d, duration=%.2fms, throughput=%.2f ops/s%n",
                tasks, success, durationMs, throughput);

        assertEquals(tasks, success, "该基准负载下应全部压缩成功");
        assertTrue(durationMs < 5000, "500 条链压缩应在 5 秒内完成");
    }

    @Test
    @DisplayName("基准：空间节省效果")
    void benchmarkSpaceSavings() {
        List<UndoCompressionManager.CompressionTask> work = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int chainLength = 2 + (i % 8);
            int valueLen = 32 + (i % 3) * 32;
            work.add(createTask(i + 1, 20, 100 + i * 20, chainLength, 4, valueLen));
        }

        List<UndoCompressionManager.CompressionResult> results = compressionManager.batchCompress(work, mtr);
        long success = results.stream().filter(UndoCompressionManager.CompressionResult::isSuccessful).count();
        int totalSavings = results.stream().mapToInt(UndoCompressionManager.CompressionResult::getSpaceSavings).sum();

        double avgSavings = success == 0 ? 0 : (double) totalSavings / success;
        System.out.printf("Undo compression savings: success=%d, total=%dB, avg=%.2fB%n",
                success, totalSavings, avgSavings);

        assertTrue(success > 0, "应至少有一部分链压缩成功");
        assertTrue(totalSavings > 0, "总空间节省应大于 0");
    }

    private List<UndoCompressionManager.CompressionTask> buildWorkload(int tasks,
                                                                       int chainLength,
                                                                       int valueLen) {
        List<UndoCompressionManager.CompressionTask> work = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            work.add(createTask(i + 1, 10, 100 + i * 10, chainLength, 3, valueLen));
        }
        return work;
    }

    private UndoCompressionManager.CompressionTask createTask(int pkSeed,
                                                              int tableId,
                                                              int trxStart,
                                                              int chainLength,
                                                              int columnCount,
                                                              int valueLen) {
        byte[] pk = new byte[]{(byte) pkSeed, (byte) (pkSeed + 1), (byte) (pkSeed + 2)};
        List<UpdateUndoRecord> chain = createUndoChain(pk, tableId, trxStart, chainLength, columnCount, valueLen);
        RollbackPointer currentRollPtr = RollbackPointer.forInsert(0, 50, 5000 + pkSeed);
        return new UndoCompressionManager.CompressionTask(pk, tableId, chain, currentRollPtr);
    }

    private List<UpdateUndoRecord> createUndoChain(byte[] pk,
                                                   int tableId,
                                                   int trxStart,
                                                   int chainLength,
                                                   int columnCount,
                                                   int valueLen) {
        List<UpdateUndoRecord> chain = new ArrayList<>();

        // 从新到旧
        for (int i = chainLength; i >= 1; i--) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int c = 1; c <= columnCount; c++) {
                byte[] oldVal = new byte[valueLen];
                oldVal[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(c, oldVal));
            }

            RollbackPointer prevPtr = i > 1
                    ? RollbackPointer.forInsert(0, 60 + i - 1, 300 + i - 1)
                    : RollbackPointer.NULL;

            chain.add(new UpdateUndoRecord(
                    new TransactionId(trxStart + i),
                    tableId,
                    prevPtr,
                    pk,
                    cols
            ));
        }
        return chain;
    }
}

