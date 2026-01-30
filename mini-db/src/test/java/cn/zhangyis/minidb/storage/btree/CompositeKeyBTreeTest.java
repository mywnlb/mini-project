package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复合键 B+Tree 测试
 *
 * @author MiniDB
 */
class CompositeKeyBTreeTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(100, diskManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
    }

    // ==================== ColumnType 测试 ====================

    @Nested
    @DisplayName("ColumnType 测试")
    class ColumnTypeTests {

        @Test
        @DisplayName("列类型属性")
        void testColumnTypeProperties() {
            assertTrue(ColumnType.INT.isFixedSize());
            assertEquals(4, ColumnType.INT.getFixedLength());
            assertTrue(ColumnType.INT.isNumeric());

            assertTrue(ColumnType.BIGINT.isFixedSize());
            assertEquals(8, ColumnType.BIGINT.getFixedLength());

            assertFalse(ColumnType.VARCHAR.isFixedSize());
            assertTrue(ColumnType.VARCHAR.isVariableSize());
            assertTrue(ColumnType.VARCHAR.isString());

            assertTrue(ColumnType.VARBINARY.isBinary());
        }
    }

    // ==================== KeyColumn 测试 ====================

    @Nested
    @DisplayName("KeyColumn 测试")
    class KeyColumnTests {

        @Test
        @DisplayName("创建整数列")
        void testIntColumn() {
            KeyColumn col = KeyColumn.intColumn("id");

            assertEquals("id", col.getName());
            assertEquals(ColumnType.INT, col.getType());
            assertFalse(col.isNullable());
            assertFalse(col.isDescending());
        }

        @Test
        @DisplayName("创建变长字符串列")
        void testVarcharColumn() {
            KeyColumn col = KeyColumn.varcharColumn("name", 100);

            assertEquals("name", col.getName());
            assertEquals(ColumnType.VARCHAR, col.getType());
            assertEquals(100, col.getMaxLength());
        }

        @Test
        @DisplayName("创建可空降序列")
        void testNullableDescColumn() {
            KeyColumn col = KeyColumn.intColumn("score").nullable().desc();

            assertTrue(col.isNullable());
            assertTrue(col.isDescending());
        }

        @Test
        @DisplayName("列的最大编码长度")
        void testMaxEncodedLength() {
            KeyColumn intCol = KeyColumn.intColumn("id");
            assertEquals(4, intCol.getMaxEncodedLength());

            KeyColumn nullableIntCol = KeyColumn.nullableIntColumn("id");
            assertEquals(5, nullableIntCol.getMaxEncodedLength()); // 1 + 4

            KeyColumn varcharCol = KeyColumn.varcharColumn("name", 50);
            assertEquals(52, varcharCol.getMaxEncodedLength()); // 2 + 50
        }
    }

    // ==================== CompositeKeyDef 测试 ====================

    @Nested
    @DisplayName("CompositeKeyDef 测试")
    class CompositeKeyDefTests {

        @Test
        @DisplayName("创建单列键定义")
        void testSingleColumnKey() {
            CompositeKeyDef keyDef = CompositeKeyDef.singleInt("id");

            assertEquals(1, keyDef.getColumnCount());
            assertEquals("id", keyDef.getColumn(0).getName());
        }

        @Test
        @DisplayName("创建多列键定义")
        void testMultiColumnKey() {
            CompositeKeyDef keyDef = new CompositeKeyDef(
                    KeyColumn.intColumn("tenant_id"),
                    KeyColumn.intColumn("user_id"),
                    KeyColumn.varcharColumn("name", 50)
            );

            assertEquals(3, keyDef.getColumnCount());
            assertTrue(keyDef.hasVariableLengthColumn());
        }

        @Test
        @DisplayName("双列整数键")
        void testDoubleIntKey() {
            CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("a", "b");

            assertEquals(2, keyDef.getColumnCount());
            assertFalse(keyDef.hasVariableLengthColumn());
        }
    }

    // ==================== CompositeKeyValue 测试 ====================

    @Nested
    @DisplayName("CompositeKeyValue 测试")
    class CompositeKeyValueTests {

        @Test
        @DisplayName("创建单列键值")
        void testSingleColumnValue() {
            CompositeKeyDef keyDef = CompositeKeyDef.singleInt("id");
            CompositeKeyValue keyValue = CompositeKeyValue.of(keyDef, 100);

            assertEquals(100, keyValue.getIntValue(0));
            assertFalse(keyValue.isPrefixKey());
        }

        @Test
        @DisplayName("创建多列键值")
        void testMultiColumnValue() {
            CompositeKeyDef keyDef = CompositeKeyDef.tripleInt("a", "b", "c");
            CompositeKeyValue keyValue = CompositeKeyValue.of(keyDef, 1, 2, 3);

            assertEquals(1, keyValue.getIntValue(0));
            assertEquals(2, keyValue.getIntValue(1));
            assertEquals(3, keyValue.getIntValue(2));
            assertEquals(3, keyValue.getEffectiveColumnCount());
        }

        @Test
        @DisplayName("前缀键值")
        void testPrefixKeyValue() {
            CompositeKeyDef keyDef = CompositeKeyDef.tripleInt("a", "b", "c");
            CompositeKeyValue prefixKey = CompositeKeyValue.of(keyDef, 1, 2);

            assertEquals(2, prefixKey.getEffectiveColumnCount());
            assertTrue(prefixKey.isPrefixKey());
        }

        @Test
        @DisplayName("编码和解码")
        void testEncodeAndDecode() {
            CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("a", "b");
            CompositeKeyValue original = CompositeKeyValue.of(keyDef, 100, 200);

            byte[] encoded = original.encode();
            CompositeKeyValue decoded = CompositeKeyValue.decode(keyDef, encoded);

            assertEquals(100, decoded.getIntValue(0));
            assertEquals(200, decoded.getIntValue(1));
        }

        @Test
        @DisplayName("字符串键编码和解码")
        void testStringKeyEncodeAndDecode() {
            CompositeKeyDef keyDef = new CompositeKeyDef(
                    KeyColumn.intColumn("id"),
                    KeyColumn.varcharColumn("name", 50)
            );
            CompositeKeyValue original = new CompositeKeyValue(keyDef, 42, "hello");

            byte[] encoded = original.encode();
            CompositeKeyValue decoded = CompositeKeyValue.decode(keyDef, encoded);

            assertEquals(42, decoded.getIntValue(0));
            assertEquals("hello", decoded.getStringValue(1));
        }
    }

    // ==================== CompositeKeyComparator 测试 ====================

    @Nested
    @DisplayName("CompositeKeyComparator 测试")
    class CompositeKeyComparatorTests {

        @Test
        @DisplayName("比较单列整数键")
        void testCompareSingleIntKey() {
            CompositeKeyDef keyDef = CompositeKeyDef.singleInt("id");
            CompositeKeyComparator comparator = new CompositeKeyComparator(keyDef);

            byte[] key1 = CompositeKeyValue.of(keyDef, 100).encode();
            byte[] key2 = CompositeKeyValue.of(keyDef, 200).encode();
            byte[] key3 = CompositeKeyValue.of(keyDef, 100).encode();

            assertTrue(comparator.compareKeys(key1, key2) < 0);
            assertTrue(comparator.compareKeys(key2, key1) > 0);
            assertEquals(0, comparator.compareKeys(key1, key3));
        }

        @Test
        @DisplayName("比较多列键")
        void testCompareMultiColumnKey() {
            CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("a", "b");
            CompositeKeyComparator comparator = new CompositeKeyComparator(keyDef);

            byte[] key1 = CompositeKeyValue.of(keyDef, 1, 10).encode();
            byte[] key2 = CompositeKeyValue.of(keyDef, 1, 20).encode();
            byte[] key3 = CompositeKeyValue.of(keyDef, 2, 5).encode();

            // (1, 10) < (1, 20)
            assertTrue(comparator.compareKeys(key1, key2) < 0);

            // (1, 20) < (2, 5)
            assertTrue(comparator.compareKeys(key2, key3) < 0);

            // (1, 10) < (2, 5)
            assertTrue(comparator.compareKeys(key1, key3) < 0);
        }

        @Test
        @DisplayName("前缀匹配")
        void testPrefixMatch() {
            CompositeKeyDef keyDef = CompositeKeyDef.tripleInt("a", "b", "c");
            CompositeKeyComparator comparator = new CompositeKeyComparator(keyDef);

            byte[] prefixKey = CompositeKeyValue.of(keyDef, 1, 2).encode();
            byte[] fullKey1 = CompositeKeyValue.of(keyDef, 1, 2, 3).encode();
            byte[] fullKey2 = CompositeKeyValue.of(keyDef, 1, 2, 100).encode();
            byte[] fullKey3 = CompositeKeyValue.of(keyDef, 1, 3, 1).encode();

            assertTrue(comparator.isPrefixMatch(prefixKey, fullKey1));
            assertTrue(comparator.isPrefixMatch(prefixKey, fullKey2));
            assertFalse(comparator.isPrefixMatch(prefixKey, fullKey3));
        }
    }

    // ==================== CompositeKeyBTree 测试 ====================

    @Nested
    @DisplayName("CompositeKeyBTree 测试")
    class CompositeKeyBTreeTests {

        @Test
        @DisplayName("创建复合键 B+Tree")
        void testCreate() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("tenant_id", "user_id");
                CompositeKeyBTree tree = CompositeKeyBTree.create(
                        1L, 0, bufferPool, keyDef, IndexType.PRIMARY, mtr);

                assertNotNull(tree);
                assertEquals(0, tree.getRecordCount());
                assertTrue(tree.isUnique());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("插入和搜索双列键")
        void testInsertAndSearchDoubleKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("tenant_id", "user_id");
                CompositeKeyBTree tree = CompositeKeyBTree.create(
                        1L, 0, bufferPool, keyDef, IndexType.SECONDARY, mtr);

                // 插入数据
                for (int tenant = 1; tenant <= 3; tenant++) {
                    for (int user = 1; user <= 5; user++) {
                        byte[] record = buildCompositeRecord(tenant, user);
                        tree.insert(record, tenant, user, mtr);
                    }
                }

                assertEquals(15, tree.getRecordCount());

                // 精确搜索
                BTreeSearchResult result = tree.search(2, 3, mtr);
                assertNotNull(result);
                assertTrue(result.isExactMatch());

                // 搜索不存在的键
                BTreeSearchResult notFound = tree.search(4, 1, mtr);
                assertFalse(notFound != null && notFound.isExactMatch());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("前缀扫描")
        void testPrefixScan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("tenant_id", "user_id");
                CompositeKeyBTree tree = CompositeKeyBTree.create(
                        1L, 0, bufferPool, keyDef, IndexType.SECONDARY, mtr);

                // 插入数据：tenant 1 有 3 个用户，tenant 2 有 5 个用户
                for (int user = 1; user <= 3; user++) {
                    tree.insert(buildCompositeRecord(1, user), 1, user, mtr);
                }
                for (int user = 1; user <= 5; user++) {
                    tree.insert(buildCompositeRecord(2, user), 2, user, mtr);
                }

                // 前缀扫描 tenant=1
                try (BTreeRangeScanner scanner = tree.prefixScan(1, mtr)) {
                    int count = 0;
                    while (scanner.hasNext()) {
                        scanner.next();
                        count++;
                    }
                    assertEquals(3, count);
                }

                // 前缀扫描 tenant=2
                try (BTreeRangeScanner scanner = tree.prefixScan(2, mtr)) {
                    int count = 0;
                    while (scanner.hasNext()) {
                        scanner.next();
                        count++;
                    }
                    assertEquals(5, count);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("三列复合键")
        void testTripleColumnKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                CompositeKeyDef keyDef = CompositeKeyDef.tripleInt("a", "b", "c");
                CompositeKeyBTree tree = CompositeKeyBTree.create(
                        1L, 0, bufferPool, keyDef, IndexType.SECONDARY, mtr);

                // 插入数据
                for (int a = 1; a <= 2; a++) {
                    for (int b = 1; b <= 3; b++) {
                        for (int c = 1; c <= 4; c++) {
                            byte[] record = buildTripleRecord(a, b, c);
                            CompositeKeyValue key = CompositeKeyValue.of(keyDef, a, b, c);
                            tree.insert(record, key, mtr);
                        }
                    }
                }

                assertEquals(24, tree.getRecordCount()); // 2 * 3 * 4

                // 前缀扫描 (1, 2, *)
                try (BTreeRangeScanner scanner = tree.prefixScan(1, 2, mtr)) {
                    int count = 0;
                    while (scanner.hasNext()) {
                        scanner.next();
                        count++;
                    }
                    assertEquals(4, count);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("唯一索引重复键检查")
        void testUniqueIndexDuplicateCheck() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("a", "b");
                CompositeKeyBTree tree = CompositeKeyBTree.create(
                        1L, 0, bufferPool, keyDef, IndexType.UNIQUE, mtr);

                tree.insert(buildCompositeRecord(1, 1), 1, 1, mtr);

                // 插入重复键应抛异常
                assertThrows(DuplicateKeyException.class, () -> {
                    tree.insert(buildCompositeRecord(1, 1), 1, 1, mtr);
                });

                // 不同的键可以插入
                tree.insert(buildCompositeRecord(1, 2), 1, 2, mtr);
                assertEquals(2, tree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除复合键记录")
        void testDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("a", "b");
                CompositeKeyBTree tree = CompositeKeyBTree.create(
                        1L, 0, bufferPool, keyDef, IndexType.SECONDARY, mtr);

                // 插入数据
                for (int a = 1; a <= 3; a++) {
                    for (int b = 1; b <= 3; b++) {
                        tree.insert(buildCompositeRecord(a, b), a, b, mtr);
                    }
                }

                assertEquals(9, tree.getRecordCount());

                // 删除 (2, 2)
                int recordSize = 8 + SimpleRecordBuilder.RECORD_HEADER_SIZE; // 两个 int
                assertTrue(tree.delete(2, 2, recordSize, mtr));
                assertEquals(8, tree.getRecordCount());

                // 验证已删除
                assertFalse(tree.containsKey(CompositeKeyValue.of(keyDef, 2, 2), mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("范围扫描")
        void testRangeScan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                CompositeKeyDef keyDef = CompositeKeyDef.doubleInt("a", "b");
                CompositeKeyBTree tree = CompositeKeyBTree.create(
                        1L, 0, bufferPool, keyDef, IndexType.SECONDARY, mtr);

                // 插入数据
                for (int a = 1; a <= 5; a++) {
                    for (int b = 1; b <= 3; b++) {
                        tree.insert(buildCompositeRecord(a, b), a, b, mtr);
                    }
                }

                // 范围扫描 (2, 1) 到 (4, 2)
                CompositeKeyValue lower = CompositeKeyValue.of(keyDef, 2, 1);
                CompositeKeyValue upper = CompositeKeyValue.of(keyDef, 4, 2);

                try (BTreeRangeScanner scanner = tree.rangeScan(mtr, lower, upper, true, true)) {
                    List<String> keys = new ArrayList<>();
                    for (BTreeRangeScanner.ScanEntry entry : scanner) {
                        CompositeKeyValue key = tree.decodeKey(entry.getKey());
                        keys.add("(" + key.getIntValue(0) + "," + key.getIntValue(1) + ")");
                    }

                    // 应该包含 (2,1), (2,2), (2,3), (3,1), (3,2), (3,3), (4,1), (4,2)
                    assertEquals(8, keys.size());
                    assertTrue(keys.contains("(2,1)"));
                    assertTrue(keys.contains("(4,2)"));
                }

                mtr.commit();
            }
        }
    }

    // ==================== 辅助方法 ====================

    private byte[] buildCompositeRecord(int key1, int key2) {
        // 简化的记录格式：header + key1 + key2
        byte[] record = new byte[SimpleRecordBuilder.RECORD_HEADER_SIZE + 8];
        // 写入键
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE] = (byte) (key1 >> 24);
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE + 1] = (byte) (key1 >> 16);
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE + 2] = (byte) (key1 >> 8);
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE + 3] = (byte) key1;
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE + 4] = (byte) (key2 >> 24);
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE + 5] = (byte) (key2 >> 16);
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE + 6] = (byte) (key2 >> 8);
        record[SimpleRecordBuilder.RECORD_HEADER_SIZE + 7] = (byte) key2;
        return record;
    }

    private byte[] buildTripleRecord(int key1, int key2, int key3) {
        byte[] record = new byte[SimpleRecordBuilder.RECORD_HEADER_SIZE + 12];
        int offset = SimpleRecordBuilder.RECORD_HEADER_SIZE;
        // key1
        record[offset++] = (byte) (key1 >> 24);
        record[offset++] = (byte) (key1 >> 16);
        record[offset++] = (byte) (key1 >> 8);
        record[offset++] = (byte) key1;
        // key2
        record[offset++] = (byte) (key2 >> 24);
        record[offset++] = (byte) (key2 >> 16);
        record[offset++] = (byte) (key2 >> 8);
        record[offset++] = (byte) key2;
        // key3
        record[offset++] = (byte) (key3 >> 24);
        record[offset++] = (byte) (key3 >> 16);
        record[offset++] = (byte) (key3 >> 8);
        record[offset] = (byte) key3;
        return record;
    }
}
