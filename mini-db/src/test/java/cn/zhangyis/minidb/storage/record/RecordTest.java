package cn.zhangyis.minidb.storage.record;

import cn.zhangyis.minidb.storage.record.format.CompactRecordFormat;
import cn.zhangyis.minidb.storage.record.format.FieldOffsets;
import cn.zhangyis.minidb.storage.record.format.RecordFormat;
import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.reader.RowReader;
import cn.zhangyis.minidb.storage.record.schema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 逻辑记录核心功能测试
 *
 * <h2>测试覆盖的 Invariants</h2>
 * <ul>
 *   <li>I3: TRX_ID/ROLL_PTR 位置恒定</li>
 *   <li>I5: rowVersion 从记录 peek</li>
 *   <li>I6: Instant 默认值按 columnId 填充</li>
 *   <li>I7: SystemLayout 不可变</li>
 *   <li>I8: FieldOffsets 相对 dataStart</li>
 * </ul>
 */
class RecordTest {

    @Nested
    @DisplayName("DataField 测试")
    class DataFieldTest {

        @Test
        @DisplayName("整型字段创建和读取")
        void testIntegerFields() {
            DataField tinyint = DataField.tinyintField((byte) 127);
            assertEquals(127, (byte) tinyint.getValue());

            DataField smallint = DataField.smallintField((short) 32767);
            assertEquals(32767, (short) smallint.getValue());

            DataField intField = DataField.intField(Integer.MAX_VALUE);
            assertEquals(Integer.MAX_VALUE, intField.asInt());

            DataField bigint = DataField.bigintField(Long.MAX_VALUE);
            assertEquals(Long.MAX_VALUE, bigint.asLong());
        }

        @Test
        @DisplayName("字符串字段创建和读取")
        void testStringFields() {
            DataField varchar = DataField.varcharField("Hello, 世界");
            assertEquals("Hello, 世界", varchar.asString());

            DataField charField = DataField.charField("Hi", 10);
            assertEquals("Hi", charField.asString()); // 尾部空格被去除
            assertEquals(10, charField.getLength());
        }

        @Test
        @DisplayName("NULL 字段")
        void testNullFields() {
            DataField nullInt = DataField.nullField(FieldKind.INT);
            assertTrue(nullInt.isNull());
            assertNull(nullInt.getValue());

            DataField nullVarchar = DataField.nullField(FieldKind.VARCHAR);
            assertTrue(nullVarchar.isNull());
            assertNull(nullVarchar.asString());
        }

        @Test
        @DisplayName("字段比较")
        void testFieldComparison() {
            DataField a = DataField.intField(100);
            DataField b = DataField.intField(200);
            DataField c = DataField.intField(100);

            assertTrue(a.compareTo(b) < 0);
            assertTrue(b.compareTo(a) > 0);
            assertEquals(0, a.compareTo(c));
        }
    }

    @Nested
    @DisplayName("DataTuple 测试")
    class DataTupleTest {

        @Test
        @DisplayName("构建器模式创建")
        void testBuilderPattern() {
            DataTuple tuple = DataTuple.builder()
                .addInt(1001)
                .addString("张三")
                .addLong(25L)
                .build();

            assertEquals(3, tuple.getFieldCount());
            assertEquals(1001, tuple.getField(0).asInt());
            assertEquals("张三", tuple.getField(1).asString());
            assertEquals(25L, tuple.getField(2).asLong());
        }

        @Test
        @DisplayName("元组比较")
        void testTupleComparison() {
            DataTuple t1 = DataTuple.of(
                DataField.intField(1),
                DataField.varcharField("A")
            );
            DataTuple t2 = DataTuple.of(
                DataField.intField(1),
                DataField.varcharField("B")
            );
            DataTuple t3 = DataTuple.of(
                DataField.intField(2),
                DataField.varcharField("A")
            );

            assertTrue(t1.compareTo(t2) < 0);
            assertTrue(t1.compareTo(t3) < 0);

            // 设置只比较第一个字段
            t1.setFieldsToCompare(1);
            t2.setFieldsToCompare(1);
            assertEquals(0, t1.compareTo(t2));
        }
    }

