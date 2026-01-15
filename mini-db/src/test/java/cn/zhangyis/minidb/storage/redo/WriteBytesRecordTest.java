package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.record.MultiRecEndRecord;
import cn.zhangyis.minidb.storage.redo.record.RedoRecord;
import cn.zhangyis.minidb.storage.redo.record.RedoRecordSerializer;
import cn.zhangyis.minidb.storage.redo.record.WriteBytesRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WriteBytesRecord 单元测试
 *
 * <p>验证页内 Patch 记录的正确性，包括：</p>
 * <ul>
 *   <li>序列化/反序列化一致性</li>
 *   <li>体积检查: 修改 4B 应产生约 19B redo (不是 16KB)</li>
 *   <li>参数校验</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("WriteBytesRecord Tests")
class WriteBytesRecordTest {

    // ==================== 构造函数测试 ====================

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Create valid WriteBytesRecord")
        void createValidRecord() {
            PageId pageId = new PageId(1, 100);
            byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};

            WriteBytesRecord record = new WriteBytesRecord(pageId, 38, data);

            assertEquals(pageId, record.getPageId());
            assertEquals(38, record.getOffset());
            assertEquals(4, record.getLength());
            assertArrayEquals(data, record.getData());
        }

        @Test
        @DisplayName("Reject null pageId")
        void rejectNullPageId() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WriteBytesRecord(null, 0, new byte[4]));
        }

        @Test
        @DisplayName("Reject null data")
        void rejectNullData() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WriteBytesRecord(new PageId(1, 1), 0, null));
        }

        @Test
        @DisplayName("Reject empty data")
        void rejectEmptyData() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WriteBytesRecord(new PageId(1, 1), 0, new byte[0]));
        }

        @Test
        @DisplayName("Reject negative offset")
        void rejectNegativeOffset() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WriteBytesRecord(new PageId(1, 1), -1, new byte[4]));
        }

        @Test
        @DisplayName("Reject offset >= 16384")
        void rejectOffsetTooLarge() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WriteBytesRecord(new PageId(1, 1), 16384, new byte[4]));
        }

        @Test
        @DisplayName("Reject offset + length > 16384")
        void rejectOverflowingData() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WriteBytesRecord(new PageId(1, 1), 16380, new byte[5]));
        }
    }

    // ==================== 序列化测试 ====================

    @Nested
    @DisplayName("Serialization Tests")
    class SerializationTests {

        @Test
        @DisplayName("Serialized size should be 15 + N bytes")
        void serializedSizeIsCorrect() {
            // size = 1(type) + 4(space_id) + 4(page_no) + 2(data_len) + 2(offset) + 2(length) + N
            // = 15 + N

            PageId pageId = new PageId(1, 100);

            // 4 bytes data → 19 bytes total
            WriteBytesRecord record4 = new WriteBytesRecord(pageId, 38, new byte[4]);
            assertEquals(19, record4.getSize());
            assertEquals(19, record4.serialize().length);

            // 100 bytes data → 115 bytes total
            WriteBytesRecord record100 = new WriteBytesRecord(pageId, 0, new byte[100]);
            assertEquals(115, record100.getSize());
            assertEquals(115, record100.serialize().length);
        }

        @Test
        @DisplayName("Modifying 4 bytes should produce ~19 bytes redo (NOT 16KB)")
        void smallModificationProducesSmallRedo() {
            PageId pageId = new PageId(1, 100);
            byte[] data = new byte[4];

            WriteBytesRecord record = new WriteBytesRecord(pageId, 38, data);

            // 关键验收: 修改 4B 产生 19B redo，而非整页 16KB
            int serializedSize = record.getSize();
            assertTrue(serializedSize < 100,
                    "Redo for 4 byte modification should be small, got: " + serializedSize);
            assertEquals(19, serializedSize);
        }

        @Test
        @DisplayName("Serialize and deserialize should preserve data")
        void serializeDeserializeRoundTrip() {
            PageId pageId = new PageId(42, 12345);
            byte[] data = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55};

            WriteBytesRecord original = new WriteBytesRecord(pageId, 100, data);
            byte[] serialized = original.serialize();

            // 反序列化
            ByteBuffer buffer = ByteBuffer.wrap(serialized);
            WriteBytesRecord deserialized = WriteBytesRecord.deserialize(buffer);

            assertEquals(original.getPageId(), deserialized.getPageId());
            assertEquals(original.getOffset(), deserialized.getOffset());
            assertEquals(original.getLength(), deserialized.getLength());
            assertArrayEquals(original.getData(), deserialized.getData());
        }
    }

    // ==================== RedoRecordSerializer 集成测试 ====================

    @Nested
    @DisplayName("RedoRecordSerializer Integration Tests")
    class SerializerIntegrationTests {

        @Test
        @DisplayName("Serialize list of records with END marker")
        void serializeListWithEndMarker() {
            List<RedoRecord> records = new ArrayList<>();
            records.add(new WriteBytesRecord(new PageId(1, 100), 38, new byte[4]));
            records.add(new WriteBytesRecord(new PageId(1, 100), 56, new byte[8]));
            records.add(new WriteBytesRecord(new PageId(1, 101), 100, new byte[20]));

            byte[] serialized = RedoRecordSerializer.serialize(records);

            // 验证以 END marker 结尾
            assertTrue(RedoRecordSerializer.endsWithMultiRecEnd(serialized));

            // 验证总大小
            int expectedSize = (19) + (23) + (35) + 1;  // 3 records + END marker
            assertEquals(expectedSize, serialized.length);
        }

        @Test
        @DisplayName("Deserialize list of records")
        void deserializeList() throws Exception {
            List<RedoRecord> original = new ArrayList<>();
            original.add(new WriteBytesRecord(new PageId(1, 100), 38, new byte[]{1, 2, 3, 4}));
            original.add(new WriteBytesRecord(new PageId(1, 101), 56, new byte[]{5, 6, 7, 8}));

            byte[] serialized = RedoRecordSerializer.serialize(original);
            List<RedoRecord> deserialized = RedoRecordSerializer.deserialize(serialized);

            assertEquals(2, deserialized.size());

            WriteBytesRecord r0 = (WriteBytesRecord) deserialized.get(0);
            assertEquals(100, r0.getPageId().getPageNo());
            assertEquals(38, r0.getOffset());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, r0.getData());

            WriteBytesRecord r1 = (WriteBytesRecord) deserialized.get(1);
            assertEquals(101, r1.getPageId().getPageNo());
            assertEquals(56, r1.getOffset());
            assertArrayEquals(new byte[]{5, 6, 7, 8}, r1.getData());
        }

        @Test
        @DisplayName("Empty record list should only produce END marker")
        void emptyListProducesOnlyEndMarker() {
            byte[] serialized = RedoRecordSerializer.serialize(new ArrayList<>());
            assertEquals(1, serialized.length);
            assertTrue(RedoRecordSerializer.endsWithMultiRecEnd(serialized));
        }
    }

    // ==================== MultiRecEndRecord 测试 ====================

    @Nested
    @DisplayName("MultiRecEndRecord Tests")
    class MultiRecEndRecordTests {

        @Test
        @DisplayName("MultiRecEndRecord size should be 1 byte")
        void sizeIsOneByte() {
            MultiRecEndRecord record = new MultiRecEndRecord();
            assertEquals(1, record.getSize());
            assertEquals(1, record.serialize().length);
        }

        @Test
        @DisplayName("MultiRecEndRecord should have null pageId")
        void pageIdIsNull() {
            MultiRecEndRecord record = new MultiRecEndRecord();
            assertNull(record.getPageId());
        }

        @Test
        @DisplayName("MultiRecEndRecord is not data redo")
        void isNotDataRedo() {
            MultiRecEndRecord record = new MultiRecEndRecord();
            assertFalse(record.isDataRedo());
        }

        @Test
        @DisplayName("Serialize and deserialize MultiRecEndRecord")
        void serializeDeserializeRoundTrip() {
            MultiRecEndRecord original = new MultiRecEndRecord();
            byte[] serialized = original.serialize();

            ByteBuffer buffer = ByteBuffer.wrap(serialized);
            MultiRecEndRecord deserialized = MultiRecEndRecord.deserialize(buffer);

            assertNotNull(deserialized);
            assertEquals(original.getType(), deserialized.getType());
        }
    }
}
