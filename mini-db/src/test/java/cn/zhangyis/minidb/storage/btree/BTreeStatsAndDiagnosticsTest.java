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

import static org.junit.jupiter.api.Assertions.*;

/**
 * B+Tree 统计信息和诊断工具测试
 *
 * @author MiniDB
 */
class BTreeStatsAndDiagnosticsTest {

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

    // ==================== BTreeStats 测试 ====================

    @Nested
    @DisplayName("BTreeStats 测试")
    class BTreeStatsTests {

        @Test
        @DisplayName("基本统计信息")
        void testBasicStats() {
            BTreeStats stats = new BTreeStats(1L);
            stats.setTreeHeight(3);
            stats.setRecordCount(1000);
            stats.setLeafPageCount(10);
            stats.setInternalPageCount(2);
            stats.setTotalPageCount(12);

            assertEquals(1L, stats.getIndexId());
            assertEquals(3, stats.getTreeHeight());
            assertEquals(1000, stats.getRecordCount());
            assertEquals(10, stats.getLeafPageCount());
            assertEquals(2, stats.getInternalPageCount());
            assertEquals(12, stats.getTotalPageCount());
        }

        @Test
        @DisplayName("选择性估计")
        void testSelectivityEstimation() {
            BTreeStats stats = new BTreeStats(1L);
            stats.setRecordCount(100);
            stats.setMinKey(IntKeyComparator.intToBytes(0));
            stats.setMaxKey(IntKeyComparator.intToBytes(100));

            // 全范围
            double fullSelectivity = stats.estimateSelectivity(null, null);
            assertEquals(1.0, fullSelectivity, 0.01);

            // 半范围
            double halfSelectivity = stats.estimateSelectivity(
                    IntKeyComparator.intToBytes(0),
                    IntKeyComparator.intToBytes(50));
            assertEquals(0.5, halfSelectivity, 0.01);

            // 四分之一范围
            double quarterSelectivity = stats.estimateSelectivity(
                    IntKeyComparator.intToBytes(25),
                    IntKeyComparator.intToBytes(50));
            assertEquals(0.25, quarterSelectivity, 0.01);
        }

        @Test
        @DisplayName("范围计数估计")
        void testRangeCountEstimation() {
            BTreeStats stats = new BTreeStats(1L);
            stats.setRecordCount(1000);
            stats.setMinKey(IntKeyComparator.intToBytes(0));
            stats.setMaxKey(IntKeyComparator.intToBytes(1000));

            long estimatedCount = stats.estimateRangeCount(
                    IntKeyComparator.intToBytes(100),
                    IntKeyComparator.intToBytes(200));

            // 10% 的范围应该返回约 100 条记录
            assertEquals(100, estimatedCount, 10);
        }

        @Test
        @DisplayName("统计信息过时检查")
        void testStaleCheck() throws InterruptedException {
            BTreeStats stats = new BTreeStats(1L);

            assertFalse(stats.isStale(1000)); // 1秒内不过时

            Thread.sleep(50);
            assertTrue(stats.isStale(10)); // 10毫秒后过时
        }
    }

    // ==================== BTreeStatsCollector 测试 ====================

    @Nested
    @DisplayName("BTreeStatsCollector 测试")
    class BTreeStatsCollectorTests {

        @Test
        @DisplayName("收集空树统计")
        void testCollectEmptyTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                BTreeStats stats = BTreeStatsCollector.collect(btree, mtr);

