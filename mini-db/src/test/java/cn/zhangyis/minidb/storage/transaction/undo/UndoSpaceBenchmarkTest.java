package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undo 空间基准测试
 *
 * <p>对比 V1 (全列) 和 V2 (增量) 格式的空间占用，验证 30-70% 空间节省目标。</p>
 *
 * <h2>测试场景</h2>
 * <ul>
 *   <li>少量列修改 (1/10 列修改) — 预期增量格式节省最大</li>
 *   <li>半数列修改 (5/10 列修改) — 预期中等节省</li>
 *   <li>全部列修改 (10/10 列修改) — 预期增量格式无节省甚至略大</li>
 *   <li>不同列宽度 — 小列(8B) vs 大列(256B)</li>
 * </ul>
 */
@DisplayName("Undo Space Benchmark: V1 vs V2")
class UndoSpaceBenchmarkTest {

    private static final int TABLE_ID = 1;
    private static final int TOTAL_COLUMNS = 10;

    // ==================== V1 vs V2 空间对比 ====================

    @Test
    @DisplayName("基准：少量列修改时 V1 vs V2 空间对比")
    void benchmarkSpaceSavings_fewColumnsModified() {
        int modifiedColumns = 1;
        int valueLen = 64;

        SpaceComparison result = compareV1V2(TOTAL_COLUMNS, modifiedColumns, valueLen, 1000);

        System.out.printf("[少量列修改] modified=%d/%d, valueLen=%dB%n", modifiedColumns, TOTAL_COLUMNS, valueLen);
        System.out.printf("  V1 总大小: %d B, 平均: %.1f B/record%n", result.v1TotalSize, result.v1AvgSize);
        System.out.printf("  V2 总大小: %d B, 平均: %.1f B/record%n", result.v2TotalSize, result.v2AvgSize);
        System.out.printf("  节省: %.2f%%%n", result.savingsPercent);

        // 修改 1/10 列时，V2 应节省 >50%
        assertTrue(result.savingsPercent > 50,
                "修改 1/10 列时，V2 应节省 >50%，实际: " + result.savingsPercent + "%");
    }

    @Test
    @DisplayName("基准：半数列修改时 V1 vs V2 空间对比")
    void benchmarkSpaceSavings_halfColumnsModified() {
        int modifiedColumns = 5;
        int valueLen = 64;

        SpaceComparison result = compareV1V2(TOTAL_COLUMNS, modifiedColumns, valueLen, 1000);

        System.out.printf("[半数列修改] modified=%d/%d, valueLen=%dB%n", modifiedColumns, TOTAL_COLUMNS, valueLen);
        System.out.printf("  V1 总大小: %d B, 平均: %.1f B/record%n", result.v1TotalSize, result.v1AvgSize);
        System.out.printf("  V2 总大小: %d B, 平均: %.1f B/record%n", result.v2TotalSize, result.v2AvgSize);
        System.out.printf("  节省: %.2f%%%n", result.savingsPercent);

        // 修改 5/10 列时，V2 仍应有节省（因为 V1 存全部列，V2 只存修改列）
        assertTrue(result.savingsPercent > 0,
                "修改 5/10 列时，V2 应有正向节省，实际: " + result.savingsPercent + "%");
    }

    @Test
    @DisplayName("基准：全部列修改时 V1 vs V2 空间对比")
    void benchmarkSpaceSavings_allColumnsModified() {
        int modifiedColumns = 10;
        int valueLen = 64;

        SpaceComparison result = compareV1V2(TOTAL_COLUMNS, modifiedColumns, valueLen, 1000);

        System.out.printf("[全部列修改] modified=%d/%d, valueLen=%dB%n", modifiedColumns, TOTAL_COLUMNS, valueLen);
        System.out.printf("  V1 总大小: %d B, 平均: %.1f B/record%n", result.v1TotalSize, result.v1AvgSize);
        System.out.printf("  V2 总大小: %d B, 平均: %.1f B/record%n", result.v2TotalSize, result.v2AvgSize);
        System.out.printf("  节省: %.2f%%（全列修改时 V2 有额外头部开销）%n", result.savingsPercent);

        // 全部列修改时 V2 因额外头部可能略大，但差距不应超过 10%
        assertTrue(result.savingsPercent > -10,
                "全列修改时 V2 开销不应超过 V1 的 10%，实际: " + result.savingsPercent + "%");
    }

