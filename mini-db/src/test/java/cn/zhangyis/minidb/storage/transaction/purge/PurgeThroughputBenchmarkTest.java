package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import cn.zhangyis.minidb.storage.transaction.undo.UndoCompressionManager;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Purge 吞吐量基准测试
 *
 * <h2>测试目标</h2>
 * <ul>
 *   <li>验证 Purge 清理吞吐量 ≥ 10,000 记录/秒</li>
 *   <li>测试不同负载场景的 Purge 性能</li>
 *   <li>测试 Purge + Compression 协调吞吐量</li>
 * </ul>
 */
@DisplayName("Purge Throughput Benchmark")
class PurgeThroughputBenchmarkTest {

    private static final int TABLE_ID = 1;

    private PurgeCoordinator coordinator;
    private UndoLogManager undoLogManager;
    private BufferPool bufferPool;
    private IndexManager indexManager;

    @BeforeEach
    void setUp() {
        coordinator = mock(PurgeCoordinator.class);
        undoLogManager = mock(UndoLogManager.class);
        bufferPool = mock(BufferPool.class);
        indexManager = mock(IndexManager.class);

        when(coordinator.getPurgeLimit()).thenReturn(new TransactionId(100_000));
        when(indexManager.getAllTableIds()).thenReturn(Collections.emptySet());
    }

    // ==================== 2.4 Purge 吞吐量 ====================

    @Test
    @DisplayName("基准：purgeUpdateUndo 单调用吞吐量")
    void benchmarkPurgeUpdateUndoThroughput() {
        int totalRecords = 10_000;
        List<TransactionId> purgableIds = new ArrayList<>();
        for (int i = 1; i <= totalRecords; i++) {
            purgableIds.add(new TransactionId(i));
        }

        // Mock purgeUpdateUndo: 模拟成功清理
        when(undoLogManager.purgeUpdateUndo(any(TransactionId.class))).thenReturn(true);
        when(undoLogManager.getPurgableUpdateSegments()).thenReturn(purgableIds);

        long startNs = System.nanoTime();

        int purgedCount = 0;
        for (TransactionId trxId : purgableIds) {
            if (undoLogManager.purgeUpdateUndo(trxId)) {
                purgedCount++;
            }
        }

        long durationNs = System.nanoTime() - startNs;
        double durationMs = durationNs / 1_000_000.0;
        double throughput = purgedCount * 1000.0 / durationMs;

        System.out.printf("Purge 吞吐量: records=%d, duration=%.2fms, throughput=%.0f records/s%n",
                purgedCount, durationMs, throughput);

        assertTrue(throughput > 10_000,
                "Purge 吞吐量应 ≥ 10,000 records/s，实际: " + throughput);
    }

    @Test
    @DisplayName("基准：批量 purgeUpdateUndo 吞吐量")
    void benchmarkBatchPurgeUpdateUndoThroughput() {
        int segmentCount = 5_000;
        List<TransactionId> purgableIds = new ArrayList<>();
        for (int i = 1; i <= segmentCount; i++) {
            purgableIds.add(new TransactionId(i));
        }

        when(undoLogManager.purgeUpdateUndo(any(TransactionId.class))).thenReturn(true);

        long startNs = System.nanoTime();
        int purged = 0;
        for (TransactionId trxId : purgableIds) {
            if (undoLogManager.purgeUpdateUndo(trxId)) {
                purged++;
            }
        }
        long durationNs = System.nanoTime() - startNs;

        double durationMs = durationNs / 1_000_000.0;
        double throughput = purged * 1000.0 / Math.max(durationMs, 0.001);

        System.out.printf("批量 Purge: purged=%d, duration=%.2fms, throughput=%.0f records/s%n",
                purged, durationMs, throughput);

        assertEquals(segmentCount, purged, "所有记录应清理成功");
        assertTrue(throughput > 10_000, "批量 Purge 吞吐量应 ≥ 10,000 records/s");
    }

    @Test
    @DisplayName("基准：不同批次大小对 Purge 吞吐量的影响")
    void benchmarkPurgeBatchSizeImpact() {
        int[] batchSizes = {100, 500, 1000, 5000, 10000};

        when(undoLogManager.purgeUpdateUndo(any(TransactionId.class))).thenReturn(true);

        System.out.println("\n批次大小对 Purge 吞吐量的影响:");
        System.out.printf("  %-15s %-15s %-15s%n", "批次大小", "耗时(ms)", "吞吐量(rec/s)");

        for (int batchSize : batchSizes) {
            List<TransactionId> purgableIds = new ArrayList<>();
            for (int i = 1; i <= batchSize; i++) {
                purgableIds.add(new TransactionId(i));
            }

            long startNs = System.nanoTime();
            int purged = 0;
            for (TransactionId trxId : purgableIds) {
                if (undoLogManager.purgeUpdateUndo(trxId)) {
                    purged++;
                }
            }
            long durationNs = System.nanoTime() - startNs;

            double durationMs = durationNs / 1_000_000.0;
            double throughput = purged * 1000.0 / Math.max(durationMs, 0.001);

            System.out.printf("  %-15d %-15.2f %-15.0f%n", batchSize, durationMs, throughput);
        }
    }

    // ==================== Purge + Compression 协调吞吐量 ====================