    @Nested
    @DisplayName("RecordHeader 测试")
    class RecordHeaderTest {

        @Test
        @DisplayName("记录头读写")
        void testHeaderReadWrite() {
            ByteBuffer buffer = ByteBuffer.allocate(100);
            int recStart = 50;

            RecordHeader header = new RecordHeader();
            header.setInfoBits(0x03);
            header.setNOwned(5);
            header.setHeapNo(100);
            header.setRecType(RecordHeader.REC_ORDINARY);
            header.setNextRecord(20);

            header.writeTo(buffer, recStart);

            RecordHeader read = RecordHeader.readFrom(buffer, recStart);
            assertEquals(0x03, read.getInfoBits());
            assertEquals(5, read.getNOwned());
            assertEquals(100, read.getHeapNo());
            assertEquals(RecordHeader.REC_ORDINARY, read.getRecType());
            assertEquals(20, read.getNextRecord());
        }

        @Test
        @DisplayName("快速读写方法")
        void testQuickAccessMethods() {
            ByteBuffer buffer = ByteBuffer.allocate(100);
            int recStart = 50;

            RecordHeader header = new RecordHeader();
            header.setNOwned(7);
            header.setNextRecord(-30); // 负偏移
            header.writeTo(buffer, recStart);

            assertEquals(7, RecordHeader.peekNOwned(buffer, recStart));
            assertEquals(-30, RecordHeader.peekNextRecord(buffer, recStart));

            RecordHeader.pokeNOwned(buffer, recStart, 10);
            assertEquals(10, RecordHeader.peekNOwned(buffer, recStart));
        }

        @Test
        @DisplayName("删除标记")
        void testDeletedFlag() {
            ByteBuffer buffer = ByteBuffer.allocate(100);
            int recStart = 50;

            RecordHeader header = new RecordHeader();
            header.setDeleted(true);
            header.writeTo(buffer, recStart);

            assertTrue(RecordHeader.isDeleted(buffer, recStart));

            RecordHeader.pokeDeletedFlag(buffer, recStart, false);
            assertFalse(RecordHeader.isDeleted(buffer, recStart));
        }
    }

    @Nested
    @DisplayName("SystemLayout 测试")
    class SystemLayoutTest {

        @Test
        @DisplayName("I7: 布局不可变性验证")
        void testLayoutImmutability() {
            SystemLayout withPk = SystemLayout.WITH_PK;
            SystemLayout withoutPk = SystemLayout.WITHOUT_PK;

            assertFalse(withPk.hasRowId());
            assertTrue(withoutPk.hasRowId());

            assertEquals(15, withPk.fixedSysBytes());
            assertEquals(21, withoutPk.fixedSysBytes());
        }

        @Test
        @DisplayName("I3: 系统列偏移恒定")
        void testSystemColumnOffsets() {
            // 无论是否有 ROW_ID，TRX_ID 和 ROLL_PTR 的偏移必须恒定
            assertEquals(0, SystemLayout.OFF_TRX_ID);
            assertEquals(6, SystemLayout.OFF_ROLL_PTR);
            assertEquals(13, SystemLayout.OFF_ROW_VER);

            SystemLayout withPk = SystemLayout.WITH_PK;
            SystemLayout withoutPk = SystemLayout.WITHOUT_PK;

            // dataStart = 100 时的绝对偏移
            assertEquals(100, withPk.trxIdOffset(100));
            assertEquals(106, withPk.rollPtrOffset(100));
            assertEquals(113, withPk.rowVerOffset(100));

            assertEquals(100, withoutPk.trxIdOffset(100));
            assertEquals(106, withoutPk.rollPtrOffset(100));
            assertEquals(113, withoutPk.rowVerOffset(100));

            // ROW_ID 偏移在有/无主键时不同
            assertEquals(-1, withPk.offRowId());
            assertEquals(15, withoutPk.offRowId());
        }
    }

