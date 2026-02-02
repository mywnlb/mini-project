package cn.zhangyis.minidb.storage.transaction;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Undo Log 单元测试
 *
 * <p>测试 UndoRecord 及其子类的编解码正确性</p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("Undo Log Tests")
class UndoLogTest {

    // ==================== UndoRecordType 测试 ====================

    @Nested
    @DisplayName("UndoRecordType Tests")
    class UndoRecordTypeTest {

        @Test
        @DisplayName("类型码映射")
        void testTypeCodes() {
            assertEquals(0x0B, UndoRecordType.INSERT.getCode());
            assertEquals(0x0C, UndoRecordType.UPDATE.getCode());
            assertEquals(0x0D, UndoRecordType.DELETE_MARK.getCode());
        }

        @Test
        @DisplayName("fromCode 转换")
        void testFromCode() {
            assertEquals(UndoRecordType.INSERT, UndoRecordType.fromCode(0x0B));
            assertEquals(UndoRecordType.UPDATE, UndoRecordType.fromCode(0x0C));
            assertEquals(UndoRecordType.DELETE_MARK, UndoRecordType.fromCode(0x0D));
        }

        @Test
        @DisplayName("fromCode 未知类型")
        void testFromCodeUnknown() {
            assertThrows(IllegalArgumentException.class, () ->
                    UndoRecordType.fromCode(0x00));
            assertThrows(IllegalArgumentException.class, () ->
                    UndoRecordType.fromCode(0xFF));
        }

        @Test
        @DisplayName("类型判断方法")
        void testTypeChecks() {
            assertTrue(UndoRecordType.INSERT.isInsert());
            assertFalse(UndoRecordType.INSERT.isUpdateUndo());

            assertTrue(UndoRecordType.UPDATE.isUpdate());
            assertTrue(UndoRecordType.UPDATE.isUpdateUndo());

            assertTrue(UndoRecordType.DELETE_MARK.isDelete());
            assertTrue(UndoRecordType.DELETE_MARK.isUpdateUndo());
        }
    }

    // ==================== InsertUndoRecord 测试 ====================

    @Nested
    @DisplayName("InsertUndoRecord Tests")
    class InsertUndoRecordTest {

        @Test
        @DisplayName("创建和访问")
        void testCreateAndAccess() {
            TransactionId trxId = new TransactionId(12345L);
            byte[] pkData = new byte[]{1, 2, 3, 4};

            InsertUndoRecord record = new InsertUndoRecord(trxId, 100, pkData);

            assertEquals(UndoRecordType.INSERT, record.getType());
            assertEquals(trxId, record.getTrxId());
            assertEquals(100, record.getTableId());
            assertTrue(record.getPrevUndoPtr().isNull());
            assertArrayEquals(pkData, record.getPrimaryKeyData());
            assertEquals(4, record.getPrimaryKeyLength());
        }

        @Test
        @DisplayName("编解码 - 典型值")
        void testCodecTypical() {
            TransactionId trxId = new TransactionId(0x123456789ABCL);
            byte[] pkData = new byte[]{10, 20, 30, 40, 50};

            InsertUndoRecord original = new InsertUndoRecord(trxId, 200, pkData);

            // 编码
            int size = original.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(size + 10);
            int written = original.writeTo(buf, 0);

            assertEquals(size, written);

            // 解码
            UndoRecord restored = UndoRecord.readFrom(buf, 0);

            assertTrue(restored instanceof InsertUndoRecord);
            InsertUndoRecord insertRestored = (InsertUndoRecord) restored;

            assertEquals(original.getTrxId(), insertRestored.getTrxId());
            assertEquals(original.getTableId(), insertRestored.getTableId());
            assertArrayEquals(original.getPrimaryKeyData(), insertRestored.getPrimaryKeyData());
        }

        @Test
        @DisplayName("编解码 - 空主键")
        void testCodecEmptyPk() {
            TransactionId trxId = new TransactionId(1L);
            byte[] pkData = new byte[0];

            InsertUndoRecord original = new InsertUndoRecord(trxId, 1, pkData);

            ByteBuffer buf = ByteBuffer.allocate(64);
            original.writeTo(buf, 0);

            InsertUndoRecord restored = (InsertUndoRecord) UndoRecord.readFrom(buf, 0);

            assertEquals(0, restored.getPrimaryKeyLength());
        }

        @Test
        @DisplayName("编解码 - 大主键")
        void testCodecLargePk() {
            TransactionId trxId = new TransactionId(999L);
            byte[] pkData = new byte[1000];
            Arrays.fill(pkData, (byte) 0xAB);

            InsertUndoRecord original = new InsertUndoRecord(trxId, 500, pkData);

            ByteBuffer buf = ByteBuffer.allocate(2048);
            original.writeTo(buf, 0);

            InsertUndoRecord restored = (InsertUndoRecord) UndoRecord.readFrom(buf, 0);

            assertArrayEquals(pkData, restored.getPrimaryKeyData());
        }