                assertEquals(1L, stats.getIndexId());
                assertEquals(1, stats.getTreeHeight());
                assertEquals(0, stats.getRecordCount());
                assertEquals(1, stats.getTotalPageCount());
                assertEquals(1, stats.getLeafPageCount());
                assertEquals(0, stats.getInternalPageCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("收集有数据的树统计")
        void testCollectWithData() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入数据
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                BTreeStats stats = BTreeStatsCollector.collect(btree, mtr);

                assertEquals(50, stats.getRecordCount());
                assertTrue(stats.getTotalPageCount() >= 1);
                assertNotNull(stats.getMinKey());
                assertNotNull(stats.getMaxKey());
                assertEquals(1, IntKeyComparator.bytesToInt(stats.getMinKey()));
                assertEquals(50, IntKeyComparator.bytesToInt(stats.getMaxKey()));
                assertTrue(stats.getAvgFillFactor() > 0);
                assertTrue(stats.getCollectionDurationMs() >= 0);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("快速收集基本统计")
        void testCollectBasic() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 20; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                BTreeStats stats = BTreeStatsCollector.collectBasic(btree);

                assertEquals(1L, stats.getIndexId());
                assertEquals(1, stats.getTreeHeight());
                assertEquals(20, stats.getRecordCount());
                assertEquals(0, stats.getCollectionDurationMs());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("采样收集统计")
        void testCollectSampled() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 50% 采样
                BTreeStats stats = BTreeStatsCollector.collectSampled(btree, 0.5, mtr);

                assertEquals(100, stats.getRecordCount());
                assertNotNull(stats.getMinKey());
                assertNotNull(stats.getMaxKey());

                mtr.commit();
            }
        }
    }

    // ==================== BTreeDiagnostics 测试 ====================

    @Nested
    @DisplayName("BTreeDiagnostics 测试")
    class BTreeDiagnosticsTests {

        @Test
        @DisplayName("验证空树")
        void testValidateEmptyTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                BTreeDiagnostics.ValidationResult result = BTreeDiagnostics.validate(btree, mtr);

                assertTrue(result.isValid());
                assertEquals(1, result.getPageCount());
                assertEquals(0, result.getRecordCount());
                assertTrue(result.getErrors().isEmpty());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("验证有数据的树")
        void testValidateWithData() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                BTreeDiagnostics.ValidationResult result = BTreeDiagnostics.validate(btree, mtr);

                assertTrue(result.isValid(), "树应该有效: " + result);
                assertEquals(50, result.getRecordCount());
                assertTrue(result.getPageCount() >= 1);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("打印树结构")
        void testPrintTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 10; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i * 10, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i * 10), mtr);
                }

                String treeStr = BTreeDiagnostics.printTree(btree, mtr);

                assertNotNull(treeStr);
                assertTrue(treeStr.contains("B+Tree Structure"));
                assertTrue(treeStr.contains("Index ID: 1"));
                assertTrue(treeStr.contains("Level 0"));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("获取页面信息")
        void testGetPageInfo() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 5; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i * 10, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i * 10), mtr);
                }

                int rootPageNo = btree.getMetadata().getRootPageNo();
                BTreeDiagnostics.PageInfo info = BTreeDiagnostics.getPageInfo(btree, rootPageNo, mtr);

                assertNotNull(info);
                assertEquals(rootPageNo, info.pageNo);
                assertEquals(0, info.level); // 单页树，根是叶子
                assertEquals(5, info.recordCount);
                assertEquals(5, info.keys.size());
                assertTrue(info.keys.contains(10));
                assertTrue(info.keys.contains(50));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("验证结果输出")
        void testValidationResultToString() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                BTreeDiagnostics.ValidationResult result = BTreeDiagnostics.validate(btree, mtr);
                String str = result.toString();

                assertNotNull(str);
                assertTrue(str.contains("valid: true"));
                assertTrue(str.contains("pageCount:"));
                assertTrue(str.contains("recordCount:"));

                mtr.commit();
            }
        }
    }

    // ==================== 集成测试 ====================

    @Nested
    @DisplayName("集成测试")
    class IntegrationTests {

        @Test
        @DisplayName("统计和验证一致性")
        void testStatsAndValidationConsistency() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 收集统计
                BTreeStats stats = BTreeStatsCollector.collect(btree, mtr);

                // 验证
                BTreeDiagnostics.ValidationResult validation = BTreeDiagnostics.validate(btree, mtr);

                // 统计和验证应该一致
                assertTrue(validation.isValid());
                assertEquals(stats.getRecordCount(), validation.getRecordCount());
                assertEquals(stats.getTotalPageCount(), validation.getPageCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除后统计更新")
        void testStatsAfterDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 删除一半
                int recordSize = SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE + 1;
                for (int i = 2; i <= 50; i += 2) {
                    btree.delete(IntKeyComparator.intToBytes(i), recordSize, mtr);
                }

                // 收集统计
                BTreeStats stats = BTreeStatsCollector.collect(btree, mtr);

                assertEquals(25, stats.getRecordCount());
                assertEquals(1, IntKeyComparator.bytesToInt(stats.getMinKey()));
                assertEquals(49, IntKeyComparator.bytesToInt(stats.getMaxKey()));

                // 验证
                BTreeDiagnostics.ValidationResult validation = BTreeDiagnostics.validate(btree, mtr);
                assertTrue(validation.isValid());

                mtr.commit();
            }
        }
    }
}
