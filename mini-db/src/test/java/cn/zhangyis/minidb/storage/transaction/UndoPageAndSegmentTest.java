package cn.zhangyis.minidb.storage.transaction;

import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Undo Log 组件单元测试
 *
 * <p>测试 UndoPage, UndoSegment 的正确性</p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("Undo Log Components Tests")
class UndoPageAndSegmentTest {

    // ==================== UndoPage 测试 ====================

    @Nested
    @DisplayName("UndoPage Tests")
    class UndoPageTest {

        private ByteBuffer pageBuffer;

        @BeforeEach
        void setUp() {
            pageBuffer = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
            pageBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }

        @Test
        @DisplayName("初始化 INSERT Undo Page")
        void testInitInsertUndoPage() {
            TransactionId trxId = new TransactionId(100);
            int rsegId = 5;

            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, rsegId);

            assertEquals(UndoPageHeader.UNDO_INSERT, UndoPage.getUndoType(pageBuffer));
            assertEquals(trxId, UndoPage.getTrxId(pageBuffer));
            assertEquals(rsegId, UndoPage.getRsegId(pageBuffer));
            assertEquals(UndoPageHeader.STATE_ACTIVE, UndoPage.getState(pageBuffer));
            assertTrue(UndoPage.isEmpty(pageBuffer));
        }

        @Test
        @DisplayName("初始化 UPDATE Undo Page")
        void testInitUpdateUndoPage() {
            TransactionId trxId = new TransactionId(200);
            int rsegId = 10;

            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_UPDATE, trxId, rsegId);