    @Test
    @DisplayName("基准：大列宽度时 V1 vs V2 空间对比")
    void benchmarkSpaceSavings_largeValueColumns() {
        int modifiedColumns = 2;
        int valueLen = 256;

        SpaceComparison result = compareV1V2(TOTAL_COLUMNS, modifiedColumns, valueLen, 500);

        System.out.printf("[大列宽度] modified=%d/%d, valueLen=%dB%n", modifiedColumns, TOTAL_COLUMNS, valueLen);
        System.out.printf("  V1 总大小: %d B, 平均: %.1f B/record%n", result.v1TotalSize, result.v1AvgSize);
        System.out.printf("  V2 总大小: %d B, 平均: %.1f B/record%n", result.v2TotalSize, result.v2AvgSize);
        System.out.printf("  节省: %.2f%%%n", result.savingsPercent);

        // 大列宽度 + 少量列修改应有显著节省
        assertTrue(result.savingsPercent > 60,
                "大列宽度+少量修改时，V2 应节省 >60%，实际: " + result.savingsPercent + "%");
    }

    @Test
    @DisplayName("基准：小列宽度时 V1 vs V2 空间对比")
    void benchmarkSpaceSavings_smallValueColumns() {
        int modifiedColumns = 1;
        int valueLen = 8;

        SpaceComparison result = compareV1V2(TOTAL_COLUMNS, modifiedColumns, valueLen, 1000);

        System.out.printf("[小列宽度] modified=%d/%d, valueLen=%dB%n", modifiedColumns, TOTAL_COLUMNS, valueLen);
        System.out.printf("  V1 总大小: %d B, 平均: %.1f B/record%n", result.v1TotalSize, result.v1AvgSize);
        System.out.printf("  V2 总大小: %d B, 平均: %.1f B/record%n", result.v2TotalSize, result.v2AvgSize);
        System.out.printf("  节省: %.2f%%%n", result.savingsPercent);

        // 小列宽度时头部占比更大，但只修改 1/10 列仍应有节省
        assertTrue(result.savingsPercent > 30,
                "小列宽度+少量修改时，V2 应节省 >30%，实际: " + result.savingsPercent + "%");
    }

    // ==================== 链长度对压缩效率的影响 ====================

    @Test
    @DisplayName("基准：不同链长度的压缩空间节省")
    void benchmarkCompressionByChainLength() {
        int[] chainLengths = {2, 3, 5, 10, 20, 50};
        int modifiedColumns = 2;
        int valueLen = 64;

        System.out.println("链长度对压缩效率的影响:");
        System.out.printf("  %-10s %-15s %-15s %-10s%n", "链长度", "原始大小(B)", "合并后大小(B)", "节省比例");

        for (int chainLength : chainLengths) {
            // 构建 V1 格式 Undo 链
            List<UpdateUndoRecord> chain = createUndoChain(chainLength, modifiedColumns,
                    TOTAL_COLUMNS, valueLen);

            int originalSize = chain.stream().mapToInt(UndoRecord::calculateSize).sum();

            // 合并后等效为一条记录（最终状态），包含所有修改列
            // 最终合并列数 = 去重后的所有 columnId
            UpdateUndoRecord merged = createMergedRecord(chain);
            int mergedSize = merged.calculateSize();

            double savings = 100.0 * (originalSize - mergedSize) / originalSize;

            System.out.printf("  %-10d %-15d %-15d %.2f%%%n",
                    chainLength, originalSize, mergedSize, savings);

            // 链越长，节省越多
            if (chainLength >= 3) {
                assertTrue(savings > 30,
                        "链长度 " + chainLength + " 的压缩节省应 >30%，实际: " + savings + "%");
            }
        }
    }

    // ==================== 辅助方法 ====================

