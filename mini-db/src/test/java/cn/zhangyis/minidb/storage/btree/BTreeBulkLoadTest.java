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
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B+Tree 批量加载和索引重建测试
 *
 * @author MiniDB
 */
class BTreeBulkLoadTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(200, diskManager);
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

    // ==================== BulkLoadConfig 测试 ====================

    @Nested
    @DisplayName("BulkLoadConfig 测试")
    class BulkLoadConfigTests {

        @Test
        @DisplayName("默认配置")
        void testDefaultConfig() {
            BulkLoadConfig config = new BulkLoadConfig();

            assertEquals(0.9, config.getFillFactor(), 0.01);
            assertEquals(1000, config.getBatchSize());
            assertTrue(config.isValidateSorted());
            assertFalse(config.isCollectStats());
        }

        @Test
        @DisplayName("自定义配置")
        void testCustomConfig() {
            BulkLoadConfig config = new BulkLoadConfig(0.75, 500);

            assertEquals(0.75, config.getFillFactor(), 0.01);
            assertEquals(500, config.getBatchSize());
        }

        @Test
        @DisplayName("Builder 方法")
        void testBuilderMethods() {
            BulkLoadConfig config = new BulkLoadConfig()
                    .fillFactor(0.8)
                    .batchSize(2000)
                    .validateSorted(false)
                    .collectStats(true)
                    .showProgress(true);

            assertEquals(0.8, config.getFillFactor(), 0.01);
            assertEquals(2000, config.getBatchSize());
            assertFalse(config.isValidateSorted());
            assertTrue(config.isCollectStats());
            assertTrue(config.isShowProgress());
        }

        @Test
        @DisplayName("填充率边界")
        void testFillFactorBounds() {
            BulkLoadConfig config = new BulkLoadConfig();

            config.fillFactor(0.3); // 低于最小值
            assertEquals(0.5, config.getFillFactor(), 0.01);

            config.fillFactor(1.5); // 高于最大值
            assertEquals(1.0, config.getFillFactor(), 0.01);
        }
    }

    // ==================== BulkLoadRecord 测试 ====================

    @Nested
    @DisplayName("BulkLoadRecord 测试")
    class BulkLoadRecordTests {

        @Test
        @DisplayName("创建记录")
        void testCreateRecord() {
            BulkLoadRecord record = BulkLoadRecord.of(100, new byte[]{1, 2, 3});

            assertEquals(100, record.getKeyAsInt());
            assertNotNull(record.getKey());
            assertNotNull(record.getData());
        }

        @Test
        @DisplayName("简化创建")
        void testSimpleCreate() {
            BulkLoadRecord record = BulkLoadRecord.of(50);

            assertEquals(50, record.getKeyAsInt());
        }
    }

    // ==================== BTreeBulkLoader 测试 ====================

    @Nested
    @DisplayName("BTreeBulkLoader 测试")
    class BTreeBulkLoaderTests {

        @Test
        @DisplayName("批量加载空数据")
        void testBulkLoadEmpty() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                BulkLoadConfig config = new BulkLoadConfig();

                BulkLoadResult result = BTreeBulkLoader.load(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                assertTrue(result.isSuccess());
                assertEquals(0, result.getRecordCount());
                assertEquals(1, result.getLeafPageCount());
                assertEquals(1, result.getTreeHeight());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量加载少量数据")
        void testBulkLoadSmall() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 10; i++) {
                    records.add(BulkLoadRecord.of(i * 10));
                }

                BulkLoadConfig config = new BulkLoadConfig();
                BTree btree = BTreeBulkLoader.loadAndGetTree(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                assertNotNull(btree);
                assertEquals(10, btree.getRecordCount());

                // 验证所有记录都能找到
                for (int i = 1; i <= 10; i++) {
                    assertTrue(btree.containsKey(IntKeyComparator.intToBytes(i * 10), mtr));
                }

                // 验证顺序
                try (BTreeRangeScanner scanner = btree.fullScan(mtr)) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(10, keys.size());
                    assertEquals(10, keys.get(0));
                    assertEquals(100, keys.get(9));
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量加载大量数据")
        void testBulkLoadLarge() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 500; i++) {
                    records.add(BulkLoadRecord.of(i));
                }

                BulkLoadConfig config = new BulkLoadConfig().fillFactor(0.9);
                BulkLoadResult result = BTreeBulkLoader.load(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                assertTrue(result.isSuccess());
                assertEquals(500, result.getRecordCount());
                assertTrue(result.getLeafPageCount() >= 1);
                assertTrue(result.getDurationMs() >= 0);
                assertTrue(result.getRecordsPerSecond() > 0);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量加载并验证")
        void testBulkLoadAndValidate() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 100; i++) {
                    records.add(BulkLoadRecord.of(i));
                }

                BulkLoadConfig config = new BulkLoadConfig();
                BTree btree = BTreeBulkLoader.loadAndGetTree(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                // 验证树结构
                BTreeDiagnostics.ValidationResult validation = BTreeDiagnostics.validate(btree, mtr);
                assertTrue(validation.isValid(), "验证失败: " + validation);
                assertEquals(100, validation.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量加载未排序数据应失败")
        void testBulkLoadUnsortedShouldFail() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                records.add(BulkLoadRecord.of(30));
                records.add(BulkLoadRecord.of(10)); // 未排序
                records.add(BulkLoadRecord.of(20));

                BulkLoadConfig config = new BulkLoadConfig().validateSorted(true);

                BulkLoadResult result = BTreeBulkLoader.load(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                assertFalse(result.isSuccess());
                assertNotNull(result.getErrorMessage());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量加载禁用排序验证")
        void testBulkLoadWithoutSortValidation() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 10; i++) {
                    records.add(BulkLoadRecord.of(i));
                }

                BulkLoadConfig config = new BulkLoadConfig().validateSorted(false);
                BTree btree = BTreeBulkLoader.loadAndGetTree(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                assertNotNull(btree);
                assertEquals(10, btree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("不同填充率")
        void testDifferentFillFactors() throws Exception {
            // 测试 50% 填充率
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 200; i++) {
                    records.add(BulkLoadRecord.of(i));
                }

                BulkLoadConfig config50 = new BulkLoadConfig().fillFactor(0.5);
                BulkLoadResult result50 = BTreeBulkLoader.load(
                        1L, 0, records.iterator(), comparator, bufferPool, config50, mtr);

                assertTrue(result50.isSuccess());

                mtr.commit();
            }

            // 测试 90% 填充率
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 200; i++) {
                    records.add(BulkLoadRecord.of(i));
                }

                BulkLoadConfig config90 = new BulkLoadConfig().fillFactor(0.9);
                BulkLoadResult result90 = BTreeBulkLoader.load(
                        2L, 0, records.iterator(), comparator, bufferPool, config90, mtr);

                assertTrue(result90.isSuccess());

                // 90% 填充率应该使用更少的页面
                // （由于记录较小，可能差异不明显）

                mtr.commit();
            }
        }
    }

    // ==================== BTreeRebuilder 测试 ====================

    @Nested
    @DisplayName("BTreeRebuilder 测试")
    class BTreeRebuilderTests {

        @Test
        @DisplayName("重建索引")
        void testRebuild() throws Exception {
            // 创建原始索引
            BTree oldTree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                oldTree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    oldTree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            // 重建索引
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BulkLoadConfig config = new BulkLoadConfig().fillFactor(0.9);
                BTree newTree = BTreeRebuilder.rebuild(oldTree, config, bufferPool, mtr);

                assertNotNull(newTree);
                assertEquals(50, newTree.getRecordCount());

                // 验证所有记录
                for (int i = 1; i <= 50; i++) {
                    assertTrue(newTree.containsKey(IntKeyComparator.intToBytes(i), mtr));
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("重建并返回结果")
        void testRebuildWithResult() throws Exception {
            // 创建原始索引
            BTree oldTree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                oldTree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    oldTree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            // 重建并获取结果
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BulkLoadConfig config = new BulkLoadConfig();
                BTreeRebuilder.RebuildResult result = BTreeRebuilder.rebuildWithResult(
                        oldTree, config, bufferPool, mtr);

                assertTrue(result.isSuccess());
                assertNotNull(result.getOldStats());
                assertNotNull(result.getNewStats());
                assertNotNull(result.getNewTree());
                assertTrue(result.getDurationMs() >= 0);

                assertEquals(100, result.getNewStats().getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("优化索引（如果需要）")
        void testOptimizeIfNeeded() throws Exception {
            // 创建索引
            BTree tree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                tree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 30; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    tree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            // 尝试优化（设置很低的阈值，应该不需要优化）
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BulkLoadConfig config = new BulkLoadConfig();
                BTree optimizedTree = BTreeRebuilder.optimizeIfNeeded(
                        tree, 0.1, config, bufferPool, mtr);

                // 由于填充率应该高于 10%，应该返回原树
                assertNotNull(optimizedTree);
                assertEquals(30, optimizedTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("重建空索引")
        void testRebuildEmptyIndex() throws Exception {
            // 创建空索引
            BTree oldTree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                oldTree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                mtr.commit();
            }

            // 重建
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BulkLoadConfig config = new BulkLoadConfig();
                BTree newTree = BTreeRebuilder.rebuild(oldTree, config, bufferPool, mtr);

                assertNotNull(newTree);
                assertEquals(0, newTree.getRecordCount());

                mtr.commit();
            }
        }
    }

    // ==================== 集成测试 ====================

    @Nested
    @DisplayName("集成测试")
    class IntegrationTests {

        @Test
        @DisplayName("批量加载后插入和删除")
        void testBulkLoadThenInsertDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                // 批量加载
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 50; i++) {
                    records.add(BulkLoadRecord.of(i * 2)); // 偶数
                }

                BulkLoadConfig config = new BulkLoadConfig();
                BTree btree = BTreeBulkLoader.loadAndGetTree(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                assertEquals(50, btree.getRecordCount());

                // 插入奇数
                for (int i = 1; i <= 10; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i * 2 - 1, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i * 2 - 1), mtr);
                }

                assertEquals(60, btree.getRecordCount());

                // 删除一些记录
                int recordSize = SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE + 1;
                for (int i = 1; i <= 5; i++) {
                    btree.delete(IntKeyComparator.intToBytes(i * 2), recordSize, mtr);
                }

                assertEquals(55, btree.getRecordCount());

                // 验证
                BTreeDiagnostics.ValidationResult validation = BTreeDiagnostics.validate(btree, mtr);
                assertTrue(validation.isValid());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("批量加载后范围扫描")
        void testBulkLoadThenRangeScan() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                List<BulkLoadRecord> records = new ArrayList<>();
                for (int i = 1; i <= 100; i++) {
                    records.add(BulkLoadRecord.of(i));
                }

                BulkLoadConfig config = new BulkLoadConfig();
                BTree btree = BTreeBulkLoader.loadAndGetTree(
                        1L, 0, records.iterator(), comparator, bufferPool, config, mtr);

                // 范围扫描
                try (BTreeRangeScanner scanner = btree.between(mtr,
                        IntKeyComparator.intToBytes(25),
                        IntKeyComparator.intToBytes(75))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(51, keys.size()); // 25 到 75
                    assertEquals(25, keys.get(0));
                    assertEquals(75, keys.get(keys.size() - 1));
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("多次重建")
        void testMultipleRebuilds() throws Exception {
            BTree tree;

            // 创建初始索引
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                tree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    tree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            // 第一次重建
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BulkLoadConfig config = new BulkLoadConfig().fillFactor(0.7);
                tree = BTreeRebuilder.rebuild(tree, config, bufferPool, mtr);
                assertEquals(50, tree.getRecordCount());
                mtr.commit();
            }

            // 第二次重建
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BulkLoadConfig config = new BulkLoadConfig().fillFactor(0.9);
                tree = BTreeRebuilder.rebuild(tree, config, bufferPool, mtr);
                assertEquals(50, tree.getRecordCount());

                // 验证
                BTreeDiagnostics.ValidationResult validation = BTreeDiagnostics.validate(tree, mtr);
                assertTrue(validation.isValid());

                mtr.commit();
            }
        }
    }
}