    @Nested
    @DisplayName("RecordSchema 测试")
    class RecordSchemaTest {

        @Test
        @DisplayName("Schema 构建")
        void testSchemaBuilder() {
            RecordSchema schema = RecordSchema.builder()
                .version(1)
                .column("id", FieldKind.INT, false)
                .column("name", FieldKind.VARCHAR, 100, false)
                .column("age", FieldKind.INT, true)
                .build();

            assertEquals(1, schema.getVersion());
            assertEquals(3, schema.getColumnCount());
            assertEquals("id", schema.getColumn(0).getName());
            assertTrue(schema.getColumn(2).isNullable());
        }

        @Test
        @DisplayName("变长列和 Nullable 列收集")
        void testVariableAndNullableColumns() {
            RecordSchema schema = RecordSchema.builder()
                .version(1)
                .column("id", FieldKind.INT, false)         // 定长，NOT NULL
                .column("name", FieldKind.VARCHAR, 100, false) // 变长，NOT NULL
                .column("age", FieldKind.INT, true)         // 定长，Nullable
                .column("desc", FieldKind.VARCHAR, 200, true)  // 变长，Nullable
                .build();

            // 变长列：name(1), desc(3)，逆序
            int[] varOrdinals = schema.getVariableColumnOrdinals();
            assertArrayEquals(new int[]{3, 1}, varOrdinals);

            // Nullable 列：age(2), desc(3)
            int[] nullOrdinals = schema.getNullableColumnOrdinals();
            assertArrayEquals(new int[]{2, 3}, nullOrdinals);

            assertEquals(1, schema.getNullBitmapBytes()); // 2 个 nullable，1 字节
        }
    }

    @Nested
    @DisplayName("CompactRecordFormat 测试")
    class CompactRecordFormatTest {

        private RecordFormat format;
        private RecordSchema schema;
        private SystemLayout layout;
        private ByteBuffer buffer;

        @BeforeEach
        void setUp() {
            format = new CompactRecordFormat();
            schema = RecordSchema.builder()
                .version(1)
                .column("id", FieldKind.INT, false)
                .column("name", FieldKind.VARCHAR, 100, false)
                .column("age", FieldKind.INT, true)
                .build();
            layout = SystemLayout.WITH_PK;
            buffer = ByteBuffer.allocate(1024);
        }

        @Test
        @DisplayName("I5: peekRowVersion 从记录读取")
        void testPeekRowVersion() {
            int recStart = 100;
            int rowVersion = 42;

            // 手动写入 rowVersion
            int rowVerOffset = recStart + RecordHeader.SIZE + SystemLayout.OFF_ROW_VER;
            buffer.putShort(rowVerOffset, (short) rowVersion);

            int peeked = format.peekRowVersion(buffer, recStart);
            assertEquals(rowVersion, peeked);
        }

        @Test
        @DisplayName("编码和解码往返")
        void testEncodeDecodeRoundTrip() {
            DataTuple original = DataTuple.of(
                DataField.intField(1001),
                DataField.varcharField("Hello"),
                DataField.intField(25)
            );

            // 预留 varlen list 和 null bitmap 空间
            int extraBytes = format.calculateExtraBytes(original, schema);
            int recStart = 50 + extraBytes; // 给 varlen list 留空间

            // 编码
            format.encodeTo(buffer, recStart, original, schema, layout, 100L, 200L, 1, 0L);

            // 验证 rowVersion
            assertEquals(1, format.peekRowVersion(buffer, recStart));

            // 解析偏移
            FieldOffsets offsets = format.parseOffsets(buffer, recStart, schema, layout);

            // 解码
            DataTuple decoded = format.decode(buffer, recStart, offsets, schema, layout);

            // 验证
            assertEquals(original.getFieldCount(), decoded.getFieldCount());
            assertEquals(1001, decoded.getField(0).asInt());
            assertEquals("Hello", decoded.getField(1).asString());
            assertEquals(25, decoded.getField(2).asInt());
        }