        @Test
        @DisplayName("回滚描述")
        void testRollbackDescription() {
            InsertUndoRecord record = new InsertUndoRecord(
                    new TransactionId(1L), 100, new byte[10]);

            String desc = record.getRollbackDescription();
            assertTrue(desc.contains("DELETE"));
            assertTrue(desc.contains("100"));
            assertTrue(desc.contains("10"));
        }
    }

    // ==================== UpdateUndoRecord 测试 ====================

    @Nested
    @DisplayName("UpdateUndoRecord Tests")
    class UpdateUndoRecordTest {

        @Test
        @DisplayName("创建和访问")
        void testCreateAndAccess() {
            TransactionId trxId = new TransactionId(12345L);
            RollbackPointer prevPtr = RollbackPointer.forUpdate(5, 1000, 200);
            byte[] pkData = new byte[]{1, 2, 3, 4};
            List<UpdateUndoRecord.OldColumnValue> oldCols = List.of(
                    new UpdateUndoRecord.OldColumnValue(1, new byte[]{10, 20}),
                    new UpdateUndoRecord.OldColumnValue(3, new byte[]{30, 40, 50})
            );

            UpdateUndoRecord record = new UpdateUndoRecord(
                    trxId, 100, prevPtr, pkData, oldCols);

            assertEquals(UndoRecordType.UPDATE, record.getType());
            assertEquals(trxId, record.getTrxId());
            assertEquals(100, record.getTableId());
            assertEquals(prevPtr, record.getPrevUndoPtr());
            assertArrayEquals(pkData, record.getPrimaryKeyData());
            assertEquals(2, record.getColumnCount());
        }

        @Test
        @DisplayName("编解码 - 典型值")
        void testCodecTypical() {
            TransactionId trxId = new TransactionId(0xABCDEF123456L);
            RollbackPointer prevPtr = RollbackPointer.forUpdate(10, 5000, 300);
            byte[] pkData = new byte[]{5, 6, 7, 8};
            List<UpdateUndoRecord.OldColumnValue> oldCols = List.of(
                    new UpdateUndoRecord.OldColumnValue(0, new byte[]{1}),
                    new UpdateUndoRecord.OldColumnValue(5, new byte[]{2, 3, 4, 5, 6}),
                    new UpdateUndoRecord.OldColumnValue(10, new byte[]{7, 8})
            );

            UpdateUndoRecord original = new UpdateUndoRecord(
                    trxId, 200, prevPtr, pkData, oldCols);

            // 编码
            int size = original.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(size + 10);
            int written = original.writeTo(buf, 0);

            assertEquals(size, written);

            // 解码
            UpdateUndoRecord restored = (UpdateUndoRecord) UndoRecord.readFrom(buf, 0);

            assertEquals(original.getTrxId(), restored.getTrxId());
            assertEquals(original.getTableId(), restored.getTableId());
            assertEquals(original.getPrevUndoPtr(), restored.getPrevUndoPtr());
            assertArrayEquals(original.getPrimaryKeyData(), restored.getPrimaryKeyData());
            assertEquals(original.getColumnCount(), restored.getColumnCount());

            // 验证每个列
            List<UpdateUndoRecord.OldColumnValue> restoredCols = restored.getOldColumns();
            for (int i = 0; i < oldCols.size(); i++) {
                assertEquals(oldCols.get(i).columnId, restoredCols.get(i).columnId);
                assertArrayEquals(oldCols.get(i).value, restoredCols.get(i).value);
            }
        }

        @Test
        @DisplayName("编解码 - 无列修改")
        void testCodecNoColumns() {
            TransactionId trxId = new TransactionId(1L);
            byte[] pkData = new byte[]{1};

            UpdateUndoRecord original = new UpdateUndoRecord(
                    trxId, 1, RollbackPointer.NULL, pkData, List.of());

            ByteBuffer buf = ByteBuffer.allocate(128);
            original.writeTo(buf, 0);

            UpdateUndoRecord restored = (UpdateUndoRecord) UndoRecord.readFrom(buf, 0);

            assertEquals(0, restored.getColumnCount());
        }

        @Test
        @DisplayName("getOldValue 方法")
        void testGetOldValue() {
            List<UpdateUndoRecord.OldColumnValue> oldCols = List.of(
                    new UpdateUndoRecord.OldColumnValue(5, new byte[]{1, 2}),
                    new UpdateUndoRecord.OldColumnValue(10, new byte[]{3, 4})
            );

            UpdateUndoRecord record = new UpdateUndoRecord(
                    new TransactionId(1L), 1, RollbackPointer.NULL,
                    new byte[]{0}, oldCols);

            assertNotNull(record.getOldValue(5));
            assertNotNull(record.getOldValue(10));
            assertNull(record.getOldValue(15)); // 不存在
        }
    }

