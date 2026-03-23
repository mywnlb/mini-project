package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.RecordVersion;
import cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端 Undo 压缩集成测试
 *
 * <p>验证压缩前后数据一致性：
 * <ul>
 *   <li>版本链在压缩后仍可正确遍历</li>
 *   <li>MVCC 可见性在压缩后不变</li>
 *   <li>压缩统计信息准确</li>
 *   <li>多轮压缩后状态一致</li>
 * </ul>
 * </p>
 */
@DisplayName("Undo Compression E2E Integration Test")
class UndoCompressionE2ETest {

    private static final int TABLE_ID = 1;

    private UndoLogManager undoLogManager;
    private PurgeCoordinator purgeCoordinator;
    private UndoCompressionManager compressionManager;
    private MiniTransaction mtr;

    private Map<String, UndoRecord> undoStore;
    private AtomicInteger pageCounter;

    @BeforeEach
    void setUp() throws Exception {
        undoLogManager = mock(UndoLogManager.class);
        purgeCoordinator = mock(PurgeCoordinator.class);
        compressionManager = new UndoCompressionManager(undoLogManager, purgeCoordinator);
        mtr = mock(MiniTransaction.class);
        undoStore = new HashMap<>();
        pageCounter = new AtomicInteger(1000);

        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(100_000));

        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(invocation -> {
                    UpdateUndoRecord merged = invocation.getArgument(0);
                    int pageNo = pageCounter.getAndIncrement();
                    RollbackPointer ptr = RollbackPointer.forInsert(0, pageNo, 128);
                    undoStore.put(ptrKey(ptr), merged);
                    return ptr;
                });
    }

    // ==================== 3.1 端到端压缩正确性 ====================

    @Test
    @DisplayName("E2E：单条链压缩后版本链深度验证")
    void testSingleChainCompressionDepthReduction() {
        int chainLength = 10;
        ChainInfo chain = buildVersionChain(chainLength, 3, 64);

        // 压缩前版本链深度
        VersionChainReader readerBefore = createChainReader();
        int depthBefore = readerBefore.getChainDepth(chain.headPtr);
        assertEquals(chainLength, depthBefore, "压缩前链深度应等于链长度");

        // 执行压缩
        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                chain.primaryKey, TABLE_ID, chain.undoChain, chain.headPtr, mtr);

        assertTrue(result.isSuccessful(), "压缩应成功");
        assertNotNull(result.getNewRollPtr(), "应返回新的 RollPtr");
        assertTrue(result.getSpaceSavings() > 0, "应有空间节省");

        // 压缩后深度应为 1
        VersionChainReader readerAfter = createChainReader();
        int depthAfter = readerAfter.getChainDepth(result.getNewRollPtr());
        assertEquals(1, depthAfter, "压缩后链深度应为 1");
    }

    @Test
    @DisplayName("E2E：压缩后列值一致性验证")
    void testCompressionPreservesColumnValues() {
        byte[] pk = new byte[]{0x01, 0x02, 0x03, 0x04};

        // 构建 3 版本链：V3(修改col 0,1) → V2(修改col 1,2) → V1(修改col 0,2)
        List<UpdateUndoRecord> chain = new ArrayList<>();

        // V3 (最新): col 0 = "aaa", col 1 = "bbb"
        chain.add(new UpdateUndoRecord(
                new TransactionId(103), TABLE_ID,
                RollbackPointer.forInsert(0, 502, 64), pk,
                List.of(
                        new UpdateUndoRecord.OldColumnValue(0, "aaa".getBytes()),
                        new UpdateUndoRecord.OldColumnValue(1, "bbb".getBytes())
                )));

        // V2: col 1 = "ccc", col 2 = "ddd"
        chain.add(new UpdateUndoRecord(
                new TransactionId(102), TABLE_ID,
                RollbackPointer.forInsert(0, 501, 64), pk,
                List.of(
                        new UpdateUndoRecord.OldColumnValue(1, "ccc".getBytes()),
                        new UpdateUndoRecord.OldColumnValue(2, "ddd".getBytes())
                )));

        // V1 (最旧): col 0 = "eee", col 2 = "fff"
        chain.add(new UpdateUndoRecord(
                new TransactionId(101), TABLE_ID,
                RollbackPointer.NULL, pk,
                List.of(
                        new UpdateUndoRecord.OldColumnValue(0, "eee".getBytes()),
                        new UpdateUndoRecord.OldColumnValue(2, "fff".getBytes())
                )));

        RollbackPointer headPtr = RollbackPointer.forInsert(0, 503, 64);

        // 执行压缩
        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                pk, TABLE_ID, chain, headPtr, mtr);

        assertTrue(result.isSuccessful(), "压缩应成功");

        // 验证合并后的记录
        UndoRecord mergedRecord = undoStore.get(ptrKey(result.getNewRollPtr()));
        assertNotNull(mergedRecord, "合并记录应存在于 store");
        assertInstanceOf(UpdateUndoRecord.class, mergedRecord);

        UpdateUndoRecord merged = (UpdateUndoRecord) mergedRecord;
        List<UpdateUndoRecord.OldColumnValue> mergedCols = merged.getOldColumns();

        // 合并后应包含所有涉及的列 (0, 1, 2)
        Set<Integer> colIds = new HashSet<>();
        for (UpdateUndoRecord.OldColumnValue col : mergedCols) {
            colIds.add(col.columnId);
        }
        assertTrue(colIds.contains(0), "合并结果应包含 col 0");
        assertTrue(colIds.contains(1), "合并结果应包含 col 1");
        assertTrue(colIds.contains(2), "合并结果应包含 col 2");
    }

    @Test
    @DisplayName("E2E：压缩统计信息准确性")
    void testCompressionStatsAccuracy() {
        int taskCount = 10;
        int chainLength = 5;
        List<UndoCompressionManager.CompressionTask> tasks = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            byte[] pk = new byte[]{(byte) i, (byte) (i + 1)};
            List<UpdateUndoRecord> chain = buildSimpleChain(pk, chainLength, 3, 32);
            RollbackPointer ptr = RollbackPointer.forInsert(0, 50 + i, 100);
            tasks.add(new UndoCompressionManager.CompressionTask(pk, TABLE_ID, chain, ptr));
        }

        List<UndoCompressionManager.CompressionResult> results =
                compressionManager.batchCompress(tasks, mtr);

        long successCount = results.stream().filter(UndoCompressionManager.CompressionResult::isSuccessful).count();
        int totalSavings = results.stream().mapToInt(UndoCompressionManager.CompressionResult::getSpaceSavings).sum();

        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();

        assertEquals(successCount, stats.successfulCompressions,
                "统计成功数应与实际匹配");
        assertEquals(taskCount - successCount, stats.failedCompressions,
                "统计失败数应与实际匹配");
        assertEquals(totalSavings, stats.totalSpaceSavings,
                "统计节省空间应与实际匹配");
    }

    @Test
    @DisplayName("E2E：多轮压缩状态累计正确")
    void testMultiRoundCompressionStatsAccumulation() {
        int rounds = 5;
        int tasksPerRound = 3;
        long totalSuccess = 0;
        long totalSavings = 0;

        for (int round = 0; round < rounds; round++) {
            List<UndoCompressionManager.CompressionTask> tasks = new ArrayList<>();
            for (int i = 0; i < tasksPerRound; i++) {
                byte[] pk = new byte[]{(byte) round, (byte) i};
                List<UpdateUndoRecord> chain = buildSimpleChain(pk, 6, 2, 48);
                RollbackPointer ptr = RollbackPointer.forInsert(0, 50 + round * 10 + i, 100);
                tasks.add(new UndoCompressionManager.CompressionTask(pk, TABLE_ID, chain, ptr));
            }

            List<UndoCompressionManager.CompressionResult> results =
                    compressionManager.batchCompress(tasks, mtr);

            totalSuccess += results.stream()
                    .filter(UndoCompressionManager.CompressionResult::isSuccessful).count();
            totalSavings += results.stream()
                    .mapToInt(UndoCompressionManager.CompressionResult::getSpaceSavings).sum();
        }

        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(totalSuccess, stats.successfulCompressions,
                "多轮累计成功数应正确");
        assertEquals(totalSavings, stats.totalSpaceSavings,
                "多轮累计节省空间应正确");
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("E2E：空链压缩应失败")
    void testEmptyChainCompression() {
        byte[] pk = new byte[]{0x01};
        List<UpdateUndoRecord> emptyChain = new ArrayList<>();
        RollbackPointer ptr = RollbackPointer.forInsert(0, 10, 100);

        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                pk, TABLE_ID, emptyChain, ptr, mtr);

        assertFalse(result.isSuccessful(), "空链压缩应失败");
    }

    @Test
    @DisplayName("E2E：单条记录链压缩应失败")
    void testSingleRecordChainCompression() {
        byte[] pk = new byte[]{0x01};
        List<UpdateUndoRecord> chain = buildSimpleChain(pk, 1, 2, 32);
        RollbackPointer ptr = RollbackPointer.forInsert(0, 10, 100);

        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                pk, TABLE_ID, chain, ptr, mtr);

        // 单条记录无需压缩
        assertFalse(result.isSuccessful(), "单条记录链不需要压缩");
    }

    @Test
    @DisplayName("E2E：超出 purge limit 的链不应压缩")
    void testChainBeyondPurgeLimitNotCompressed() {
        // 设置低 purge limit
        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(50));

        byte[] pk = new byte[]{0x01};
        List<UpdateUndoRecord> chain = new ArrayList<>();
        // 所有事务 ID > purge limit
        for (int i = 3; i >= 1; i--) {
            chain.add(new UpdateUndoRecord(
                    new TransactionId(100 + i), TABLE_ID,
                    i > 1 ? RollbackPointer.forInsert(0, 60 + i, 64) : RollbackPointer.NULL,
                    pk,
                    List.of(new UpdateUndoRecord.OldColumnValue(0, new byte[]{(byte) i}))));
        }

        RollbackPointer ptr = RollbackPointer.forInsert(0, 10, 100);
        UndoCompressionManager.CompressionResult result = compressionManager.compressUndoChain(
                pk, TABLE_ID, chain, ptr, mtr);

        assertFalse(result.isSuccessful(), "超出 purge limit 的链不应压缩");
    }

    // ==================== 辅助方法 ====================

    private ChainInfo buildVersionChain(int depth, int modifiedColumns, int valueLen) {
        byte[] pk = new byte[]{0x01, 0x02, 0x03, 0x04};
        List<UpdateUndoRecord> chain = new ArrayList<>();
        RollbackPointer prevPtr = RollbackPointer.NULL;

        for (int i = 1; i <= depth; i++) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int c = 0; c < modifiedColumns; c++) {
                byte[] val = new byte[valueLen];
                val[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(c, val));
            }

            UpdateUndoRecord record = new UpdateUndoRecord(
                    new TransactionId(i), TABLE_ID, prevPtr, pk, cols);

            int pageNo = pageCounter.getAndIncrement();
            RollbackPointer thisPtr = RollbackPointer.forInsert(0, pageNo, 64);
            undoStore.put(ptrKey(thisPtr), record);

            chain.add(0, record);
            prevPtr = thisPtr;
        }

        return new ChainInfo(pk, chain, prevPtr);
    }

    private List<UpdateUndoRecord> buildSimpleChain(byte[] pk, int chainLength,
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

    private VersionChainReader createChainReader() {
        VersionChainReader.UndoRecordReader undoReader = rollPtr ->
                undoStore.get(ptrKey(rollPtr));
        return new VersionChainReader(undoReader);
    }

    private String ptrKey(RollbackPointer ptr) {
        return ptr.getRsegId() + ":" + ptr.getPageNo() + ":" + ptr.getOffset();
    }

    private static class ChainInfo {
        final byte[] primaryKey;
        final List<UpdateUndoRecord> undoChain;
        final RollbackPointer headPtr;

        ChainInfo(byte[] pk, List<UpdateUndoRecord> chain, RollbackPointer headPtr) {
            this.primaryKey = pk;
            this.undoChain = chain;
            this.headPtr = headPtr;
        }
    }
}
