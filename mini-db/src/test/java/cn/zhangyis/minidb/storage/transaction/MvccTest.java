package cn.zhangyis.minidb.storage.transaction;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.RecordVersion;
import cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader;
import cn.zhangyis.minidb.storage.transaction.mvcc.VisibilityChecker;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.InsertUndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVCC 组件单元测试
 *
 * <p>测试 VisibilityChecker, ReadView, VersionChainReader 的正确性</p>
 *
 * <h2>测试覆盖</h2>
 * <ul>
 *   <li>可见性判断算法的5个规则</li>
 *   <li>版本链遍历逻辑</li>
 *   <li>边界条件处理</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("MVCC Components Tests")
class MvccTest {

    // ==================== ReadView 测试 ====================

    @Nested
    @DisplayName("ReadView Tests")
    class ReadViewTest {

        @Test
        @DisplayName("创建 ReadView 验证不可变性")
        void testReadViewImmutability() {
            List<TransactionId> activeList = new ArrayList<>();
            activeList.add(new TransactionId(50));
            activeList.add(new TransactionId(60));

            ReadView readView = new ReadView(
                    new TransactionId(100),  // creator
                    new TransactionId(110),  // lowLimit (高水位)
                    new TransactionId(40),   // upLimit (低水位)
                    activeList
            );

            // 修改原始列表不应影响 ReadView
            activeList.add(new TransactionId(70));
            assertEquals(2, readView.getActiveCount());

            // 返回的列表也不应可修改
            assertThrows(UnsupportedOperationException.class, () ->
                    readView.getActiveTrxIds().add(new TransactionId(80)));
        }

        @Test
        @DisplayName("ReadView 可见性 - 规则1: 无效事务ID可见")
        void testVisibilityInvalidTrxId() {
            ReadView readView = createSimpleReadView(100, 110, 40, List.of(50L, 60L));

            assertTrue(readView.isVisible(TransactionId.INVALID));
            assertTrue(readView.isVisible(new TransactionId(0)));
        }

        @Test
        @DisplayName("ReadView 可见性 - 规则2: 创建者自己可见")
        void testVisibilityCreator() {
            TransactionId creatorId = new TransactionId(100);
            ReadView readView = new ReadView(
                    creatorId,
                    new TransactionId(110),
                    new TransactionId(40),
                    List.of()
            );

            assertTrue(readView.isVisible(creatorId));
        }

        @Test
        @DisplayName("ReadView 可见性 - 规则3: 小于低水位可见")
        void testVisibilityBelowUpLimit() {
            ReadView readView = createSimpleReadView(100, 110, 40, List.of(50L, 60L));

            // trx_id < up_limit_id (40) 可见
            assertTrue(readView.isVisible(new TransactionId(1)));
            assertTrue(readView.isVisible(new TransactionId(39)));

            // trx_id == up_limit_id 不适用此规则
        }

        @Test
        @DisplayName("ReadView 可见性 - 规则4: 大于等于高水位不可见")
        void testVisibilityAboveLowLimit() {
            ReadView readView = createSimpleReadView(100, 110, 40, List.of(50L, 60L));

            // trx_id >= low_limit_id (110) 不可见
            assertFalse(readView.isVisible(new TransactionId(110)));
            assertFalse(readView.isVisible(new TransactionId(150)));
            assertFalse(readView.isVisible(new TransactionId(1000)));
        }

        @Test
        @DisplayName("ReadView 可见性 - 规则5: 活跃列表中不可见")
        void testVisibilityInActiveList() {
            ReadView readView = createSimpleReadView(100, 110, 40, List.of(50L, 60L, 70L));

            // 在活跃列表中的事务不可见
            assertFalse(readView.isVisible(new TransactionId(50)));
            assertFalse(readView.isVisible(new TransactionId(60)));
            assertFalse(readView.isVisible(new TransactionId(70)));
        }

