package cn.zhangyis.minidb.storage.transaction;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事务核心数据结构单元测试
 *
 * <p>测试 TransactionId 和 RollbackPointer 的编解码正确性</p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("Transaction Core Data Structures")
class TransactionCoreTest {

    // ==================== TransactionId 测试 ====================

    @Nested
    @DisplayName("TransactionId Tests")
    class TransactionIdTest {

        @Test
        @DisplayName("创建有效的事务ID")
        void testCreateValidTrxId() {
            TransactionId trxId = new TransactionId(12345L);
            assertEquals(12345L, trxId.getValue());
            assertTrue(trxId.isValid());
        }

        @Test
        @DisplayName("创建无效的事务ID (0)")
        void testInvalidTrxId() {
            TransactionId trxId = new TransactionId(0);
            assertFalse(trxId.isValid());
            assertEquals(TransactionId.INVALID, trxId);
        }

        @Test
        @DisplayName("事务ID最大值边界")
        void testMaxTrxId() {
            long maxValue = TransactionId.MAX_VALUE;
            TransactionId trxId = new TransactionId(maxValue);
            assertEquals(maxValue, trxId.getValue());
            assertEquals(TransactionId.MAX, trxId);
        }

        @Test
        @DisplayName("事务ID溢出检测")
        void testTrxIdOverflow() {
            assertThrows(IllegalArgumentException.class, () ->
                    new TransactionId(TransactionId.MAX_VALUE + 1));
            assertThrows(IllegalArgumentException.class, () ->
                    new TransactionId(-1));
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - 小值")
        void testByteBufferCodecSmallValue() {
            TransactionId original = new TransactionId(1);
            ByteBuffer buf = ByteBuffer.allocate(16);

            original.writeTo(buf, 0);
            TransactionId restored = TransactionId.readFrom(buf, 0);

            assertEquals(original, restored);
            assertEquals(1L, restored.getValue());
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - 典型值")
        void testByteBufferCodecTypicalValue() {
            TransactionId original = new TransactionId(0x123456789ABCL);
            ByteBuffer buf = ByteBuffer.allocate(16);

            original.writeTo(buf, 0);
            TransactionId restored = TransactionId.readFrom(buf, 0);

            assertEquals(original, restored);
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - 最大值")
        void testByteBufferCodecMaxValue() {
            TransactionId original = TransactionId.MAX;
            ByteBuffer buf = ByteBuffer.allocate(16);

            original.writeTo(buf, 0);
            TransactionId restored = TransactionId.readFrom(buf, 0);

            assertEquals(original, restored);
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - 非零偏移")
        void testByteBufferCodecWithOffset() {
            TransactionId original = new TransactionId(0xABCDEF123456L);
            ByteBuffer buf = ByteBuffer.allocate(32);
            int offset = 10;

            original.writeTo(buf, offset);
            TransactionId restored = TransactionId.readFrom(buf, offset);

            assertEquals(original, restored);
        }

        @Test
        @DisplayName("字节数组编解码")
        void testByteArrayCodec() {
            TransactionId original = new TransactionId(0xFEDCBA987654L);
            byte[] bytes = new byte[16];

            original.writeTo(bytes, 0);
            TransactionId restored = TransactionId.readFrom(bytes, 0);

            assertEquals(original, restored);
        }

        @Test
        @DisplayName("Big-Endian 字节序验证")
        void testBigEndianByteOrder() {
            // 值: 0x010203040506 (高字节在前)
            TransactionId trxId = new TransactionId(0x010203040506L);
            ByteBuffer buf = ByteBuffer.allocate(8);

            trxId.writeTo(buf, 0);

            // 验证 Big-Endian 顺序
            assertEquals((byte) 0x01, buf.get(0));
            assertEquals((byte) 0x02, buf.get(1));
            assertEquals((byte) 0x03, buf.get(2));
            assertEquals((byte) 0x04, buf.get(3));
            assertEquals((byte) 0x05, buf.get(4));
            assertEquals((byte) 0x06, buf.get(5));
        }

        @Test
        @DisplayName("事务ID比较")
        void testTrxIdComparison() {
            TransactionId trx1 = new TransactionId(100);
            TransactionId trx2 = new TransactionId(200);
            TransactionId trx3 = new TransactionId(100);

            assertTrue(trx1.compareTo(trx2) < 0);
            assertTrue(trx2.compareTo(trx1) > 0);
            assertEquals(0, trx1.compareTo(trx3));

            assertTrue(trx1.isBefore(trx2));
            assertFalse(trx2.isBefore(trx1));
            assertTrue(trx2.isAfterOrEqual(trx1));
            assertTrue(trx1.isAfterOrEqual(trx3));
        }

        @Test
        @DisplayName("next() 方法")
        void testNext() {
            TransactionId trx = new TransactionId(100);
            TransactionId next = trx.next();

            assertEquals(101L, next.getValue());
        }

        @Test
        @DisplayName("next() 溢出检测")
        void testNextOverflow() {
            TransactionId maxTrx = TransactionId.MAX;
            assertThrows(IllegalStateException.class, maxTrx::next);
        }

        @Test
        @DisplayName("equals 和 hashCode")
        void testEqualsAndHashCode() {
            TransactionId trx1 = new TransactionId(12345L);
            TransactionId trx2 = new TransactionId(12345L);
            TransactionId trx3 = new TransactionId(54321L);

            assertEquals(trx1, trx2);
            assertNotEquals(trx1, trx3);
            assertEquals(trx1.hashCode(), trx2.hashCode());
        }

        @Test
        @DisplayName("toString 格式")
        void testToString() {
            TransactionId trx = new TransactionId(12345L);
            String str = trx.toString();

            assertTrue(str.contains("12345"));
        }
    }

    // ==================== RollbackPointer 测试 ====================

    @Nested
    @DisplayName("RollbackPointer Tests")
    class RollbackPointerTest {

        @Test
        @DisplayName("创建 INSERT Undo 指针")
        void testCreateInsertUndoPointer() {
            RollbackPointer ptr = RollbackPointer.forInsert(5, 1234, 100);

            assertTrue(ptr.isInsert());
            assertFalse(ptr.isUpdate());
            assertEquals(5, ptr.getRsegId());
            assertEquals(1234, ptr.getPageNo());
            assertEquals(100, ptr.getOffset());
            assertFalse(ptr.isNull());
        }

        @Test
        @DisplayName("创建 UPDATE Undo 指针")
        void testCreateUpdateUndoPointer() {
            RollbackPointer ptr = RollbackPointer.forUpdate(10, 5678, 200);

            assertFalse(ptr.isInsert());
            assertTrue(ptr.isUpdate());
            assertEquals(10, ptr.getRsegId());
            assertEquals(5678, ptr.getPageNo());
            assertEquals(200, ptr.getOffset());
        }

        @Test
        @DisplayName("空指针")
        void testNullPointer() {
            RollbackPointer nullPtr = RollbackPointer.NULL;

            assertTrue(nullPtr.isNull());
            assertEquals(0, nullPtr.getPageNo());
            assertEquals(0, nullPtr.getOffset());
        }

        @Test
        @DisplayName("rseg_id 边界检查")
        void testRsegIdBoundary() {
            // 有效值: 0 ~ 127
            assertDoesNotThrow(() -> new RollbackPointer(true, 0, 100, 100));
            assertDoesNotThrow(() -> new RollbackPointer(true, 127, 100, 100));

            // 无效值
            assertThrows(IllegalArgumentException.class, () ->
                    new RollbackPointer(true, -1, 100, 100));
            assertThrows(IllegalArgumentException.class, () ->
                    new RollbackPointer(true, 128, 100, 100));
        }

        @Test
        @DisplayName("offset 边界检查")
        void testOffsetBoundary() {
            // 有效值: 0 ~ 65535
            assertDoesNotThrow(() -> new RollbackPointer(true, 0, 100, 0));
            assertDoesNotThrow(() -> new RollbackPointer(true, 0, 100, 65535));

            // 无效值
            assertThrows(IllegalArgumentException.class, () ->
                    new RollbackPointer(true, 0, 100, -1));
            assertThrows(IllegalArgumentException.class, () ->
                    new RollbackPointer(true, 0, 100, 65536));
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - INSERT")
        void testByteBufferCodecInsert() {
            RollbackPointer original = RollbackPointer.forInsert(63, 0x12345678, 0xABCD);
            ByteBuffer buf = ByteBuffer.allocate(16);

            original.writeTo(buf, 0);
            RollbackPointer restored = RollbackPointer.readFrom(buf, 0);

            assertEquals(original, restored);
            assertTrue(restored.isInsert());
            assertEquals(63, restored.getRsegId());
            assertEquals(0x12345678, restored.getPageNo());
            assertEquals(0xABCD, restored.getOffset());
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - UPDATE")
        void testByteBufferCodecUpdate() {
            RollbackPointer original = RollbackPointer.forUpdate(100, 0x87654321, 0x1234);
            ByteBuffer buf = ByteBuffer.allocate(16);

            original.writeTo(buf, 0);
            RollbackPointer restored = RollbackPointer.readFrom(buf, 0);

            assertEquals(original, restored);
            assertFalse(restored.isInsert());
            assertEquals(100, restored.getRsegId());
            assertEquals(0x87654321, restored.getPageNo());
            assertEquals(0x1234, restored.getOffset());
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - NULL 指针")
        void testByteBufferCodecNull() {
            RollbackPointer original = RollbackPointer.NULL;
            ByteBuffer buf = ByteBuffer.allocate(16);

            original.writeTo(buf, 0);
            RollbackPointer restored = RollbackPointer.readFrom(buf, 0);

            assertTrue(restored.isNull());
        }

        @Test
        @DisplayName("ByteBuffer 编解码 - 非零偏移")
        void testByteBufferCodecWithOffset() {
            RollbackPointer original = RollbackPointer.forInsert(127, 0xFFFFFFFF, 0xFFFF);
            ByteBuffer buf = ByteBuffer.allocate(32);
            int offset = 15;

            original.writeTo(buf, offset);
            RollbackPointer restored = RollbackPointer.readFrom(buf, offset);

            assertEquals(original, restored);
        }

        @Test
        @DisplayName("字节数组编解码")
        void testByteArrayCodec() {
            RollbackPointer original = RollbackPointer.forUpdate(50, 999999, 12345);
            byte[] bytes = new byte[16];

            original.writeTo(bytes, 0);
            RollbackPointer restored = RollbackPointer.readFrom(bytes, 0);

            assertEquals(original, restored);
        }

        @Test
        @DisplayName("字节布局验证")
        void testByteLayout() {
            // is_insert=1, rseg_id=0x3F(63), page_no=0x01020304, offset=0x0506
            RollbackPointer ptr = new RollbackPointer(true, 63, 0x01020304, 0x0506);
            ByteBuffer buf = ByteBuffer.allocate(8);

            ptr.writeTo(buf, 0);

            // Byte 0: [is_insert(1) | rseg_id(7)] = 0x80 | 0x3F = 0xBF
            assertEquals((byte) 0xBF, buf.get(0));

            // Byte 1-4: page_no (Big-Endian)
            assertEquals((byte) 0x01, buf.get(1));
            assertEquals((byte) 0x02, buf.get(2));
            assertEquals((byte) 0x03, buf.get(3));
            assertEquals((byte) 0x04, buf.get(4));

            // Byte 5-6: offset (Big-Endian)
            assertEquals((byte) 0x05, buf.get(5));
            assertEquals((byte) 0x06, buf.get(6));
        }

        @Test
        @DisplayName("is_insert 位正确性")
        void testIsInsertBit() {
            ByteBuffer buf = ByteBuffer.allocate(8);

            // INSERT 指针
            RollbackPointer insertPtr = RollbackPointer.forInsert(0, 0, 0);
            insertPtr.writeTo(buf, 0);
            assertEquals((byte) 0x80, (byte) (buf.get(0) & 0x80)); // 最高位为1

            // UPDATE 指针
            RollbackPointer updatePtr = RollbackPointer.forUpdate(0, 0, 0);
            updatePtr.writeTo(buf, 0);
            assertEquals((byte) 0x00, (byte) (buf.get(0) & 0x80)); // 最高位为0
        }

        @Test
        @DisplayName("equals 和 hashCode")
        void testEqualsAndHashCode() {
            RollbackPointer ptr1 = RollbackPointer.forInsert(10, 1000, 500);
            RollbackPointer ptr2 = RollbackPointer.forInsert(10, 1000, 500);
            RollbackPointer ptr3 = RollbackPointer.forUpdate(10, 1000, 500);
            RollbackPointer ptr4 = RollbackPointer.forInsert(10, 1000, 501);

            assertEquals(ptr1, ptr2);
            assertNotEquals(ptr1, ptr3); // is_insert 不同
            assertNotEquals(ptr1, ptr4); // offset 不同
            assertEquals(ptr1.hashCode(), ptr2.hashCode());
        }

        @Test
        @DisplayName("toString 格式")
        void testToString() {
            RollbackPointer insertPtr = RollbackPointer.forInsert(5, 100, 50);
            String insertStr = insertPtr.toString();
            assertTrue(insertStr.contains("INSERT"));
            assertTrue(insertStr.contains("rseg=5"));
            assertTrue(insertStr.contains("page=100"));
            assertTrue(insertStr.contains("off=50"));

            RollbackPointer updatePtr = RollbackPointer.forUpdate(10, 200, 100);
            String updateStr = updatePtr.toString();
            assertTrue(updateStr.contains("UPDATE"));

            RollbackPointer nullPtr = RollbackPointer.NULL;
            String nullStr = nullPtr.toString();
            assertTrue(nullStr.contains("NULL"));
        }
    }
}