            assertEquals(UndoPageHeader.UNDO_UPDATE, UndoPage.getUndoType(pageBuffer));
            assertEquals(trxId, UndoPage.getTrxId(pageBuffer));
            assertEquals(rsegId, UndoPage.getRsegId(pageBuffer));
        }

        @Test
        @DisplayName("写入单条 INSERT Undo 记录")
        void testWriteSingleInsertUndo() {
            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, 0);

            // 创建 INSERT Undo 记录
            byte[] pk = new byte[]{0x01, 0x02, 0x03, 0x04};
            InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, 1, pk);

            // 写入
            int offset = UndoPage.writeUndoRecord(pageBuffer, undoRecord);

            // 验证
            assertTrue(offset > 0);
            assertFalse(UndoPage.isEmpty(pageBuffer));
            assertEquals(1, UndoPage.getRecordCount(pageBuffer));

            // 读取验证
            UndoRecord readRecord = UndoPage.readUndoRecord(pageBuffer, offset);
            assertInstanceOf(InsertUndoRecord.class, readRecord);
            InsertUndoRecord readInsert = (InsertUndoRecord) readRecord;
            assertEquals(trxId, readInsert.getTrxId());
            assertEquals(1, readInsert.getTableId());
            assertArrayEquals(pk, readInsert.getPrimaryKeyData());
        }

        @Test
        @DisplayName("写入多条 Undo 记录")
        void testWriteMultipleUndoRecords() {
            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, 0);

            // 写入多条记录
            int recordCount = 10;
            List<Integer> offsets = new ArrayList<>();

            for (int i = 0; i < recordCount; i++) {
                byte[] pk = new byte[]{(byte) i};
                InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, i, pk);
                int offset = UndoPage.writeUndoRecord(pageBuffer, undoRecord);
                assertTrue(offset > 0, "Failed to write record " + i);
                offsets.add(offset);
            }

            // 验证
            assertEquals(recordCount, UndoPage.getRecordCount(pageBuffer));

            // 读取验证每条记录
            for (int i = 0; i < recordCount; i++) {
                UndoRecord record = UndoPage.readUndoRecord(pageBuffer, offsets.get(i));
                assertInstanceOf(InsertUndoRecord.class, record);
                InsertUndoRecord insertRecord = (InsertUndoRecord) record;
                assertEquals(i, insertRecord.getTableId());
            }
        }

        @Test
        @DisplayName("写入 UPDATE Undo 记录")
        void testWriteUpdateUndo() {
            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_UPDATE, trxId, 0);

            // 创建 UPDATE Undo 记录
            byte[] pk = new byte[]{0x10, 0x20};
            List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
            oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{0x01}));
            oldCols.add(new UpdateUndoRecord.OldColumnValue(3, new byte[]{0x03, 0x04}));

            RollbackPointer prevPtr = RollbackPointer.forUpdate(5, 100, 50);
            UpdateUndoRecord undoRecord = new UpdateUndoRecord(trxId, 2, prevPtr, pk, oldCols);

            // 写入
            int offset = UndoPage.writeUndoRecord(pageBuffer, undoRecord);
            assertTrue(offset > 0);

            // 读取验证
            UndoRecord readRecord = UndoPage.readUndoRecord(pageBuffer, offset);
            assertInstanceOf(UpdateUndoRecord.class, readRecord);
            UpdateUndoRecord readUpdate = (UpdateUndoRecord) readRecord;
            assertEquals(trxId, readUpdate.getTrxId());
            assertEquals(2, readUpdate.getTableId());
            assertEquals(prevPtr, readUpdate.getPrevUndoPtr());
            assertArrayEquals(pk, readUpdate.getPrimaryKeyData());
            assertEquals(2, readUpdate.getColumnCount());
        }

        @Test
        @DisplayName("写入 DELETE Undo 记录")
        void testWriteDeleteUndo() {
            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_UPDATE, trxId, 0);

            // 创建 DELETE Undo 记录
            byte[] pk = new byte[]{0x10};
            byte[] oldRow = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            RollbackPointer prevPtr = RollbackPointer.forUpdate(3, 200, 100);

            DeleteUndoRecord undoRecord = new DeleteUndoRecord(trxId, 5, prevPtr, pk, oldRow);

            // 写入
            int offset = UndoPage.writeUndoRecord(pageBuffer, undoRecord);
            assertTrue(offset > 0);

            // 读取验证
            UndoRecord readRecord = UndoPage.readUndoRecord(pageBuffer, offset);
            assertInstanceOf(DeleteUndoRecord.class, readRecord);
            DeleteUndoRecord readDelete = (DeleteUndoRecord) readRecord;
            assertEquals(trxId, readDelete.getTrxId());
            assertEquals(5, readDelete.getTableId());
            assertArrayEquals(pk, readDelete.getPrimaryKeyData());
            assertArrayEquals(oldRow, readDelete.getOldRowData());
        }

        @Test
        @DisplayName("页面空间不足时返回 -1")
        void testWriteWhenNoSpace() {
            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, 0);

            // 写入大量记录直到空间不足
            int writeCount = 0;
            while (true) {
                // 创建较大的记录
                byte[] pk = new byte[500];
                InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, writeCount, pk);

                int offset = UndoPage.writeUndoRecord(pageBuffer, undoRecord);
                if (offset < 0) {
                    break;
                }
                writeCount++;
            }

            // 确认写入了一些记录后空间不足
            assertTrue(writeCount > 0);
            assertTrue(writeCount < 100); // 确保不是无限循环
        }

        @Test
        @DisplayName("使用 writeUndoRecordWithPointer 获取 RollbackPointer")
        void testWriteUndoRecordWithPointer() {
            TransactionId trxId = new TransactionId(100);
            int rsegId = 7;
            int pageNo = 1234;

            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, rsegId);

            byte[] pk = new byte[]{0x01};
            InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, 1, pk);

            RollbackPointer rollPtr = UndoPage.writeUndoRecordWithPointer(
                    pageBuffer, undoRecord, rsegId, pageNo);

            assertNotNull(rollPtr);
            assertTrue(rollPtr.isInsert());
            assertEquals(rsegId, rollPtr.getRsegId());
            assertEquals(pageNo, rollPtr.getPageNo());
            assertTrue(rollPtr.getOffset() > 0);
        }

        @Test
        @DisplayName("迭代器正向遍历")
        void testForwardIterator() {
            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, 0);

            // 写入多条记录
            int recordCount = 5;
            for (int i = 0; i < recordCount; i++) {
                byte[] pk = new byte[]{(byte) i};
                InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, i, pk);
                UndoPage.writeUndoRecord(pageBuffer, undoRecord);
            }

            // 正向遍历
            UndoPage.UndoRecordIterator iter = UndoPage.iterator(pageBuffer);
            int count = 0;
            while (iter.hasNext()) {
                UndoRecord record = iter.next();
                assertEquals(count, record.getTableId());
                count++;
            }
            assertEquals(recordCount, count);
        }

        @Test
        @DisplayName("获取最后一条记录")
        void testReadLastUndoRecord() {
            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, 0);

            // 空页面返回 null
            assertNull(UndoPage.readLastUndoRecord(pageBuffer));

            // 写入记录
            byte[] pk = new byte[]{0x01};
            InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, 99, pk);
            UndoPage.writeUndoRecord(pageBuffer, undoRecord);

            // 获取最后一条
            UndoRecord lastRecord = UndoPage.readLastUndoRecord(pageBuffer);
            assertNotNull(lastRecord);
            assertEquals(99, lastRecord.getTableId());
        }
    }

    // ==================== UndoSegment 测试 ====================

    @Nested
    @DisplayName("UndoSegment Tests")
    class UndoSegmentTest {

        private TransactionId trxId;
        private int rsegId;
        private MockUndoPageSupplier pageSupplier;

        @BeforeEach
        void setUp() {
            trxId = new TransactionId(100);
            rsegId = 5;
            pageSupplier = new MockUndoPageSupplier();
        }

        @Test
        @DisplayName("创建 INSERT UndoSegment")
        void testCreateInsertSegment() {
            UndoSegment segment = new UndoSegment(trxId, rsegId, true);

            assertEquals(trxId, segment.getTrxId());
            assertEquals(rsegId, segment.getRsegId());
            assertTrue(segment.isInsert());
            assertEquals(UndoSegment.State.ACTIVE, segment.getState());
            assertTrue(segment.isEmpty());
            assertEquals(0, segment.getRecordCount());
        }

        @Test
        @DisplayName("创建 UPDATE UndoSegment")
        void testCreateUpdateSegment() {
            UndoSegment segment = new UndoSegment(trxId, rsegId, false);

            assertFalse(segment.isInsert());
        }

        @Test
        @DisplayName("写入单条 INSERT Undo")
        void testWriteSingleInsertUndo() {
            UndoSegment segment = new UndoSegment(trxId, rsegId, true);

            byte[] pk = new byte[]{0x01, 0x02};
            InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, 1, pk);

            RollbackPointer rollPtr = segment.writeUndoRecord(undoRecord, pageSupplier);

            assertNotNull(rollPtr);
            assertTrue(rollPtr.isInsert());
            assertEquals(rsegId, rollPtr.getRsegId());
            assertEquals(1, segment.getRecordCount());
            assertEquals(1, segment.getPageCount());
            assertEquals(rollPtr, segment.getLastUndoPtr());
            assertEquals(rollPtr, segment.getFirstUndoPtr());
        }

        @Test
        @DisplayName("写入多条 INSERT Undo")
        void testWriteMultipleInsertUndo() {
            UndoSegment segment = new UndoSegment(trxId, rsegId, true);

            int count = 10;
            RollbackPointer firstPtr = null;
            RollbackPointer lastPtr = null;

            for (int i = 0; i < count; i++) {
                byte[] pk = new byte[]{(byte) i};
                InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, i, pk);
                RollbackPointer ptr = segment.writeUndoRecord(undoRecord, pageSupplier);

                if (i == 0) {
                    firstPtr = ptr;
                }
                lastPtr = ptr;
            }

            assertEquals(count, segment.getRecordCount());
            assertEquals(firstPtr, segment.getFirstUndoPtr());
            assertEquals(lastPtr, segment.getLastUndoPtr());
        }

        @Test
        @DisplayName("类型不匹配抛出异常")
        void testTypeMismatchThrowsException() {
            UndoSegment insertSegment = new UndoSegment(trxId, rsegId, true);

            // 尝试写入 UPDATE Undo 到 INSERT Segment
            List<UpdateUndoRecord.OldColumnValue> oldCols = List.of();
            UpdateUndoRecord updateRecord = new UpdateUndoRecord(
                    trxId, 1, RollbackPointer.NULL, new byte[]{0x01}, oldCols);

            assertThrows(IllegalArgumentException.class, () ->
                    insertSegment.writeUndoRecord(updateRecord, pageSupplier));
        }

        @Test
        @DisplayName("状态转换: ACTIVE -> COMMITTED")
        void testStateTransitionToCommitted() {
            UndoSegment segment = new UndoSegment(trxId, rsegId, true);

            assertEquals(UndoSegment.State.ACTIVE, segment.getState());

            segment.markCommitted();

            assertEquals(UndoSegment.State.COMMITTED, segment.getState());

            // 提交后不能再写入
            InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, 1, new byte[]{0x01});
            assertThrows(IllegalStateException.class, () ->
                    segment.writeUndoRecord(undoRecord, pageSupplier));
        }

        @Test
        @DisplayName("状态转换: ACTIVE -> ROLLED_BACK")
        void testStateTransitionToRolledBack() {
            UndoSegment segment = new UndoSegment(trxId, rsegId, true);

            segment.markRolledBack();

            assertEquals(UndoSegment.State.ROLLED_BACK, segment.getState());
        }

        @Test
        @DisplayName("反向遍历 Undo 记录")
        void testReverseIteration() {
            UndoSegment segment = new UndoSegment(trxId, rsegId, true);

            // 写入记录
            int count = 5;
            for (int i = 0; i < count; i++) {
                byte[] pk = new byte[]{(byte) i};
                InsertUndoRecord undoRecord = new InsertUndoRecord(trxId, i, pk);
                segment.writeUndoRecord(undoRecord, pageSupplier);
            }

            // 反向遍历
            List<Integer> tableIds = new ArrayList<>();
            for (UndoRecord record : segment.reverseIterate(pageSupplier::readPage)) {
                tableIds.add(record.getTableId());
            }

            // 验证顺序是反向的
            assertEquals(count, tableIds.size());
            for (int i = 0; i < count; i++) {
                assertEquals(count - 1 - i, tableIds.get(i));
            }
        }

        // ==================== Mock 类 ====================

        /**
         * 模拟 Undo Page 提供器
         */
        private class MockUndoPageSupplier implements UndoSegment.UndoPageSupplier {
            private int nextPageNo = 100;
            private final java.util.Map<Integer, ByteBuffer> pages = new java.util.HashMap<>();

            @Override
            public UndoSegment.UndoPageInfo allocateUndoPage(int undoType,
                                                              TransactionId trxId,
                                                              int rsegId) {
                int pageNo = nextPageNo++;
                ByteBuffer buf = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                UndoPage.init(buf, undoType, trxId, rsegId);
                pages.put(pageNo, buf);
                return new UndoSegment.UndoPageInfo(pageNo, buf);
            }

            public ByteBuffer readPage(int pageNo) {
                return pages.get(pageNo);
            }
        }
    }

    // ==================== Undo Record 序列化测试 ====================

    @Nested
    @DisplayName("UndoRecord Serialization Tests")
    class UndoRecordSerializationTest {

        @Test
        @DisplayName("InsertUndoRecord 序列化和反序列化")
        void testInsertUndoRecordSerialization() {
            TransactionId trxId = new TransactionId(12345);
            byte[] pk = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            InsertUndoRecord original = new InsertUndoRecord(trxId, 100, pk);

            // 序列化
            int size = original.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(size);
            original.writeTo(buf, 0);

            // 反序列化
            UndoRecord restored = UndoRecord.readFrom(buf, 0);

            // 验证
            assertInstanceOf(InsertUndoRecord.class, restored);
            InsertUndoRecord restoredInsert = (InsertUndoRecord) restored;
            assertEquals(UndoRecordType.INSERT, restoredInsert.getType());
            assertEquals(trxId, restoredInsert.getTrxId());
            assertEquals(100, restoredInsert.getTableId());
            assertArrayEquals(pk, restoredInsert.getPrimaryKeyData());
            assertEquals(RollbackPointer.NULL, restoredInsert.getPrevUndoPtr());
        }

        @Test
        @DisplayName("UpdateUndoRecord 序列化和反序列化")
        void testUpdateUndoRecordSerialization() {
            TransactionId trxId = new TransactionId(54321);
            byte[] pk = new byte[]{0x10, 0x20};
            RollbackPointer prevPtr = RollbackPointer.forUpdate(10, 500, 200);

            List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
            oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{0x01, 0x02}));
            oldCols.add(new UpdateUndoRecord.OldColumnValue(5, new byte[]{0x05}));
            oldCols.add(new UpdateUndoRecord.OldColumnValue(10, new byte[]{0x0A, 0x0B, 0x0C}));

            UpdateUndoRecord original = new UpdateUndoRecord(trxId, 200, prevPtr, pk, oldCols);

            // 序列化
            int size = original.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(size);
            original.writeTo(buf, 0);

            // 反序列化
            UndoRecord restored = UndoRecord.readFrom(buf, 0);

            // 验证
            assertInstanceOf(UpdateUndoRecord.class, restored);
            UpdateUndoRecord restoredUpdate = (UpdateUndoRecord) restored;
            assertEquals(UndoRecordType.UPDATE, restoredUpdate.getType());
            assertEquals(trxId, restoredUpdate.getTrxId());
            assertEquals(200, restoredUpdate.getTableId());
            assertEquals(prevPtr, restoredUpdate.getPrevUndoPtr());
            assertArrayEquals(pk, restoredUpdate.getPrimaryKeyData());
            assertEquals(3, restoredUpdate.getColumnCount());

            // 验证旧列值
            List<UpdateUndoRecord.OldColumnValue> restoredCols = restoredUpdate.getOldColumns();
            assertEquals(1, restoredCols.get(0).columnId);
            assertArrayEquals(new byte[]{0x01, 0x02}, restoredCols.get(0).value);
            assertEquals(5, restoredCols.get(1).columnId);
            assertEquals(10, restoredCols.get(2).columnId);
        }

        @Test
        @DisplayName("DeleteUndoRecord 序列化和反序列化")
        void testDeleteUndoRecordSerialization() {
            TransactionId trxId = new TransactionId(99999);
            byte[] pk = new byte[]{0x01};
            byte[] oldRow = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
            RollbackPointer prevPtr = RollbackPointer.forUpdate(50, 1000, 500);

            DeleteUndoRecord original = new DeleteUndoRecord(trxId, 300, prevPtr, pk, oldRow);

            // 序列化
            int size = original.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(size);
            original.writeTo(buf, 0);

            // 反序列化
            UndoRecord restored = UndoRecord.readFrom(buf, 0);

            // 验证
            assertInstanceOf(DeleteUndoRecord.class, restored);
            DeleteUndoRecord restoredDelete = (DeleteUndoRecord) restored;
            assertEquals(UndoRecordType.DELETE_MARK, restoredDelete.getType());
            assertEquals(trxId, restoredDelete.getTrxId());
            assertEquals(300, restoredDelete.getTableId());
            assertEquals(prevPtr, restoredDelete.getPrevUndoPtr());
            assertArrayEquals(pk, restoredDelete.getPrimaryKeyData());
            assertArrayEquals(oldRow, restoredDelete.getOldRowData());
        }

        @Test
        @DisplayName("UndoRecord peekLength 和 peekType")
        void testPeekMethods() {
            TransactionId trxId = new TransactionId(100);
            byte[] pk = new byte[]{0x01, 0x02, 0x03};
            InsertUndoRecord record = new InsertUndoRecord(trxId, 1, pk);

            int expectedSize = record.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(expectedSize);
            record.writeTo(buf, 0);

            // 测试 peek 方法
            int peekedLength = UndoRecord.peekLength(buf, 0);
            UndoRecordType peekedType = UndoRecord.peekType(buf, 0);

            assertEquals(expectedSize, peekedLength);
            assertEquals(UndoRecordType.INSERT, peekedType);
        }
    }
}
