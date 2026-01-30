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
 * BTreeRangeScanner 单元测试
 *
 * @author MiniDB
 */
class BTreeRangeScannerTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

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

    // ==================== 全表扫描测试 ====================

    @Nested
    @DisplayName("全表扫描")
    class FullScanTests {

        @Test
        @DisplayName("扫描空树")
        void testFullScanEmptyTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertTrue(keys.isEmpty());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("扫描所有记录")
        void testFullScanAllRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 30, 10, 50, 20, 40);

                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 20, 30, 40, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("count 统计记录数")
        void testCount() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    assertEquals(5, scanner.count());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("exists 检查是否存在记录")
        void testExists() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree emptyTree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                BTree nonEmptyTree = createTreeWithData(mtr, 10);

                try (BTreeRangeScanner emptyScanner = emptyTree.fullScan(mtr);
                     BTreeRangeScanner nonEmptyScanner = nonEmptyTree.fullScan(mtr)) {
                    assertFalse(emptyScanner.exists());
                    assertTrue(nonEmptyScanner.exists());
                }

                mtr.commit();
            }
        }
    }

    // ==================== 等值查询测试 ====================

    @Nested
    @DisplayName("等值查询")
    class EqualScanTests {

        @Test
        @DisplayName("查找存在的键")
        void testEqualScanExistingKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.equalScan(mtr, IntKeyComparator.intToBytes(30))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(30), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("查找不存在的键")
        void testEqualScanNonExistingKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.equalScan(mtr, IntKeyComparator.intToBytes(25))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertTrue(keys.isEmpty());
                }

                mtr.commit();
            }
        }
    }

    // ==================== 大于查询测试 ====================

    @Nested
    @DisplayName("大于查询")
    class GreaterThanTests {

        @Test
        @DisplayName("> 查询")
        void testGreaterThan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.greaterThan(mtr, IntKeyComparator.intToBytes(30))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(40, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName(">= 查询")
        void testGreaterOrEqual() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.greaterOrEqual(mtr, IntKeyComparator.intToBytes(30))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(30, 40, 50), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("> 最大值（空结果）")
        void testGreaterThanMax() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                try (BTreeRangeScanner scanner = btree.greaterThan(mtr, IntKeyComparator.intToBytes(30))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertTrue(keys.isEmpty());
                }

                mtr.commit();
            }
        }
    }

    // ==================== 小于查询测试 ====================

    @Nested
    @DisplayName("小于查询")
    class LessThanTests {

        @Test
        @DisplayName("< 查询")
        void testLessThan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.lessThan(mtr, IntKeyComparator.intToBytes(30))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 20), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("<= 查询")
        void testLessOrEqual() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.lessOrEqual(mtr, IntKeyComparator.intToBytes(30))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(10, 20, 30), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("< 最小值（空结果）")
        void testLessThanMin() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                try (BTreeRangeScanner scanner = btree.lessThan(mtr, IntKeyComparator.intToBytes(10))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertTrue(keys.isEmpty());
                }

                mtr.commit();
            }
        }
    }

    // ==================== BETWEEN 查询测试 ====================

    @Nested
    @DisplayName("BETWEEN 查询")
    class BetweenTests {

        @Test
        @DisplayName("BETWEEN 闭区间")
        void testBetweenInclusive() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.between(mtr,
                        IntKeyComparator.intToBytes(20),
                        IntKeyComparator.intToBytes(40))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(20, 30, 40), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("BETWEEN 开区间")
        void testBetweenExclusive() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = BTreeRangeScanner.openRange(
                        btree, bufferPool, comparator, mtr,
                        IntKeyComparator.intToBytes(20),
                        IntKeyComparator.intToBytes(40))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(30), keys);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("BETWEEN 空范围")
        void testBetweenEmptyRange() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                // 范围 25-27，没有匹配的键
                try (BTreeRangeScanner scanner = btree.between(mtr,
                        IntKeyComparator.intToBytes(25),
                        IntKeyComparator.intToBytes(27))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertTrue(keys.isEmpty());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("BETWEEN 单个值")
        void testBetweenSingleValue() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.between(mtr,
                        IntKeyComparator.intToBytes(30),
                        IntKeyComparator.intToBytes(30))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(30), keys);
                }

                mtr.commit();
            }
        }
    }

    // ==================== 迭代器测试 ====================

    @Nested
    @DisplayName("迭代器")
    class IteratorTests {

        @Test
        @DisplayName("使用 for-each 遍历")
        void testForEachIteration() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                int count = 0;
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    for (BTreeRangeScanner.ScanEntry entry : scanner) {
                        count++;
                        assertTrue(entry.getKeyAsInt() >= 10 && entry.getKeyAsInt() <= 30);
                    }
                }

                assertEquals(3, count);

                mtr.commit();
            }
        }
    }

    // ==================== first 方法测试 ====================

    @Nested
    @DisplayName("first 方法")
    class FirstTests {

        @Test
        @DisplayName("获取第一条匹配记录")
        void testFirst() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30, 40, 50);

                try (BTreeRangeScanner scanner = btree.greaterOrEqual(mtr, IntKeyComparator.intToBytes(25))) {
                    BTreeRangeScanner.ScanEntry first = scanner.first(1);
                    assertNotNull(first);
                    assertEquals(30, first.getKeyAsInt());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("空范围返回 null")
        void testFirstEmptyRange() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = createTreeWithData(mtr, 10, 20, 30);

                try (BTreeRangeScanner scanner = btree.greaterThan(mtr, IntKeyComparator.intToBytes(30))) {
                    BTreeRangeScanner.ScanEntry first = scanner.first(1);
                    assertNull(first);
                }

                mtr.commit();
            }
        }
    }

    // ==================== 大规模测试 ====================

    @Nested
    @DisplayName("大规模测试")
    class LargeScaleTests {

        @Test
        @DisplayName("扫描 100 条记录")
        void testScan100Records() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入 100 条记录
                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 全表扫描
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    assertEquals(100, scanner.count());
                }

                // 范围扫描 50-75
                try (BTreeRangeScanner scanner = btree.between(mtr,
                        IntKeyComparator.intToBytes(50),
                        IntKeyComparator.intToBytes(75))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(26, keys.size()); // 50 到 75 共 26 个
                    assertEquals(50, keys.get(0));
                    assertEquals(75, keys.get(keys.size() - 1));
                }

                mtr.commit();
            }
        }
    }
}