        @Test
        @DisplayName("ReadView 可见性 - 规则6: 不在活跃列表的已提交事务可见")
        void testVisibilityCommittedNotInActiveList() {
            ReadView readView = createSimpleReadView(100, 110, 40, List.of(50L, 70L));

            // up_limit_id <= trx_id < low_limit_id 且不在活跃列表中
            // 40 <= 60 < 110, 60 不在 [50, 70] 中 → 可见
            assertTrue(readView.isVisible(new TransactionId(45)));
            assertTrue(readView.isVisible(new TransactionId(60)));
            assertTrue(readView.isVisible(new TransactionId(80)));
        }

        @Test
        @DisplayName("ReadView 可见性 - 边界情况: 空活跃列表")
        void testVisibilityEmptyActiveList() {
            ReadView readView = createSimpleReadView(100, 110, 40, List.of());

            // 所有 up_limit <= trx < low_limit 都可见
            assertTrue(readView.isVisible(new TransactionId(40)));
            assertTrue(readView.isVisible(new TransactionId(50)));
            assertTrue(readView.isVisible(new TransactionId(109)));
        }

        private ReadView createSimpleReadView(long creator, long lowLimit,
                                              long upLimit, List<Long> activeIds) {
            List<TransactionId> activeList = activeIds.stream()
                    .map(TransactionId::new)
                    .toList();
            return new ReadView(
                    new TransactionId(creator),
                    new TransactionId(lowLimit),
                    new TransactionId(upLimit),
                    activeList
            );
        }
    }

    // ==================== VisibilityChecker 测试 ====================

    @Nested
    @DisplayName("VisibilityChecker Tests")
    class VisibilityCheckerTest {

        private ReadView readView;

        @BeforeEach
        void setUp() {
            // creator=100, lowLimit=110, upLimit=40, active=[50, 60]
            readView = new ReadView(
                    new TransactionId(100),
                    new TransactionId(110),
                    new TransactionId(40),
                    List.of(new TransactionId(50), new TransactionId(60))
            );
        }

        @Test
        @DisplayName("isVisible 参数校验")
        void testIsVisibleParameterValidation() {
            assertThrows(NullPointerException.class, () ->
                    VisibilityChecker.isVisible((TransactionId) null, readView));
            assertThrows(NullPointerException.class, () ->
                    VisibilityChecker.isVisible(new TransactionId(1), null));
        }

        @Test
        @DisplayName("isVisible 使用原始值")
        void testIsVisibleWithRawValue() {
            // 无效值可见
            assertTrue(VisibilityChecker.isVisible(0L, readView));

            // 小于低水位可见
            assertTrue(VisibilityChecker.isVisible(30L, readView));

            // 大于高水位不可见
            assertFalse(VisibilityChecker.isVisible(120L, readView));
        }

        @Test
        @DisplayName("analyzeVisibility 详细分析")
        void testAnalyzeVisibility() {
            // 无效 trxId
            assertEquals(VisibilityChecker.VisibilityResult.VISIBLE_INVALID_TRX_ID,
                    VisibilityChecker.analyzeVisibility(TransactionId.INVALID, readView));

            // 创建者
            assertEquals(VisibilityChecker.VisibilityResult.VISIBLE_CREATOR,
                    VisibilityChecker.analyzeVisibility(new TransactionId(100), readView));

            // 小于低水位
            assertEquals(VisibilityChecker.VisibilityResult.VISIBLE_BELOW_UP_LIMIT,
                    VisibilityChecker.analyzeVisibility(new TransactionId(30), readView));

            // 大于高水位
            assertEquals(VisibilityChecker.VisibilityResult.NOT_VISIBLE_ABOVE_LOW_LIMIT,
                    VisibilityChecker.analyzeVisibility(new TransactionId(120), readView));

            // 在活跃列表中
            assertEquals(VisibilityChecker.VisibilityResult.NOT_VISIBLE_IN_ACTIVE_LIST,
                    VisibilityChecker.analyzeVisibility(new TransactionId(50), readView));

            // 已提交
            assertEquals(VisibilityChecker.VisibilityResult.VISIBLE_COMMITTED,
                    VisibilityChecker.analyzeVisibility(new TransactionId(70), readView));
        }