        @Test
        @DisplayName("NULL 字段编码解码")
        void testNullFieldEncodeDecode() {
            DataTuple original = DataTuple.of(
                DataField.intField(1001),
                DataField.varcharField("Test"),
                DataField.nullField(FieldKind.INT) // NULL age
            );

            int extraBytes = format.calculateExtraBytes(original, schema);
            int recStart = 50 + extraBytes;

            format.encodeTo(buffer, recStart, original, schema, layout, 100L, 200L, 1, 0L);

            FieldOffsets offsets = format.parseOffsets(buffer, recStart, schema, layout);
            DataTuple decoded = format.decode(buffer, recStart, offsets, schema, layout);

            assertFalse(decoded.getField(0).isNull());
            assertFalse(decoded.getField(1).isNull());
            assertTrue(decoded.getField(2).isNull());
        }

        @Test
        @DisplayName("I8: FieldOffsets 基准验证")
        void testFieldOffsetsBaseline() {
            DataTuple tuple = DataTuple.of(
                DataField.intField(1),
                DataField.varcharField("ABC"),
                DataField.intField(2)
            );

            int extraBytes = format.calculateExtraBytes(tuple, schema);
            int recStart = 50 + extraBytes;

            format.encodeTo(buffer, recStart, tuple, schema, layout, 0L, 0L, 1, 0L);

            FieldOffsets offsets = format.parseOffsets(buffer, recStart, schema, layout);

            // 验证用户列偏移相对 userColumnsStart
            assertEquals(layout.userColumnsOffset(), offsets.getUserColumnsOffset());
            assertEquals(0, offsets.getOffset(0));  // id 在 +0
            assertEquals(4, offsets.getOffset(1));  // name 在 +4 (id 占 4 字节)
            assertEquals(7, offsets.getOffset(2));  // age 在 +7 (id 4 + name 3)
        }

        @Test
        @DisplayName("空间计算")
        void testSizeCalculation() {
            DataTuple tuple = DataTuple.of(
                DataField.intField(1),
                DataField.varcharField("Hello"),
                DataField.intField(2)
            );

            int size = format.calculateSize(tuple, schema, layout);

            // varlen: 1 byte (Hello 长度 5 < 128)
            // null bitmap: 1 byte (1 个 nullable)
            // header: 5 bytes
            // sys cols: 15 bytes (WITH_PK)
            // user data: 4 + 5 + 4 = 13 bytes
            // total: 1 + 1 + 5 + 15 + 13 = 35 bytes
            assertEquals(35, size);
        }

        @Test
        @DisplayName("系统列读写验证")
        void testSystemColumnsReadWrite() {
            DataTuple tuple = DataTuple.of(
                DataField.intField(1),
                DataField.varcharField("Test"),
                DataField.intField(2)
            );

            int extraBytes = format.calculateExtraBytes(tuple, schema);
            int recStart = 50 + extraBytes;

            long trxId = 0x123456789ABCL;
            long rollPtr = 0x1234567890ABCDL;
            int rowVersion = 42;

            format.encodeTo(buffer, recStart, tuple, schema, layout, trxId, rollPtr, rowVersion, 0L);

            // 读取系统列
            long[] sysCols = format.readSystemColumns(buffer, recStart, layout);

            assertEquals(trxId, sysCols[0]);
            assertEquals(rollPtr, sysCols[1]);
            assertEquals(rowVersion, (int) sysCols[2]);
        }

        @Test
        @DisplayName("长变长字段编码 (>= 128 字节)")
        void testLongVarcharEncoding() {
            // 创建一个变长字段超过 128 字节的 schema
            RecordSchema longSchema = RecordSchema.builder()
                .version(1)
                .column("id", FieldKind.INT, false)
                .column("content", FieldKind.VARCHAR, 500, false)
                .build();

            // 创建 200 字节的字符串
            String longString = "A".repeat(200);
            DataTuple tuple = DataTuple.of(
                DataField.intField(1),
                DataField.varcharField(longString)
            );

            int extraBytes = format.calculateExtraBytes(tuple, longSchema);
            // 200 字节需要 2 字节编码
            assertEquals(2, extraBytes); // 只有 1 个变长列，无 nullable

            int recStart = 50 + extraBytes;
            format.encodeTo(buffer, recStart, tuple, longSchema, layout, 0L, 0L, 1, 0L);

            FieldOffsets offsets = format.parseOffsets(buffer, recStart, longSchema, layout);
            assertEquals(200, offsets.getLength(1));

            DataTuple decoded = format.decode(buffer, recStart, offsets, longSchema, layout);
            assertEquals(longString, decoded.getField(1).asString());
        }
    }

