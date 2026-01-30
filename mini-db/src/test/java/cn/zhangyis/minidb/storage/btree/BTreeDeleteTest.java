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
 * BTreeDelete 单元测试
 *
 * @author MiniDB
 */
class BTreeDeleteTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

    /** 测试记录大小 */
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

    private BTree createTreeWithData(MiniTransaction mtr, int... keys) throws Exception {
        BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
        for (int key : keys) {
            byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
            btree.insert(record, IntKeyComparator.intToBytes(key), mtr);
        }
        return btree;
    }

    // ==================== 基本删除测试 ====================

    @Nested
    @DisplayName("基本删除")
    class BasicDeleteTests {

        @Test
        @DisplayName("删除存在的键")
        void testDeleteExistingKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                assertEquals(5, btree.getRecordCount());
                assertTrue(btree.containsKey(IntKeyComparator.intToBytes(30), mtr));

                // 删除 key=30
                boolean deleted = btree.delete(IntKeyComparator.intToBytes(30), RECORD_SIZE, mtr);

                assertTrue(deleted);
                assertEquals(4, btree.getRecordCount());
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(30), mtr));

                // 其他键仍然存在
                assertTrue(btree.containsKey(IntKeyComparator.intToBytes(10), mtr));
                assertTrue(btree.containsKey(IntKeyComparator.intToBytes(20), mtr));
                assertTrue(btree.containsKey(IntKeyComparator.intToBytes(40), mtr));
                assertTrue(btree.containsKey(IntKeyComparator.intToBytes(50), mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除不存在的键")
        void testDeleteNonExistingKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                assertEquals(3, btree.getRecordCount());

                // 尝试删除不存在的键
                boolean deleted = btree.delete(IntKeyComparator.intToBytes(25), RECORD_SIZE, mtr);

                assertFalse(deleted);
                assertEquals(3, btree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除第一条记录")
        void testDeleteFirstRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                boolean deleted = btree.delete(IntKeyComparator.intToBytes(10), RECORD_SIZE, mtr);

                assertTrue(deleted);
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(10), mtr));

                // 验证遍历顺序
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(20, 30, 40, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除最后一条记录")
        void testDeleteLastRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                boolean deleted = btree.delete(IntKeyComparator.intToBytes(50), RECORD_SIZE, mtr);

                assertTrue(deleted);
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(50), mtr));

                // 验证遍历顺序
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 20, 30, 40), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除唯一的记录")
        void testDeleteOnlyRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 100);

                assertEquals(1, btree.getRecordCount());

                boolean deleted = btree.delete(IntKeyComparator.intToBytes(100), RECORD_SIZE, mtr);

                assertTrue(deleted);
                assertEquals(0, btree.getRecordCount());
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(100), mtr));

                // 验证树为空
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    assertTrue(scanner.scanKeysAsInt().isEmpty());
                }

                mtr.commit();
            }
        }
    }

    // ==================== 批量删除测试 ====================

    @Nested
    @DisplayName("批量删除")
    class BatchDeleteTests {

        @Test
        @DisplayName("批量删除多个键")
        void testDeleteBatch() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                byte[][] keysToDelete = {
                        IntKeyComparator.intToBytes(20),
                        IntKeyComparator.intToBytes(40)
                };

                int deletedCount = btree.deleteBatch(keysToDelete, RECORD_SIZE, mtr);

                assertEquals(2, deletedCount);
                assertEquals(3, btree.getRecordCount());

                // 验证剩余的键
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 30, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量删除包含不存在的键")
        void testDeleteBatchWithNonExisting() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                byte[][] keysToDelete = {
                        IntKeyComparator.intToBytes(20),
                        IntKeyComparator.intToBytes(25), // 不存在
                        IntKeyComparator.intToBytes(30)
                };

                int deletedCount = btree.deleteBatch(keysToDelete, RECORD_SIZE, mtr);

                assertEquals(2, deletedCount); // 只删除了 2 个
                assertEquals(1, btree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量删除所有记录")
        void testDeleteBatchAll() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                byte[][] keysToDelete = {
                        IntKeyComparator.intToBytes(10),
                        IntKeyComparator.intToBytes(20),
                        IntKeyComparator.intToBytes(30)
                };

                int deletedCount = btree.deleteBatch(keysToDelete, RECORD_SIZE, mtr);

                assertEquals(3, deletedCount);
                assertEquals(0, btree.getRecordCount());

                mtr.commit();
            }
        }
    }

    // ==================== 范围删除测试 ====================

    @Nested
    @DisplayName("范围删除")
    class RangeDeleteTests {

        @Test
        @DisplayName("删除范围内的记录（闭区间）")
        void testDeleteRangeInclusive() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                // 删除 20 <= key <= 40
                int deletedCount = btree.deleteRange(
                        RangeBound.inclusive(IntKeyComparator.intToBytes(20)),
                        RangeBound.inclusive(IntKeyComparator.intToBytes(40)),
                        RECORD_SIZE, mtr);

                assertEquals(3, deletedCount);
                assertEquals(2, btree.getRecordCount());

                // 验证剩余的键
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除范围内的记录（开区间）")
        void testDeleteRangeExclusive() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                // 删除 20 < key < 50
                int deletedCount = btree.deleteRange(
                        RangeBound.exclusive(IntKeyComparator.intToBytes(20)),
                        RangeBound.exclusive(IntKeyComparator.intToBytes(50)),
                        RECORD_SIZE, mtr);

                assertEquals(2, deletedCount); // 30, 40
                assertEquals(3, btree.getRecordCount());

                // 验证剩余的键
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 20, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除大于某值的所有记录")
        void testDeleteGreaterThan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                // 删除 key > 30
                int deletedCount = btree.deleteRange(
                        RangeBound.exclusive(IntKeyComparator.intToBytes(30)),
                        RangeBound.unbounded(),
                        RECORD_SIZE, mtr);

                assertEquals(2, deletedCount); // 40, 50
                assertEquals(3, btree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除小于某值的所有记录")
        void testDeleteLessThan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                // 删除 key < 30
                int deletedCount = btree.deleteRange(
                        RangeBound.unbounded(),
                        RangeBound.exclusive(IntKeyComparator.intToBytes(30)),
                        RECORD_SIZE, mtr);

                assertEquals(2, deletedCount); // 10, 20
                assertEquals(3, btree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除空范围")
        void testDeleteEmptyRange() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                // 删除 25 <= key <= 27（没有匹配的键）
                int deletedCount = btree.deleteRange(
                        RangeBound.inclusive(IntKeyComparator.intToBytes(25)),
                        RangeBound.inclusive(IntKeyComparator.intToBytes(27)),
                        RECORD_SIZE, mtr);

                assertEquals(0, deletedCount);
                assertEquals(3, btree.getRecordCount());

                mtr.commit();
            }
        }
    }

    // ==================== 删除后遍历测试 ====================

    @Nested
    @DisplayName("删除后遍历")
    class TraversalAfterDeleteTests {

        @Test
        @DisplayName("删除后正向遍历正确")
        void testForwardTraversalAfterDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                // 删除中间的记录
                btree.delete(IntKeyComparator.intToBytes(20), RECORD_SIZE, mtr);
                btree.delete(IntKeyComparator.intToBytes(40), RECORD_SIZE, mtr);

                // 验证遍历
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 30, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除后范围查询正确")
        void testRangeQueryAfterDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                // 删除一些记录
                btree.delete(IntKeyComparator.intToBytes(30), RECORD_SIZE, mtr);

                // 范围查询
                try (BTreeRangeScanner scanner = btree.between(mtr,
                        IntKeyComparator.intToBytes(15),
                        IntKeyComparator.intToBytes(45))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(20, 40), keys);
                }

                mtr.commit();
            }
        }
    }

    // ==================== 删除后插入测试 ====================

    @Nested
    @DisplayName("删除后插入")
    class InsertAfterDeleteTests {

        @Test
        @DisplayName("删除后可以重新插入相同的键")
        void testReinsertAfterDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                // 删除
                btree.delete(IntKeyComparator.intToBytes(20), RECORD_SIZE, mtr);
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(20), mtr));

                // 重新插入
                byte[] record = SimpleRecordBuilder.buildRecord(20, new byte[]{99}, 2);
                btree.insert(record, IntKeyComparator.intToBytes(20), mtr);

                assertTrue(btree.containsKey(IntKeyComparator.intToBytes(20), mtr));
                assertEquals(3, btree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除后插入新键保持顺序")
        void testInsertNewKeyAfterDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 30, 50);

                // 删除 30
                btree.delete(IntKeyComparator.intToBytes(30), RECORD_SIZE, mtr);

                // 插入 20 和 40
                byte[] record20 = SimpleRecordBuilder.buildRecord(20, new byte[]{20}, 2);
                byte[] record40 = SimpleRecordBuilder.buildRecord(40, new byte[]{40}, 2);
                btree.insert(record20, IntKeyComparator.intToBytes(20), mtr);
                btree.insert(record40, IntKeyComparator.intToBytes(40), mtr);

                // 验证顺序
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 20, 40, 50), keys);
                }

                mtr.commit();
            }
        }
    }

    // ==================== 大规模删除测试 ====================

    @Nested
    @DisplayName("大规模删除")
    class LargeScaleDeleteTests {

        @Test
        @DisplayName("删除 50 条记录中的一半")
        void testDeleteHalfRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入 50 条记录
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                assertEquals(50, btree.getRecordCount());

                // 删除偶数键
                for (int i = 2; i <= 50; i += 2) {
                    btree.delete(IntKeyComparator.intToBytes(i), RECORD_SIZE, mtr);
                }

                assertEquals(25, btree.getRecordCount());

                // 验证只剩奇数键
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(25, keys.size());
                    for (int key : keys) {
                        assertEquals(1, key % 2, "应该只剩奇数键");
                    }
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除所有记录后重新插入")
        void testDeleteAllThenReinsert() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 1; i <= 20; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 删除所有记录
                for (int i = 1; i <= 20; i++) {
                    btree.delete(IntKeyComparator.intToBytes(i), RECORD_SIZE, mtr);
                }

                assertEquals(0, btree.getRecordCount());

                // 重新插入
                for (int i = 100; i <= 110; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                assertEquals(11, btree.getRecordCount());

                // 验证新记录
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(11, keys.size());
                    assertEquals(100, keys.get(0));
                    assertEquals(110, keys.get(keys.size() - 1));
                }

                mtr.commit();
            }
        }
    }
}
