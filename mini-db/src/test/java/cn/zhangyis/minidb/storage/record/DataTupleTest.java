package cn.zhangyis.minidb.storage.record;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataTuple 单元测试
 * 
 * <p>
 * 测试逻辑记录的比较功能，覆盖以下场景：
 * </p>
 * <ul>
 * <li>相同字段值比较</li>
 * <li>INT 类型比较</li>
 * <li>VARCHAR 字符串比较</li>
 * <li>NULL 字段比较</li>
 * <li>部分字段比较（nFieldsCmp）</li>
 * <li>二级索引场景</li>
 * </ul>
 */
@DisplayName("DataTuple 逻辑记录测试")
class DataTupleTest {

    @Nested
    @DisplayName("基本比较测试")
    class BasicCompareTests {

        @Test
        @DisplayName("相同 INT 字段值比较返回 0")
        void testCompareEqualIntTuples() {
            FieldType intType = FieldType.intType(false);

            DataField field1 = DataField.intField(intType, 100);
            DataField field2 = DataField.intField(intType, 100);

            DataTuple tuple1 = DataTuple.withCmpFields(1, field1);
            DataTuple tuple2 = DataTuple.withCmpFields(1, field2);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { intType });

            assertEquals(0, tuple1.compare(tuple2, index));
        }

        @Test
        @DisplayName("INT 字段大小比较")
        void testCompareIntFields() {
            FieldType intType = FieldType.intType(false);

            DataField small = DataField.intField(intType, 10);
            DataField large = DataField.intField(intType, 100);

            DataTuple tupleSmall = DataTuple.withCmpFields(1, small);
            DataTuple tupleLarge = DataTuple.withCmpFields(1, large);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { intType });