    @Nested
    @DisplayName("Instant DDL 测试")
    class InstantDDLTest {

        @Test
        @DisplayName("I6: Instant 默认值按 columnId 填充")
        void testInstantDefaultFill() {
            // 创建初始 Schema v1
            RecordSchema schemaV1 = RecordSchema.builder()
                .version(1)
                .column("id", FieldKind.INT, false)
                .column("name", FieldKind.VARCHAR, 100, false)
                .build();

            SchemaRegistry registry = new SchemaRegistry(schemaV1);

            // Instant ADD COLUMN
            ColumnDescriptor statusCol = ColumnDescriptor.of(
                999L, "status", FieldType.intType(true), 2);
            RecordSchema schemaV2 = registry.addColumnInstant(
                statusCol,
                DataField.intField(0) // 默认值
            );

            assertEquals(2, schemaV2.getVersion());
            assertEquals(3, schemaV2.getColumnCount());

            // 模拟读取 v1 版本的记录
            DataTuple oldTuple = DataTuple.create(3);
            oldTuple.setField(0, DataField.intField(1));
            oldTuple.setField(1, DataField.varcharField("Test"));
            // field[2] 为 null，需要填充

            DataTuple filled = registry.fillInstantDefaults(oldTuple, 1);

            // 验证 status 被填充为默认值 0
            assertNotNull(filled.getField(2));
            assertEquals(0, filled.getField(2).asInt());
        }

        @Test
        @DisplayName("新版本记录不需要填充")
        void testNoFillForCurrentVersion() {
            RecordSchema schemaV1 = RecordSchema.builder()
                .version(1)
                .column("id", FieldKind.INT, false)
                .build();

            SchemaRegistry registry = new SchemaRegistry(schemaV1);

            DataTuple tuple = DataTuple.of(DataField.intField(1));
            DataTuple result = registry.fillInstantDefaults(tuple, 1);

            assertSame(tuple, result); // 应该返回同一个对象
        }
    }

    @Nested
    @DisplayName("RowReader 集成测试")
    class RowReaderTest {

        @Test
        @DisplayName("完整读取流程")
        void testFullReadFlow() {
            // 准备 Schema 和 Registry
            RecordSchema schema = RecordSchema.builder()
                .version(1)
                .column("id", FieldKind.INT, false)
                .column("name", FieldKind.VARCHAR, 100, false)
                .build();

            SchemaRegistry registry = new SchemaRegistry(schema);
            SystemLayout layout = SystemLayout.WITH_PK;
            RecordFormat format = new CompactRecordFormat();

            // 准备数据
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            DataTuple original = DataTuple.of(
                DataField.intField(42),
                DataField.varcharField("Test")
            );

            int extraBytes = format.calculateExtraBytes(original, schema);
            int recStart = 100 + extraBytes;

            // 写入记录头
            RecordHeader header = new RecordHeader();
            header.setHeapNo(2);
            header.setRecType(RecordHeader.REC_ORDINARY);
            header.writeTo(buffer, recStart);

            // 编码数据
            format.encodeTo(buffer, recStart, original, schema, layout, 100L, 200L, 1, 0L);

            // 使用 RowReader 读取
            RowReader reader = new RowReader(registry, format, layout);
            DataTuple result = reader.read(buffer, recStart);

            assertEquals(42, result.getField(0).asInt());
            assertEquals("Test", result.getField(1).asString());
        }
    }
}