    private SpaceComparison compareV1V2(int totalColumns, int modifiedColumns,
                                         int valueLen, int recordCount) {
        long v1Total = 0;
        long v2Total = 0;

        for (int i = 0; i < recordCount; i++) {
            // V1: 存储所有列的旧值
            UpdateUndoRecord v1 = createV1Record(i, totalColumns, valueLen);
            v1Total += v1.calculateSize();

            // V2: 只存储修改的列
            UpdateUndoRecord v2 = createV2Record(i, modifiedColumns, valueLen);
            v2Total += v2.calculateSize();
        }

        return new SpaceComparison(v1Total, v2Total, recordCount);
    }

    private UpdateUndoRecord createV1Record(int seed, int totalColumns, int valueLen) {
        byte[] pk = intToBytes(seed);
        List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
        for (int c = 0; c < totalColumns; c++) {
            byte[] val = new byte[valueLen];
            val[0] = (byte) (seed + c);
            cols.add(new UpdateUndoRecord.OldColumnValue(c, val));
        }
        return new UpdateUndoRecord(
                new TransactionId(100 + seed), TABLE_ID,
                RollbackPointer.NULL, pk, cols,
                UndoRecordVersion.FORMAT_V1, UndoRecordVersion.INITIAL_SCHEMA_VERSION);
    }

    private UpdateUndoRecord createV2Record(int seed, int modifiedColumns, int valueLen) {
        byte[] pk = intToBytes(seed);
        List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
        for (int c = 0; c < modifiedColumns; c++) {
            byte[] val = new byte[valueLen];
            val[0] = (byte) (seed + c);
            cols.add(new UpdateUndoRecord.OldColumnValue(c, val));
        }
        return new UpdateUndoRecord(
                new TransactionId(100 + seed), TABLE_ID,
                RollbackPointer.NULL, pk, cols,
                UndoRecordVersion.FORMAT_V2, UndoRecordVersion.INITIAL_SCHEMA_VERSION);
    }

    private List<UpdateUndoRecord> createUndoChain(int chainLength, int modifiedColumns,
                                                    int totalColumns, int valueLen) {
        byte[] pk = intToBytes(1);
        List<UpdateUndoRecord> chain = new ArrayList<>();

        for (int i = chainLength; i >= 1; i--) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            // 每个版本修改不同的列子集，模拟真实场景
            for (int c = 0; c < modifiedColumns; c++) {
                int colId = (i + c) % totalColumns;
                byte[] val = new byte[valueLen];
                val[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(colId, val));
            }

            RollbackPointer prevPtr = i > 1
                    ? RollbackPointer.forInsert(0, 60 + i - 1, 300 + i - 1)
                    : RollbackPointer.NULL;

            chain.add(new UpdateUndoRecord(
                    new TransactionId(100 + i), TABLE_ID, prevPtr, pk, cols));
        }
        return chain;
    }

    /**
     * 模拟压缩合并：所有链段的列去重合并为一条记录
     */
    private UpdateUndoRecord createMergedRecord(List<UpdateUndoRecord> chain) {
        // 从旧到新合并列值（新覆盖旧）
        java.util.Map<Integer, byte[]> mergedCols = new java.util.LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (UpdateUndoRecord.OldColumnValue col : chain.get(i).getOldColumns()) {
                mergedCols.put(col.columnId, col.value);
            }
        }

        List<UpdateUndoRecord.OldColumnValue> finalCols = new ArrayList<>();
        for (var entry : mergedCols.entrySet()) {
            finalCols.add(new UpdateUndoRecord.OldColumnValue(entry.getKey(), entry.getValue()));
        }

        byte[] pk = chain.get(0).getPrimaryKeyData();
        TransactionId trxId = chain.get(chain.size() - 1).getTrxId();
        RollbackPointer prevPtr = chain.get(chain.size() - 1).getPrevUndoPtr();

        return new UpdateUndoRecord(trxId, TABLE_ID, prevPtr, pk, finalCols);
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static class SpaceComparison {
        final long v1TotalSize;
        final long v2TotalSize;
        final double v1AvgSize;
        final double v2AvgSize;
        final double savingsPercent;

        SpaceComparison(long v1Total, long v2Total, int count) {
            this.v1TotalSize = v1Total;
            this.v2TotalSize = v2Total;
            this.v1AvgSize = (double) v1Total / count;
            this.v2AvgSize = (double) v2Total / count;
            this.savingsPercent = 100.0 * (v1Total - v2Total) / v1Total;
        }
    }
}