    // ==================== DeleteUndoRecord 测试 ====================

    @Nested
    @DisplayName("DeleteUndoRecord Tests")
    class DeleteUndoRecordTest {

        @Test
        @DisplayName("创建和访问")
        void testCreateAndAccess() {
            TransactionId trxId = new TransactionId(12345L);
            RollbackPointer prevPtr = RollbackPointer.forUpdate(3, 500, 100);
            byte[] pkData = new byte[]{1, 2, 3, 4};
            byte[] rowData = new byte[]{10, 20, 30, 40, 50, 60};

            DeleteUndoRecord record = new DeleteUndoRecord(
                    trxId, 100, prevPtr, pkData, rowData);

            assertEquals(UndoRecordType.DELETE_MARK, record.getType());
            assertEquals(trxId, record.getTrxId());
            assertEquals(100, record.getTableId());
            assertEquals(prevPtr, record.getPrevUndoPtr());
            assertArrayEquals(pkData, record.getPrimaryKeyData());
            assertArrayEquals(rowData, record.getOldRowData());
            assertEquals(6, record.getOldRowDataLength());
        }

        @Test
        @DisplayName("编解码 - 典型值")
        void testCodecTypical() {
            TransactionId trxId = new TransactionId(0xFEDCBA987654L);
            RollbackPointer prevPtr = RollbackPointer.forUpdate(50, 10000, 500);
            byte[] pkData = new byte[]{1, 2, 3, 4, 5};
            byte[] rowData = new byte[100];
            Arrays.fill(rowData, (byte) 0xCD);

            DeleteUndoRecord original = new DeleteUndoRecord(
                    trxId, 300, prevPtr, pkData, rowData);

            // 编码
            int size = original.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(size + 10);
            int written = original.writeTo(buf, 0);

            assertEquals(size, written);

            // 解码
            DeleteUndoRecord restored = (DeleteUndoRecord) UndoRecord.readFrom(buf, 0);

            assertEquals(original.getTrxId(), restored.getTrxId());
            assertEquals(original.getTableId(), restored.getTableId());
            assertEquals(original.getPrevUndoPtr(), restored.getPrevUndoPtr());
            assertArrayEquals(original.getPrimaryKeyData(), restored.getPrimaryKeyData());
            assertArrayEquals(original.getOldRowData(), restored.getOldRowData());
        }

        @Test
        @DisplayName("回滚描述")
        void testRollbackDescription() {
            DeleteUndoRecord record = new DeleteUndoRecord(
                    new TransactionId(1L), 100,
                    RollbackPointer.NULL,
                    new byte[5], new byte[50]);

            String desc = record.getRollbackDescription();
            assertTrue(desc.contains("UNDELETE"));
            assertTrue(desc.contains("100"));
        }
    }

    // ==================== UndoRecord 通用测试 ====================

    @Nested
    @DisplayName("UndoRecord Common Tests")
    class UndoRecordCommonTest {

        @Test
        @DisplayName("peekLength 方法")
        void testPeekLength() {
            InsertUndoRecord record = new InsertUndoRecord(
                    new TransactionId(1L), 1, new byte[10]);

            ByteBuffer buf = ByteBuffer.allocate(128);
            record.writeTo(buf, 0);

            int expectedLen = record.calculateSize();
            int peekedLen = UndoRecord.peekLength(buf, 0);

            assertEquals(expectedLen, peekedLen);
        }

        @Test
        @DisplayName("peekType 方法")
        void testPeekType() {
            InsertUndoRecord insertRecord = new InsertUndoRecord(
                    new TransactionId(1L), 1, new byte[5]);
            UpdateUndoRecord updateRecord = new UpdateUndoRecord(
                    new TransactionId(1L), 1, RollbackPointer.NULL,
                    new byte[5], List.of());
            DeleteUndoRecord deleteRecord = new DeleteUndoRecord(
                    new TransactionId(1L), 1, RollbackPointer.NULL, new byte[5]);

            ByteBuffer buf = ByteBuffer.allocate(256);

            insertRecord.writeTo(buf, 0);
            assertEquals(UndoRecordType.INSERT, UndoRecord.peekType(buf, 0));

            updateRecord.writeTo(buf, 0);
            assertEquals(UndoRecordType.UPDATE, UndoRecord.peekType(buf, 0));

            deleteRecord.writeTo(buf, 0);
            assertEquals(UndoRecordType.DELETE_MARK, UndoRecord.peekType(buf, 0));
        }