            assertTrue(tupleSmall.compare(tupleLarge, index) < 0);
            assertTrue(tupleLarge.compare(tupleSmall, index) > 0);
        }

        @Test
        @DisplayName("负数 INT 比较")
        void testCompareNegativeInt() {
            FieldType intType = FieldType.intType(false);

            DataField negative = DataField.intField(intType, -100);
            DataField positive = DataField.intField(intType, 100);

            DataTuple tupleNeg = DataTuple.withCmpFields(1, negative);
            DataTuple tuplePos = DataTuple.withCmpFields(1, positive);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { intType });

            assertTrue(tupleNeg.compare(tuplePos, index) < 0);
        }
    }

    @Nested
    @DisplayName("VARCHAR 字符串比较测试")
    class VarcharCompareTests {

        @Test
        @DisplayName("相同字符串比较返回 0")
        void testCompareEqualVarchar() {
            FieldType varcharType = FieldType.varcharType(100, false);

            DataField field1 = DataField.stringField(varcharType, "hello");
            DataField field2 = DataField.stringField(varcharType, "hello");

            DataTuple tuple1 = DataTuple.withCmpFields(1, field1);
            DataTuple tuple2 = DataTuple.withCmpFields(1, field2);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { varcharType });

            assertEquals(0, tuple1.compare(tuple2, index));
        }

        @Test
        @DisplayName("字符串字典序比较")
        void testCompareVarcharLexicographic() {
            FieldType varcharType = FieldType.varcharType(100, false);

            DataField abc = DataField.stringField(varcharType, "abc");
            DataField abd = DataField.stringField(varcharType, "abd");

            DataTuple tupleAbc = DataTuple.withCmpFields(1, abc);
            DataTuple tupleAbd = DataTuple.withCmpFields(1, abd);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { varcharType });

            assertTrue(tupleAbc.compare(tupleAbd, index) < 0);
            assertTrue(tupleAbd.compare(tupleAbc, index) > 0);
        }

        @Test
        @DisplayName("字符串长度不同比较")
        void testCompareVarcharDifferentLength() {
            FieldType varcharType = FieldType.varcharType(100, false);

            DataField ab = DataField.stringField(varcharType, "ab");
            DataField abc = DataField.stringField(varcharType, "abc");

            DataTuple tupleAb = DataTuple.withCmpFields(1, ab);
            DataTuple tupleAbc = DataTuple.withCmpFields(1, abc);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { varcharType });

            // "ab" < "abc" (shorter prefix)
            assertTrue(tupleAb.compare(tupleAbc, index) < 0);
        }
    }

    @Nested
    @DisplayName("NULL 比较测试")
    class NullCompareTests {

        @Test
        @DisplayName("两个 NULL 比较返回 0")
        void testCompareBothNull() {
            FieldType intType = FieldType.intType(true);

            DataField null1 = DataField.nullField(intType);
            DataField null2 = DataField.nullField(intType);

            DataTuple tuple1 = DataTuple.withCmpFields(1, null1);
            DataTuple tuple2 = DataTuple.withCmpFields(1, null2);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { intType });

            assertEquals(0, tuple1.compare(tuple2, index));
        }

        @Test
        @DisplayName("NULL < NOT NULL")
        void testCompareNullLessThanNotNull() {
            FieldType intType = FieldType.intType(true);

            DataField nullField = DataField.nullField(intType);
            DataField notNull = DataField.intField(intType, 100);

            DataTuple tupleNull = DataTuple.withCmpFields(1, nullField);
            DataTuple tupleNotNull = DataTuple.withCmpFields(1, notNull);

            IndexDescriptor index = IndexDescriptor.clustered(new FieldType[] { intType });

            assertTrue(tupleNull.compare(tupleNotNull, index) < 0);
            assertTrue(tupleNotNull.compare(tupleNull, index) > 0);
        }
    }

    @Nested
    @DisplayName("部分字段比较测试")
    class PartialCompareTests {

        @Test
        @DisplayName("使用 nFieldsCmp 只比较前 N 个字段")
        void testPartialCompare() {
            FieldType intType = FieldType.intType(false);
            FieldType varcharType = FieldType.varcharType(100, false);

            // Tuple1: [10, "abc", 1]
            DataTuple tuple1 = DataTuple.withCmpFields(2,
                    DataField.intField(intType, 10),
                    DataField.stringField(varcharType, "abc"),
                    DataField.intField(intType, 1));

            // Tuple2: [10, "abc", 999] -- 第3个字段不同
            DataTuple tuple2 = DataTuple.withCmpFields(2,
                    DataField.intField(intType, 10),
                    DataField.stringField(varcharType, "abc"),
                    DataField.intField(intType, 999));

            IndexDescriptor index = new IndexDescriptor(
                    new FieldType[] { intType, varcharType, intType },
                    2, true, false);

            // 只比较前 2 个字段，应相等
            assertEquals(0, tuple1.compare(tuple2, index));
        }

        @Test
        @DisplayName("第二字段不同时返回比较结果")
        void testSecondFieldDiffers() {
            FieldType intType = FieldType.intType(false);
            FieldType varcharType = FieldType.varcharType(100, false);

            // Tuple1: [10, "abc", 1]
            DataTuple tuple1 = DataTuple.withCmpFields(2,
                    DataField.intField(intType, 10),
                    DataField.stringField(varcharType, "abc"),
                    DataField.intField(intType, 1));

            // Tuple2: [10, "abd", 2]
            DataTuple tuple2 = DataTuple.withCmpFields(2,
                    DataField.intField(intType, 10),
                    DataField.stringField(varcharType, "abd"),
                    DataField.intField(intType, 2));

            IndexDescriptor index = new IndexDescriptor(
                    new FieldType[] { intType, varcharType, intType },
                    2, true, false);

            // "abc" < "abd"
            assertTrue(tuple1.compare(tuple2, index) < 0);
        }
    }

    @Nested
    @DisplayName("二级索引场景测试")
    class SecondaryIndexTests {

        @Test
        @DisplayName("二级索引：索引列 + PK")
        void testSecondaryIndexCompare() {
            FieldType varcharType = FieldType.varcharType(100, false);
            FieldType intType = FieldType.intType(false); // PK

            // 二级索引 INDEX(name)，主键 id
            // fields = [name, id], nFieldsCmp = 1（只比较 name）

            DataTuple tuple1 = DataTuple.withCmpFields(1,
                    DataField.stringField(varcharType, "Alice"),
                    DataField.intField(intType, 1));

            DataTuple tuple2 = DataTuple.withCmpFields(1,
                    DataField.stringField(varcharType, "Alice"),
                    DataField.intField(intType, 2));

            IndexDescriptor index = IndexDescriptor.secondary(
                    new FieldType[] { varcharType, intType }, 1);

            // 只比较 name，name 相同，返回 0
            assertEquals(0, tuple1.compare(tuple2, index));
        }

        @Test
        @DisplayName("二级索引不同值比较")
        void testSecondaryIndexDifferentValues() {
            FieldType varcharType = FieldType.varcharType(100, false);
            FieldType intType = FieldType.intType(false);

            DataTuple tupleAlice = DataTuple.withCmpFields(1,
                    DataField.stringField(varcharType, "Alice"),
                    DataField.intField(intType, 1));

            DataTuple tupleBob = DataTuple.withCmpFields(1,
                    DataField.stringField(varcharType, "Bob"),
                    DataField.intField(intType, 2));

            IndexDescriptor index = IndexDescriptor.secondary(
                    new FieldType[] { varcharType, intType }, 1);

            assertTrue(tupleAlice.compare(tupleBob, index) < 0);
        }
    }

    @Nested
    @DisplayName("Info Bits 测试")
    class InfoBitsTests {

        @Test
        @DisplayName("删除标记设置和检查")
        void testDeleteMark() {
            FieldType intType = FieldType.intType(false);
            DataTuple tuple = DataTuple.of(DataField.intField(intType, 1));

            assertFalse(tuple.isDeleteMarked());

            tuple.setDeleteMark(true);
            assertTrue(tuple.isDeleteMarked());

            tuple.setDeleteMark(false);
            assertFalse(tuple.isDeleteMarked());
        }
    }
}