        @Test
        @DisplayName("needsVersionChainTraversal 判断")
        void testNeedsVersionChainTraversal() {
            // 可见且有旧版本 → 不需要遍历
            assertFalse(VisibilityChecker.needsVersionChainTraversal(
                    new TransactionId(30), readView, true));

            // 不可见且有旧版本 → 需要遍历
            assertTrue(VisibilityChecker.needsVersionChainTraversal(
                    new TransactionId(120), readView, true));

            // 不可见但无旧版本 → 不需要遍历
            assertFalse(VisibilityChecker.needsVersionChainTraversal(
                    new TransactionId(120), readView, false));
        }
    }

    // ==================== VersionChainReader 测试 ====================

    @Nested
    @DisplayName("VersionChainReader Tests")
    class VersionChainReaderTest {

        private MockUndoRecordReader mockReader;
        private VersionChainReader chainReader;
        private ReadView readView;

        @BeforeEach
        void setUp() {
            mockReader = new MockUndoRecordReader();
            chainReader = new VersionChainReader(mockReader);

            // creator=100, lowLimit=110, upLimit=40, active=[50]
            readView = new ReadView(
                    new TransactionId(100),
                    new TransactionId(110),
                    new TransactionId(40),
                    List.of(new TransactionId(50))
            );
        }