        @Test
        @DisplayName("readHeader 方法")
        void testReadHeader() {
            TransactionId trxId = new TransactionId(0x123456789ABCL);
            RollbackPointer prevPtr = RollbackPointer.forInsert(10, 1000, 100);

            UpdateUndoRecord record = new UpdateUndoRecord(
                    trxId, 500, prevPtr, new byte[]{1, 2, 3},
                    List.of(new UpdateUndoRecord.OldColumnValue(1, new byte[]{10})));

            ByteBuffer buf = ByteBuffer.allocate(256);
            record.writeTo(buf, 0);

            UndoRecord.UndoRecordHeader header = UndoRecord.readHeader(buf, 0);

            assertEquals(UndoRecordType.UPDATE, header.type());
            assertEquals(trxId, header.trxId());
            assertEquals(500, header.tableId());
            assertEquals(prevPtr, header.prevUndoPtr());
            assertEquals(record.calculateSize(), header.length());
        }
    }

    // ==================== UndoPageHeader 测试 ====================

    @Nested
    @DisplayName("UndoPageHeader Tests")
    class UndoPageHeaderTest {

        @Test
        @DisplayName("初始化和读取")
        void testInitAndRead() {
            ByteBuffer buf = ByteBuffer.allocate(16384);
            TransactionId trxId = new TransactionId(12345L);

            UndoPageHeader.init(buf, UndoPageHeader.UNDO_INSERT, trxId, 5);

            assertEquals(UndoPageHeader.UNDO_INSERT, UndoPageHeader.getUndoType(buf));
            assertEquals(0, UndoPageHeader.getLastLogOffset(buf));
            assertEquals(UndoPageHeader.UNDO_LOG_START, UndoPageHeader.getFreeOffset(buf));
            assertEquals(UndoPageHeader.UNDO_LOG_START, UndoPageHeader.getLogStart(buf));
            assertEquals(trxId, UndoPageHeader.getTrxId(buf));
            assertEquals(5, UndoPageHeader.getRsegId(buf));
            assertEquals(UndoPageHeader.STATE_ACTIVE, UndoPageHeader.getState(buf));
        }

        @Test
        @DisplayName("空闲空间计算")
        void testFreeSpace() {
            ByteBuffer buf = ByteBuffer.allocate(16384);
            UndoPageHeader.init(buf, UndoPageHeader.UNDO_UPDATE,
                    new TransactionId(1L), 0);

            int initialFreeSpace = UndoPageHeader.getFreeSpace(buf);
            assertTrue(initialFreeSpace > 16000); // 大约 16KB - headers

            // 模拟写入一条记录
            int recordSize = 100;
            UndoPageHeader.setFreeOffset(buf,
                    UndoPageHeader.getFreeOffset(buf) + recordSize);

            int afterFreeSpace = UndoPageHeader.getFreeSpace(buf);
            assertEquals(initialFreeSpace - recordSize, afterFreeSpace);
        }

        @Test
        @DisplayName("hasSpace 方法")
        void testHasSpace() {
            ByteBuffer buf = ByteBuffer.allocate(16384);
            UndoPageHeader.init(buf, UndoPageHeader.UNDO_INSERT,
                    new TransactionId(1L), 0);

            assertTrue(UndoPageHeader.hasSpace(buf, 1000));
            assertTrue(UndoPageHeader.hasSpace(buf, 15000));
            assertFalse(UndoPageHeader.hasSpace(buf, 20000)); // 超出页面大小
        }

        @Test
        @DisplayName("类型名称")
        void testTypeNames() {
            assertEquals("INSERT", UndoPageHeader.getUndoTypeName(UndoPageHeader.UNDO_INSERT));
            assertEquals("UPDATE", UndoPageHeader.getUndoTypeName(UndoPageHeader.UNDO_UPDATE));
        }

        @Test
        @DisplayName("状态名称")
        void testStateNames() {
            assertEquals("ACTIVE", UndoPageHeader.getStateName(UndoPageHeader.STATE_ACTIVE));
            assertEquals("CACHED", UndoPageHeader.getStateName(UndoPageHeader.STATE_CACHED));
            assertEquals("TO_FREE", UndoPageHeader.getStateName(UndoPageHeader.STATE_TO_FREE));
            assertEquals("TO_PURGE", UndoPageHeader.getStateName(UndoPageHeader.STATE_TO_PURGE));
        }

        @Test
        @DisplayName("dump 方法")
        void testDump() {
            ByteBuffer buf = ByteBuffer.allocate(16384);
            UndoPageHeader.init(buf, UndoPageHeader.UNDO_INSERT,
                    new TransactionId(12345L), 7);

            String dump = UndoPageHeader.dump(buf);

            assertTrue(dump.contains("INSERT"));
            assertTrue(dump.contains("12345"));
            assertTrue(dump.contains("rsegId=7"));
            assertTrue(dump.contains("ACTIVE"));
        }
    }
}
