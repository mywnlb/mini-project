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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 唯一索引和重复键 B+Tree 测试
 *
 * @author MiniDB
 */
class UniqueAndDuplicateKeyTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

    private static final int RECORD_SIZE = SimpleRecordBuilder.RECORD_HEADER_SIZE +
            SimpleRecordBuilder.KEY_SIZE + 1;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(100, diskManager);
        comparator = new IntKeyComparator();
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

    // ==================== IndexType 测试 ====================

    @Nested
    @DisplayName("IndexType 测试")
    class IndexTypeTests {

        @Test
        @DisplayName("索引类型枚举")
        void testIndexTypes() {
            assertEquals(3, IndexType.values().length);
            assertNotNull(IndexType.PRIMARY);
            assertNotNull(IndexType.UNIQUE);
            assertNotNull(IndexType.SECONDARY);
        }
    }

    // ==================== DuplicateKeyException 测试 ====================

    @Nested
    @DisplayName("DuplicateKeyException 测试")
    class DuplicateKeyExceptionTests {

        @Test
        @DisplayName("创建异常")
        void testCreateException() {
            byte[] key = IntKeyComparator.intToBytes(100);
            DuplicateKeyException ex = new DuplicateKeyException(key, 1L);

            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("100"));
            assertEquals(100, ex.getKeyAsInt());
            assertEquals(1L, ex.getIndexId());
        }

        @Test
        @DisplayName("带消息的异常")
        void testExceptionWithMessage() {
            byte[] key = IntKeyComparator.intToBytes(50);
            DuplicateKeyException ex = new DuplicateKeyException("Custom message", key, 2L);

            assertEquals("Custom message", ex.getMessage());
            assertEquals(50, ex.getKeyAsInt());
        }
    }

    // ==================== UniqueBTree 测试 ====================

    @Nested
    @DisplayName("UniqueBTree 测试")
    class UniqueBTreeTests {

        @Test
        @DisplayName("创建唯一索引")
        void testCreateUniqueIndex() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                assertNotNull(uniqueTree);
                assertTrue(uniqueTree.isUnique());
                assertFalse(uniqueTree.isPrimary());
                assertEquals(IndexType.UNIQUE, uniqueTree.getIndexType());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("创建主键索引")
        void testCreatePrimaryIndex() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree primaryTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.PRIMARY, mtr);

                assertTrue(primaryTree.isUnique());
                assertTrue(primaryTree.isPrimary());
                assertEquals(IndexType.PRIMARY, primaryTree.getIndexType());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("唯一索引插入不重复的键")
        void testInsertUniqueKeys() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                for (int i = 1; i <= 10; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    assertTrue(uniqueTree.insert(record, IntKeyComparator.intToBytes(i), mtr));
                }

                assertEquals(10, uniqueTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("唯一索引插入重复键应抛异常")
        void testInsertDuplicateKeyShouldThrow() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                byte[] record1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                uniqueTree.insert(record1, IntKeyComparator.intToBytes(100), mtr);

                byte[] record2 = SimpleRecordBuilder.buildRecord(100, new byte[]{2}, 2);

                DuplicateKeyException ex = assertThrows(DuplicateKeyException.class, () -> {
                    uniqueTree.insert(record2, IntKeyComparator.intToBytes(100), mtr);
                });

                assertEquals(100, ex.getKeyAsInt());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("tryInsert 不抛异常")
        void testTryInsert() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                byte[] record1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                assertTrue(uniqueTree.tryInsert(record1, IntKeyComparator.intToBytes(100), mtr));

                byte[] record2 = SimpleRecordBuilder.buildRecord(100, new byte[]{2}, 2);
                assertFalse(uniqueTree.tryInsert(record2, IntKeyComparator.intToBytes(100), mtr));

                assertEquals(1, uniqueTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("insertOrUpdate")
        void testInsertOrUpdate() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                byte[] record1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                boolean wasUpdate1 = uniqueTree.insertOrUpdate(
                        record1, IntKeyComparator.intToBytes(100), RECORD_SIZE, mtr);
                assertFalse(wasUpdate1); // 新插入

                byte[] record2 = SimpleRecordBuilder.buildRecord(100, new byte[]{2}, 2);
                boolean wasUpdate2 = uniqueTree.insertOrUpdate(
                        record2, IntKeyComparator.intToBytes(100), RECORD_SIZE, mtr);
                assertTrue(wasUpdate2); // 更新

                assertEquals(1, uniqueTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("唯一索引搜索和删除")
        void testSearchAndDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                for (int i = 1; i <= 5; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i * 10, new byte[]{(byte) i}, 2);
                    uniqueTree.insert(record, IntKeyComparator.intToBytes(i * 10), mtr);
                }

                // 搜索
                assertTrue(uniqueTree.containsKey(IntKeyComparator.intToBytes(30), mtr));
                assertFalse(uniqueTree.containsKey(IntKeyComparator.intToBytes(25), mtr));

                // 删除
                assertTrue(uniqueTree.delete(IntKeyComparator.intToBytes(30), RECORD_SIZE, mtr));
                assertFalse(uniqueTree.containsKey(IntKeyComparator.intToBytes(30), mtr));
                assertEquals(4, uniqueTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("唯一索引范围扫描")
        void testRangeScan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                for (int i = 1; i <= 10; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    uniqueTree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                try (BTreeRangeScanner scanner = uniqueTree.rangeScan(mtr,
                        RangeBound.inclusive(IntKeyComparator.intToBytes(3)),
                        RangeBound.inclusive(IntKeyComparator.intToBytes(7)))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(3, 4, 5, 6, 7), keys);
                }

                mtr.commit();
            }
        }
    }

    // ==================== DuplicateKeyBTree 测试 ====================

    @Nested
    @DisplayName("DuplicateKeyBTree 测试")
    class DuplicateKeyBTreeTests {

        @Test
        @DisplayName("创建支持重复键的索引")
        void testCreate() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                assertNotNull(dupTree);
                assertEquals(0, dupTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("插入重复键")
        void testInsertDuplicateKeys() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                // 插入多个相同键的记录
                for (int i = 1; i <= 5; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{(byte) i}, 2);
                    assertTrue(dupTree.insert(record, IntKeyComparator.intToBytes(100), mtr));
                }

                assertEquals(5, dupTree.getRecordCount());
                assertTrue(dupTree.containsKey(IntKeyComparator.intToBytes(100), mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("统计重复键数量")
        void testCountKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                // 插入不同数量的重复键
                for (int i = 1; i <= 3; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(10, new byte[]{(byte) i}, 2);
                    dupTree.insert(record, IntKeyComparator.intToBytes(10), mtr);
                }

                for (int i = 1; i <= 5; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(20, new byte[]{(byte) i}, 2);
                    dupTree.insert(record, IntKeyComparator.intToBytes(20), mtr);
                }

                assertEquals(3, dupTree.countKey(IntKeyComparator.intToBytes(10), mtr));
                assertEquals(5, dupTree.countKey(IntKeyComparator.intToBytes(20), mtr));
                assertEquals(0, dupTree.countKey(IntKeyComparator.intToBytes(30), mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("查找所有匹配的记录")
        void testFindAll() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 4; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(50, new byte[]{(byte) i}, 2);
                    dupTree.insert(record, IntKeyComparator.intToBytes(50), mtr);
                }

                // 使用 countKey 代替 findAll
                int count = dupTree.countKey(IntKeyComparator.intToBytes(50), mtr);
                assertEquals(4, count);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除第一个匹配的记录")
        void testDeleteFirst() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 3; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{(byte) i}, 2);
                    dupTree.insert(record, IntKeyComparator.intToBytes(100), mtr);
                }

                assertEquals(3, dupTree.getRecordCount());

                assertTrue(dupTree.deleteFirst(IntKeyComparator.intToBytes(100), RECORD_SIZE, mtr));
                assertEquals(2, dupTree.getRecordCount());
                assertEquals(2, dupTree.countKey(IntKeyComparator.intToBytes(100), mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除所有匹配的记录")
        void testDeleteAll() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                // 插入 key=100 的 4 条记录
                for (int i = 1; i <= 4; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{(byte) i}, 2);
                    dupTree.insert(record, IntKeyComparator.intToBytes(100), mtr);
                }

                // 插入 key=200 的 2 条记录
                for (int i = 1; i <= 2; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(200, new byte[]{(byte) i}, 2);
                    dupTree.insert(record, IntKeyComparator.intToBytes(200), mtr);
                }

                assertEquals(6, dupTree.getRecordCount());

                // 删除所有 key=100 的记录
                int deleted = dupTree.deleteAll(IntKeyComparator.intToBytes(100), RECORD_SIZE, mtr);
                assertEquals(4, deleted);
                assertEquals(2, dupTree.getRecordCount());
                assertFalse(dupTree.containsKey(IntKeyComparator.intToBytes(100), mtr));
                assertTrue(dupTree.containsKey(IntKeyComparator.intToBytes(200), mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("等值扫描")
        void testEqualScan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                // 插入混合数据
                dupTree.insert(SimpleRecordBuilder.buildRecord(10, new byte[]{1}, 2),
                        IntKeyComparator.intToBytes(10), mtr);
                dupTree.insert(SimpleRecordBuilder.buildRecord(20, new byte[]{1}, 2),
                        IntKeyComparator.intToBytes(20), mtr);
                dupTree.insert(SimpleRecordBuilder.buildRecord(20, new byte[]{2}, 2),
                        IntKeyComparator.intToBytes(20), mtr);
                dupTree.insert(SimpleRecordBuilder.buildRecord(20, new byte[]{3}, 2),
                        IntKeyComparator.intToBytes(20), mtr);
                dupTree.insert(SimpleRecordBuilder.buildRecord(30, new byte[]{1}, 2),
                        IntKeyComparator.intToBytes(30), mtr);

                // 等值扫描 key=20
                try (BTreeRangeScanner scanner = dupTree.equalScan(mtr,
                        IntKeyComparator.intToBytes(20))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(3, keys.size());
                    assertTrue(keys.stream().allMatch(k -> k == 20));
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("混合键的范围扫描")
        void testRangeScanWithDuplicates() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                // 插入数据（有重复）
                int[] keys = {10, 20, 20, 30, 30, 30, 40};
                for (int i = 0; i < keys.length; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(keys[i], new byte[]{(byte) i}, 2);
                    dupTree.insert(record, IntKeyComparator.intToBytes(keys[i]), mtr);
                }

                // 范围扫描
                try (BTreeRangeScanner scanner = dupTree.rangeScan(mtr,
                        RangeBound.inclusive(IntKeyComparator.intToBytes(20)),
                        RangeBound.inclusive(IntKeyComparator.intToBytes(30)))) {
                    List<Integer> scannedKeys = scanner.scanKeysAsInt();
                    assertEquals(5, scannedKeys.size()); // 2 个 20 + 3 个 30
                }

                mtr.commit();
            }
        }
    }

    // ==================== 集成测试 ====================

    @Nested
    @DisplayName("集成测试")
    class IntegrationTests {

        @Test
        @DisplayName("唯一索引和普通索引对比")
        void testUniqueVsNonUnique() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                // 唯一索引
                UniqueBTree uniqueTree = UniqueBTree.create(
                        1L, 0, bufferPool, comparator, IndexType.UNIQUE, mtr);

                // 普通索引（允许重复）
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.create(
                        2L, 0, bufferPool, comparator, mtr);

                // 唯一索引：插入重复键失败
                byte[] record1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                byte[] record2 = SimpleRecordBuilder.buildRecord(100, new byte[]{2}, 2);

                uniqueTree.insert(record1, IntKeyComparator.intToBytes(100), mtr);
                assertThrows(DuplicateKeyException.class, () -> {
                    uniqueTree.insert(record2, IntKeyComparator.intToBytes(100), mtr);
                });
                assertEquals(1, uniqueTree.getRecordCount());

                // 普通索引：插入重复键成功
                dupTree.insert(record1, IntKeyComparator.intToBytes(100), mtr);
                dupTree.insert(record2, IntKeyComparator.intToBytes(100), mtr);
                assertEquals(2, dupTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("包装现有 B+Tree")
        void testWrapExistingTree() throws Exception {
            BTree btree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 5; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            // 包装为唯一索引
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                UniqueBTree uniqueTree = UniqueBTree.wrap(btree, IndexType.UNIQUE);
                assertEquals(5, uniqueTree.getRecordCount());

                // 尝试插入重复键
                byte[] record = SimpleRecordBuilder.buildRecord(3, new byte[]{99}, 2);
                assertThrows(DuplicateKeyException.class, () -> {
                    uniqueTree.insert(record, IntKeyComparator.intToBytes(3), mtr);
                });

                mtr.commit();
            }

            // 包装为普通索引
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DuplicateKeyBTree dupTree = DuplicateKeyBTree.wrap(btree);
                assertEquals(5, dupTree.getRecordCount());

                // 可以插入重复键
                byte[] record = SimpleRecordBuilder.buildRecord(3, new byte[]{99}, 2);
                assertTrue(dupTree.insert(record, IntKeyComparator.intToBytes(3), mtr));
                assertEquals(6, dupTree.getRecordCount());

                mtr.commit();
            }
        }
    }
}