        @Test
        @DisplayName("空指针返回空")
        void testNullPointerReturnsEmpty() {
            Optional<RecordVersion> result = chainReader.findVisibleVersion(
                    RollbackPointer.NULL, readView);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("第一个版本可见时直接返回")
        void testFirstVersionVisible() {
            // 创建版本链: trxId=30 (可见)
            RollbackPointer ptr1 = RollbackPointer.forUpdate(1, 100, 50);
            mockReader.addRecord(ptr1, createUpdateUndo(30, ptr1, RollbackPointer.NULL));

            Optional<RecordVersion> result = chainReader.findVisibleVersion(ptr1, readView);

            assertTrue(result.isPresent());
            assertEquals(30L, result.get().getTrxId().getValue());
        }

        @Test
        @DisplayName("遍历版本链找到可见版本")
        void testTraverseToFindVisible() {
            // 版本链: trxId=120 (不可见) → trxId=70 (可见)
            RollbackPointer ptr1 = RollbackPointer.forUpdate(1, 100, 50);
            RollbackPointer ptr2 = RollbackPointer.forUpdate(1, 100, 100);

            mockReader.addRecord(ptr1, createUpdateUndo(120, ptr1, ptr2));
            mockReader.addRecord(ptr2, createUpdateUndo(70, ptr2, RollbackPointer.NULL));

            Optional<RecordVersion> result = chainReader.findVisibleVersion(ptr1, readView);

            assertTrue(result.isPresent());
            assertEquals(70L, result.get().getTrxId().getValue());
        }

        @Test
        @DisplayName("遍历到 INSERT Undo 时停止")
        void testStopAtInsertUndo() {
            // 版本链: trxId=120 (不可见) → INSERT trxId=50 (不可见) → 停止
            RollbackPointer ptr1 = RollbackPointer.forUpdate(1, 100, 50);
            RollbackPointer ptr2 = RollbackPointer.forInsert(1, 100, 100);

            mockReader.addRecord(ptr1, createUpdateUndo(120, ptr1, ptr2));
            mockReader.addRecord(ptr2, createInsertUndo(50)); // 50 在活跃列表中，不可见

            Optional<RecordVersion> result = chainReader.findVisibleVersion(ptr1, readView);

            // INSERT Undo 不可见，且是版本链起点，返回空
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("INSERT Undo 可见时返回")
        void testInsertUndoVisible() {
            // 版本链: trxId=120 (不可见) → INSERT trxId=30 (可见)
            RollbackPointer ptr1 = RollbackPointer.forUpdate(1, 100, 50);
            RollbackPointer ptr2 = RollbackPointer.forInsert(1, 100, 100);

            mockReader.addRecord(ptr1, createUpdateUndo(120, ptr1, ptr2));
            mockReader.addRecord(ptr2, createInsertUndo(30)); // 30 < 40, 可见

            Optional<RecordVersion> result = chainReader.findVisibleVersion(ptr1, readView);

            assertTrue(result.isPresent());
            assertEquals(30L, result.get().getTrxId().getValue());
            assertTrue(result.get().isInsertVersion());
        }

        @Test
        @DisplayName("读取所有版本")
        void testReadAllVersions() {
            // 版本链: trxId=120 → trxId=70 → INSERT trxId=30
            RollbackPointer ptr1 = RollbackPointer.forUpdate(1, 100, 50);
            RollbackPointer ptr2 = RollbackPointer.forUpdate(1, 100, 100);
            RollbackPointer ptr3 = RollbackPointer.forInsert(1, 100, 150);

            mockReader.addRecord(ptr1, createUpdateUndo(120, ptr1, ptr2));
            mockReader.addRecord(ptr2, createUpdateUndo(70, ptr2, ptr3));
            mockReader.addRecord(ptr3, createInsertUndo(30));

            List<RecordVersion> versions = chainReader.readAllVersions(ptr1);

            assertEquals(3, versions.size());
            assertEquals(120L, versions.get(0).getTrxId().getValue());
            assertEquals(70L, versions.get(1).getTrxId().getValue());
            assertEquals(30L, versions.get(2).getTrxId().getValue());
            assertTrue(versions.get(2).isInsertVersion());
        }

        @Test
        @DisplayName("获取版本链深度")
        void testGetChainDepth() {
            // 版本链深度为 3
            RollbackPointer ptr1 = RollbackPointer.forUpdate(1, 100, 50);
            RollbackPointer ptr2 = RollbackPointer.forUpdate(1, 100, 100);
            RollbackPointer ptr3 = RollbackPointer.forInsert(1, 100, 150);

            mockReader.addRecord(ptr1, createUpdateUndo(120, ptr1, ptr2));
            mockReader.addRecord(ptr2, createUpdateUndo(70, ptr2, ptr3));
            mockReader.addRecord(ptr3, createInsertUndo(30));

            assertEquals(3, chainReader.getChainDepth(ptr1));
            assertEquals(2, chainReader.getChainDepth(ptr2));
            assertEquals(1, chainReader.getChainDepth(ptr3));
            assertEquals(0, chainReader.getChainDepth(RollbackPointer.NULL));
        }

        @Test
        @DisplayName("超过最大深度抛出异常")
        void testMaxDepthException() {
            int maxDepth = 10;
            VersionChainReader limitedReader = new VersionChainReader(mockReader, maxDepth);

            // 创建超过限制的版本链
            RollbackPointer[] ptrs = new RollbackPointer[maxDepth + 5];
            for (int i = 0; i < ptrs.length; i++) {
                ptrs[i] = RollbackPointer.forUpdate(1, 100, i * 50);
            }

            for (int i = 0; i < ptrs.length - 1; i++) {
                // 所有版本都不可见 (trxId > lowLimit)
                mockReader.addRecord(ptrs[i], createUpdateUndo(200 + i, ptrs[i], ptrs[i + 1]));
            }
            mockReader.addRecord(ptrs[ptrs.length - 1],
                    createUpdateUndo(300, ptrs[ptrs.length - 1], RollbackPointer.NULL));

            assertThrows(VersionChainReader.VersionChainException.class, () ->
                    limitedReader.findVisibleVersion(ptrs[0], readView));
        }

        @Test
        @DisplayName("Undo 记录被 purge 返回空")
        void testPurgedUndoRecord() {
            RollbackPointer ptr = RollbackPointer.forUpdate(1, 100, 50);
            // 不添加记录到 mockReader，模拟 purge

            Optional<RecordVersion> result = chainReader.findVisibleVersion(ptr, readView);

            assertTrue(result.isEmpty());
        }

        // ==================== 辅助方法 ====================

        private UpdateUndoRecord createUpdateUndo(long trxIdValue,
                                                  RollbackPointer selfPtr,
                                                  RollbackPointer prevPtr) {
            List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
            oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{0x01, 0x02}));

            return new UpdateUndoRecord(
                    new TransactionId(trxIdValue),
                    1,  // tableId
                    prevPtr,
                    new byte[]{0x10, 0x20},  // pk
                    oldCols
            );
        }