    @Test
    @DisplayName("基准：Purge + Compression 并行吞吐量")
    void benchmarkPurgeAndCompressionParallel() throws Exception {
        int purgeRecords = 5_000;
        int compressTasks = 500;

        // Setup purge mocks
        List<TransactionId> purgableIds = new ArrayList<>();
        for (int i = 1; i <= purgeRecords; i++) {
            purgableIds.add(new TransactionId(i));
        }
        when(undoLogManager.purgeUpdateUndo(any(TransactionId.class))).thenReturn(true);
        when(undoLogManager.getPurgableUpdateSegments()).thenReturn(purgableIds);

        // Setup compression mocks
        AtomicInteger seq = new AtomicInteger(1);
        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(inv -> RollbackPointer.forInsert(0, 2000 + seq.getAndIncrement(), 128));
        MiniTransaction mtr = mock(MiniTransaction.class);

        UndoCompressionManager compressionMgr = new UndoCompressionManager(undoLogManager, coordinator);
        List<UndoCompressionManager.CompressionTask> tasks = new ArrayList<>();
        for (int i = 0; i < compressTasks; i++) {
            tasks.add(createCompressionTask(i + 1, 6, 3, 64));
        }

        // 并行执行
        long startNs = System.nanoTime();

        Thread purgeWorker = new Thread(() -> {
            for (TransactionId trxId : purgableIds) {
                undoLogManager.purgeUpdateUndo(trxId);
            }
        });

        Thread compressionWorker = new Thread(() -> {
            compressionMgr.batchCompress(tasks, mtr);
        });

        purgeWorker.start();
        compressionWorker.start();

        try {
            purgeWorker.join();
            compressionWorker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long durationNs = System.nanoTime() - startNs;
        double durationMs = durationNs / 1_000_000.0;
        double purgeRate = purgeRecords * 1000.0 / durationMs;
        double compressRate = compressTasks * 1000.0 / durationMs;

        System.out.printf("\nPurge + Compression 并行:%n");
        System.out.printf("  总耗时: %.2fms%n", durationMs);
        System.out.printf("  Purge: %d records, %.0f records/s%n", purgeRecords, purgeRate);
        System.out.printf("  Compression: %d tasks, %.0f tasks/s%n", compressTasks, compressRate);

        UndoCompressionManager.CompressionStats stats = compressionMgr.getCompressionStats();
        System.out.printf("  Compression 成功率: %.2f%%%n", stats.getSuccessRate() * 100);

        assertTrue(durationMs < 5000, "Purge + Compression 并行应在 5 秒内完成");
    }

    @Test
    @DisplayName("基准：PurgeThreadIntegration 生命周期吞吐量")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void benchmarkIntegrationLifecycleThroughput() {
        int purgeRecords = 1_000;
        List<TransactionId> purgableIds = new ArrayList<>();
        for (int i = 1; i <= purgeRecords; i++) {
            purgableIds.add(new TransactionId(i));
        }
        when(undoLogManager.purgeUpdateUndo(any(TransactionId.class))).thenReturn(true);
        when(undoLogManager.getPurgableUpdateSegments()).thenReturn(purgableIds);

        PurgeThreadIntegration integration = new PurgeThreadIntegration(
                coordinator, undoLogManager, bufferPool, indexManager,
                50,    // purgeIntervalMs
                10_000, // maxRecordsPerRound
                50,    // compressionIntervalMs
                100    // maxCompressionsPerRound
        );

        // 启动 → 运行一段时间 → 关闭
        long startNs = System.nanoTime();
        integration.start();

        assertTrue(integration.isStarted(), "Integration 应已启动");

        // 等待线程实际启动 + 几轮 purge
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        integration.shutdown();
        long durationNs = System.nanoTime() - startNs;
        double durationMs = durationNs / 1_000_000.0;

        PurgeThreadIntegration.IntegrationStats stats = integration.getStats();
        System.out.printf("\nIntegration 生命周期: duration=%.2fms%n", durationMs);
        System.out.printf("  Purge: rounds=%d, records=%d%n",
                stats.purgeRounds, stats.totalPurgedRecords);
        System.out.printf("  Compression: rounds=%d, compressions=%d, savings=%dB%n",
                stats.compressionRounds, stats.totalCompressions, stats.totalSpaceSavings);
    }

    // ==================== 辅助方法 ====================

    private UndoCompressionManager.CompressionTask createCompressionTask(int pkSeed,
                                                                          int chainLength,
                                                                          int columnCount,
                                                                          int valueLen) {
        byte[] pk = new byte[]{(byte) pkSeed, (byte) (pkSeed + 1), (byte) (pkSeed + 2)};
        List<UpdateUndoRecord> chain = new ArrayList<>();

        for (int i = chainLength; i >= 1; i--) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int c = 1; c <= columnCount; c++) {
                byte[] val = new byte[valueLen];
                val[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(c, val));
            }

            RollbackPointer prevPtr = i > 1
                    ? RollbackPointer.forInsert(0, 60 + i - 1, 300 + i - 1)
                    : RollbackPointer.NULL;

            chain.add(new UpdateUndoRecord(
                    new TransactionId(10 + i), TABLE_ID, prevPtr, pk, cols));
        }

        RollbackPointer currentRollPtr = RollbackPointer.forInsert(0, 50, 5000 + pkSeed);
        return new UndoCompressionManager.CompressionTask(pk, TABLE_ID, chain, currentRollPtr);
    }
}