        private InsertUndoRecord createInsertUndo(long trxIdValue) {
            return new InsertUndoRecord(
                    new TransactionId(trxIdValue),
                    1,  // tableId
                    new byte[]{0x10, 0x20}  // pk
            );
        }

        // ==================== Mock Undo Reader ====================

        /**
         * 模拟 Undo 记录读取器
         */
        private static class MockUndoRecordReader implements VersionChainReader.UndoRecordReader {
            private final Map<String, UndoRecord> records = new HashMap<>();

            void addRecord(RollbackPointer ptr, UndoRecord record) {
                records.put(ptrKey(ptr), record);
            }

            @Override
            public UndoRecord read(RollbackPointer rollPtr) {
                return records.get(ptrKey(rollPtr));
            }

            private String ptrKey(RollbackPointer ptr) {
                return ptr.getRsegId() + ":" + ptr.getPageNo() + ":" + ptr.getOffset();
            }
        }
    }

    // ==================== RecordVersion 测试 ====================

    @Nested
    @DisplayName("RecordVersion Tests")
    class RecordVersionTest {

        @Test
        @DisplayName("从 UpdateUndo 创建版本")
        void testFromUpdateUndo() {
            List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
            oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{0x01}));
            oldCols.add(new UpdateUndoRecord.OldColumnValue(3, new byte[]{0x03, 0x04}));

            UpdateUndoRecord undoRecord = new UpdateUndoRecord(
                    new TransactionId(100),
                    5,
                    RollbackPointer.forUpdate(1, 100, 50),
                    new byte[]{0x10},
                    oldCols
            );

            RecordVersion version = RecordVersion.fromUpdateUndo(undoRecord);

            assertEquals(100L, version.getTrxId().getValue());
            assertEquals(5, version.getTableId());
            assertFalse(version.isInsertVersion());
            assertFalse(version.isDeleteMarked());
            assertTrue(version.hasPreviousVersion());

            assertArrayEquals(new byte[]{0x01}, version.getColumnValue(1));
            assertArrayEquals(new byte[]{0x03, 0x04}, version.getColumnValue(3));
            assertNull(version.getColumnValue(2));
        }

        @Test
        @DisplayName("创建 INSERT 起点版本")
        void testCreateInsertOrigin() {
            RecordVersion version = RecordVersion.createInsertOrigin(
                    new TransactionId(50),
                    3,
                    new byte[]{0x01, 0x02}
            );

            assertEquals(50L, version.getTrxId().getValue());
            assertEquals(3, version.getTableId());
            assertTrue(version.isInsertVersion());
            assertFalse(version.isDeleteMarked());
            assertFalse(version.hasPreviousVersion());
            assertTrue(version.getPrevVersionPtr().isNull());
        }

        @Test
        @DisplayName("创建删除标记版本")
        void testCreateDeleteMarked() {
            RecordVersion version = RecordVersion.createDeleteMarked(
                    new TransactionId(80),
                    2,
                    RollbackPointer.forUpdate(1, 100, 50),
                    new byte[]{0x05}
            );

            assertEquals(80L, version.getTrxId().getValue());
            assertTrue(version.isDeleteMarked());
            assertFalse(version.isInsertVersion());
            assertTrue(version.hasPreviousVersion());
        }

        @Test
        @DisplayName("主键数据不可变")
        void testPrimaryKeyImmutability() {
            byte[] pk = new byte[]{0x01, 0x02};
            RecordVersion version = RecordVersion.createInsertOrigin(
                    new TransactionId(50), 1, pk);

            // 修改原始数组不影响版本
            pk[0] = (byte) 0xFF;
            byte[] retrieved = version.getPrimaryKey();
            assertEquals((byte) 0x01, retrieved[0]);

            // 修改返回的数组不影响版本
            retrieved[0] = (byte) 0xAA;
            assertEquals((byte) 0x01, version.getPrimaryKey()[0]);
        }
    }
}
