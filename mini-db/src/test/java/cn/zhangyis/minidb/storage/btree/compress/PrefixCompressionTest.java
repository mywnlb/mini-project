package cn.zhangyis.minidb.storage.btree.compress;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.btree.BTree;
import cn.zhangyis.minidb.storage.btree.IntKeyComparator;
import cn.zhangyis.minidb.storage.btree.SimpleRecordBuilder;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 前缀压缩测试
 *
 * @author MiniDB
 */
class PrefixCompressionTest {

    @TempDir
    Path tempDir;

    // ==================== PrefixCompressor 测试 ====================

    @Nested
    @DisplayName("PrefixCompressor 测试")
    class PrefixCompressorTests {

        @Test
        @DisplayName("计算公共前缀长度")
        void testCommonPrefixLength() {
            byte[] key1 = "hello_world".getBytes(StandardCharsets.UTF_8);
            byte[] key2 = "hello_there".getBytes(StandardCharsets.UTF_8);

            int prefixLen = PrefixCompressor.commonPrefixLength(key1, key2);
            assertEquals(6, prefixLen); // "hello_"

            // 完全相同
            assertEquals(key1.length, PrefixCompressor.commonPrefixLength(key1, key1));

            // 完全不同
            byte[] key3 = "xyz".getBytes(StandardCharsets.UTF_8);
            assertEquals(0, PrefixCompressor.commonPrefixLength(key1, key3));

            // null 处理
            assertEquals(0, PrefixCompressor.commonPrefixLength(null, key1));
            assertEquals(0, PrefixCompressor.commonPrefixLength(key1, null));
        }

        @Test
        @DisplayName("压缩键")
        void testCompress() {
            byte[] key1 = "user_001".getBytes(StandardCharsets.UTF_8);
            byte[] key2 = "user_002".getBytes(StandardCharsets.UTF_8);

            // 第一个键不压缩
            CompressedKey compressed1 = PrefixCompressor.compress(key1, null);
            assertEquals(0, compressed1.getPrefixLength());
            assertArrayEquals(key1, compressed1.getSuffix());

            // 第二个键压缩
            CompressedKey compressed2 = PrefixCompressor.compress(key2, key1);
            assertEquals(7, compressed2.getPrefixLength()); // "user_00"
            assertEquals(1, compressed2.getSuffix().length); // "2"
        }

        @Test
        @DisplayName("解压键")
        void testDecompress() {
            byte[] key1 = "prefix_abc".getBytes(StandardCharsets.UTF_8);
            byte[] key2 = "prefix_xyz".getBytes(StandardCharsets.UTF_8);

            CompressedKey compressed = PrefixCompressor.compress(key2, key1);
            byte[] decompressed = PrefixCompressor.decompress(compressed, key1);

            assertArrayEquals(key2, decompressed);
        }

        @Test
        @DisplayName("计算压缩大小")
        void testCalculateCompressedSize() {
            byte[][] keys = {
                    "user_0001".getBytes(StandardCharsets.UTF_8),
                    "user_0002".getBytes(StandardCharsets.UTF_8),
                    "user_0003".getBytes(StandardCharsets.UTF_8),
                    "user_0010".getBytes(StandardCharsets.UTF_8),
                    "user_0011".getBytes(StandardCharsets.UTF_8)
            };

            int originalSize = PrefixCompressor.calculateOriginalSize(keys);
            int compressedSize = PrefixCompressor.calculateCompressedSize(keys);

            assertTrue(compressedSize < originalSize);
            System.out.println("Original: " + originalSize + ", Compressed: " + compressedSize);
        }

        @Test
        @DisplayName("计算压缩比")
        void testCompressionRatio() {
            // 高度相似的键
            byte[][] similarKeys = {
                    "com.example.service.UserService".getBytes(StandardCharsets.UTF_8),
                    "com.example.service.UserServiceImpl".getBytes(StandardCharsets.UTF_8),
                    "com.example.service.UserServiceTest".getBytes(StandardCharsets.UTF_8),
                    "com.example.service.OrderService".getBytes(StandardCharsets.UTF_8),
                    "com.example.service.OrderServiceImpl".getBytes(StandardCharsets.UTF_8)
            };

            double ratio = PrefixCompressor.calculateCompressionRatio(similarKeys);
            assertTrue(ratio < 0.7); // 应该有显著压缩
            System.out.println("Similar keys compression ratio: " + ratio);

            // 完全不同的键
            byte[][] differentKeys = {
                    "abc".getBytes(StandardCharsets.UTF_8),
                    "xyz".getBytes(StandardCharsets.UTF_8),
                    "123".getBytes(StandardCharsets.UTF_8)
            };

            double ratio2 = PrefixCompressor.calculateCompressionRatio(differentKeys);
            assertTrue(ratio2 > 0.9); // 几乎没有压缩
            System.out.println("Different keys compression ratio: " + ratio2);
        }

        @Test
        @DisplayName("判断是否值得压缩")
        void testIsWorthCompressing() {
            byte[][] goodKeys = {
                    "index_user_id_001".getBytes(StandardCharsets.UTF_8),
                    "index_user_id_002".getBytes(StandardCharsets.UTF_8),
                    "index_user_id_003".getBytes(StandardCharsets.UTF_8)
            };

            assertTrue(PrefixCompressor.isWorthCompressing(goodKeys, 0.8));

            byte[][] badKeys = {
                    "a".getBytes(StandardCharsets.UTF_8),
                    "b".getBytes(StandardCharsets.UTF_8),
                    "c".getBytes(StandardCharsets.UTF_8)
            };

            assertFalse(PrefixCompressor.isWorthCompressing(badKeys, 0.8));
        }
    }

    // ==================== CompressedKey 测试 ====================

    @Nested
    @DisplayName("CompressedKey 测试")
    class CompressedKeyTests {

        @Test
        @DisplayName("创建未压缩的键")
        void testUncompressed() {
            byte[] key = "test_key".getBytes(StandardCharsets.UTF_8);
            CompressedKey compressed = CompressedKey.uncompressed(key);

            assertEquals(0, compressed.getPrefixLength());
            assertArrayEquals(key, compressed.getSuffix());
            assertEquals(key.length, compressed.getOriginalLength());
        }

        @Test
        @DisplayName("压缩大小计算")
        void testCompressedSize() {
            byte[] suffix = "xyz".getBytes(StandardCharsets.UTF_8);
            CompressedKey compressed = new CompressedKey(10, suffix, 13);

            assertEquals(2 + 3, compressed.getCompressedSize()); // 2 bytes prefix len + 3 bytes suffix
            assertEquals(13 - 5, compressed.getSavedBytes()); // original - compressed
        }

        @Test
        @DisplayName("压缩比计算")
        void testCompressionRatio() {
            byte[] suffix = "x".getBytes(StandardCharsets.UTF_8);
            CompressedKey compressed = new CompressedKey(20, suffix, 21);

            double ratio = compressed.getCompressionRatio();
            assertTrue(ratio < 0.2); // 3 / 21 ≈ 0.14
        }
    }

    // ==================== CompressedKeyBlock 测试 ====================

    @Nested
    @DisplayName("CompressedKeyBlock 测试")
    class CompressedKeyBlockTests {

        @Test
        @DisplayName("从键数组构建")
        void testFromKeys() {
            byte[][] keys = {
                    "key_001".getBytes(StandardCharsets.UTF_8),
                    "key_002".getBytes(StandardCharsets.UTF_8),
                    "key_003".getBytes(StandardCharsets.UTF_8)
            };

            CompressedKeyBlock block = CompressedKeyBlock.fromKeys(keys);

            assertEquals(3, block.getKeyCount());
            assertTrue(block.isCompressed());
            assertArrayEquals(keys[0], block.getBaseKey());
        }

        @Test
        @DisplayName("添加键")
        void testAddKey() {
            CompressedKeyBlock block = new CompressedKeyBlock();

            block.addKey("first".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, block.getKeyCount());

            block.addKey("first_second".getBytes(StandardCharsets.UTF_8));
            assertEquals(2, block.getKeyCount());
        }

        @Test
        @DisplayName("获取键")
        void testGetKey() {
            byte[][] keys = {
                    "alpha".getBytes(StandardCharsets.UTF_8),
                    "alpha_beta".getBytes(StandardCharsets.UTF_8),
                    "alpha_beta_gamma".getBytes(StandardCharsets.UTF_8)
            };

            CompressedKeyBlock block = CompressedKeyBlock.fromKeys(keys);

            assertArrayEquals(keys[0], block.getKey(0));
            assertArrayEquals(keys[1], block.getKey(1));
            assertArrayEquals(keys[2], block.getKey(2));
        }

        @Test
        @DisplayName("获取所有键")
        void testGetAllKeys() {
            byte[][] originalKeys = {
                    "test_a".getBytes(StandardCharsets.UTF_8),
                    "test_b".getBytes(StandardCharsets.UTF_8),
                    "test_c".getBytes(StandardCharsets.UTF_8)
            };

            CompressedKeyBlock block = CompressedKeyBlock.fromKeys(originalKeys);
            byte[][] retrievedKeys = block.getAllKeys();

            assertEquals(originalKeys.length, retrievedKeys.length);
            for (int i = 0; i < originalKeys.length; i++) {
                assertArrayEquals(originalKeys[i], retrievedKeys[i]);
            }
        }

        @Test
        @DisplayName("二分搜索")
        void testBinarySearch() {
            byte[][] keys = {
                    "aaa".getBytes(StandardCharsets.UTF_8),
                    "bbb".getBytes(StandardCharsets.UTF_8),
                    "ccc".getBytes(StandardCharsets.UTF_8),
                    "ddd".getBytes(StandardCharsets.UTF_8)
            };

            CompressedKeyBlock block = CompressedKeyBlock.fromKeys(keys);

            assertEquals(0, block.binarySearch("aaa".getBytes(StandardCharsets.UTF_8)));
            assertEquals(2, block.binarySearch("ccc".getBytes(StandardCharsets.UTF_8)));
            assertTrue(block.binarySearch("bbc".getBytes(StandardCharsets.UTF_8)) < 0);
        }

        @Test
        @DisplayName("序列化和反序列化")
        void testSerializeAndDeserialize() {
            byte[][] keys = {
                    "prefix_001".getBytes(StandardCharsets.UTF_8),
                    "prefix_002".getBytes(StandardCharsets.UTF_8),
                    "prefix_010".getBytes(StandardCharsets.UTF_8)
            };

            CompressedKeyBlock original = CompressedKeyBlock.fromKeys(keys);
            byte[] serialized = original.serialize();
            CompressedKeyBlock restored = CompressedKeyBlock.deserialize(serialized);

            assertEquals(original.getKeyCount(), restored.getKeyCount());
            for (int i = 0; i < original.getKeyCount(); i++) {
                assertArrayEquals(original.getKey(i), restored.getKey(i));
            }
        }

        @Test
        @DisplayName("压缩比")
        void testCompressionRatio() {
            byte[][] keys = {
                    "very_long_common_prefix_001".getBytes(StandardCharsets.UTF_8),
                    "very_long_common_prefix_002".getBytes(StandardCharsets.UTF_8),
                    "very_long_common_prefix_003".getBytes(StandardCharsets.UTF_8),
                    "very_long_common_prefix_004".getBytes(StandardCharsets.UTF_8),
                    "very_long_common_prefix_005".getBytes(StandardCharsets.UTF_8)
            };

            CompressedKeyBlock block = CompressedKeyBlock.fromKeys(keys);

            double ratio = block.getCompressionRatio();
            assertTrue(ratio < 0.5); // 应该有显著压缩
            System.out.println("Block compression ratio: " + ratio);
        }
    }

    // ==================== CompressedPage 测试 ====================

    @Nested
    @DisplayName("CompressedPage 测试")
    class CompressedPageTests {

        @Test
        @DisplayName("初始化页面")
        void testInitPage() {
            ByteBuffer buffer = ByteBuffer.allocate(CompressedPage.PAGE_SIZE);
            CompressedPage.initPage(buffer);

            assertEquals(CompressedPage.FLAG_COMPRESSED, CompressedPage.readFlags(buffer));
            assertEquals(0, CompressedPage.readKeyCount(buffer));
            assertEquals(CompressedPage.PAGE_SIZE, CompressedPage.readDataOffset(buffer));
        }

        @Test
        @DisplayName("插入压缩记录")
        void testInsertCompressedRecord() {
            ByteBuffer buffer = ByteBuffer.allocate(CompressedPage.PAGE_SIZE);
            CompressedPage.initPage(buffer);

            byte[] key1 = "key_001".getBytes(StandardCharsets.UTF_8);
            byte[] value1 = "value1".getBytes(StandardCharsets.UTF_8);
            CompressedKey compressed1 = CompressedKey.uncompressed(key1);

            assertTrue(CompressedPage.insertCompressedRecord(buffer, 0, compressed1, value1));
            assertEquals(1, CompressedPage.readKeyCount(buffer));

            byte[] key2 = "key_002".getBytes(StandardCharsets.UTF_8);
            byte[] value2 = "value2".getBytes(StandardCharsets.UTF_8);
            CompressedKey compressed2 = PrefixCompressor.compress(key2, key1);

            assertTrue(CompressedPage.insertCompressedRecord(buffer, 1, compressed2, value2));
            assertEquals(2, CompressedPage.readKeyCount(buffer));
        }

        @Test
        @DisplayName("读取压缩记录")
        void testReadCompressedRecord() {
            ByteBuffer buffer = ByteBuffer.allocate(CompressedPage.PAGE_SIZE);
            CompressedPage.initPage(buffer);

            byte[] key = "test_key".getBytes(StandardCharsets.UTF_8);
            byte[] value = "test_value".getBytes(StandardCharsets.UTF_8);
            CompressedKey compressed = CompressedKey.uncompressed(key);

            CompressedPage.insertCompressedRecord(buffer, 0, compressed, value);

            CompressedPage.CompressedRecord record = CompressedPage.readCompressedRecord(buffer, 0);
            assertArrayEquals(value, record.getValue());
            assertEquals(0, record.getCompressedKey().getPrefixLength());
        }

        @Test
        @DisplayName("删除记录")
        void testDeleteRecord() {
            ByteBuffer buffer = ByteBuffer.allocate(CompressedPage.PAGE_SIZE);
            CompressedPage.initPage(buffer);

            // 插入3条记录
            for (int i = 0; i < 3; i++) {
                byte[] key = ("key_" + i).getBytes(StandardCharsets.UTF_8);
                byte[] value = ("value_" + i).getBytes(StandardCharsets.UTF_8);
                CompressedKey compressed = CompressedKey.uncompressed(key);
                CompressedPage.insertCompressedRecord(buffer, i, compressed, value);
            }

            assertEquals(3, CompressedPage.readKeyCount(buffer));

            // 删除中间的记录
            CompressedPage.deleteRecord(buffer, 1);
            assertEquals(2, CompressedPage.readKeyCount(buffer));
        }

        @Test
        @DisplayName("可用空间计算")
        void testFreeSpace() {
            ByteBuffer buffer = ByteBuffer.allocate(CompressedPage.PAGE_SIZE);
            CompressedPage.initPage(buffer);

            int initialFreeSpace = CompressedPage.getFreeSpace(buffer);
            assertTrue(initialFreeSpace > 0);

            // 插入记录后空间减少
            byte[] key = "test".getBytes(StandardCharsets.UTF_8);
            byte[] value = "value".getBytes(StandardCharsets.UTF_8);
            CompressedPage.insertCompressedRecord(buffer, 0, CompressedKey.uncompressed(key), value);

            int afterInsertFreeSpace = CompressedPage.getFreeSpace(buffer);
            assertTrue(afterInsertFreeSpace < initialFreeSpace);
        }
    }

    // ==================== CompressedPageScanner 测试 ====================

    @Nested
    @DisplayName("CompressedPageScanner 测试")
    class CompressedPageScannerTests {

        @Test
        @DisplayName("顺序扫描")
        void testSequentialScan() {
            ByteBuffer buffer = ByteBuffer.allocate(CompressedPage.PAGE_SIZE);
            CompressedPage.initPage(buffer);

            // 插入记录
            String[] keys = {"aaa", "aab", "aac", "aad"};
            byte[] previousKey = null;
            for (int i = 0; i < keys.length; i++) {
                byte[] key = keys[i].getBytes(StandardCharsets.UTF_8);
                byte[] value = ("value_" + i).getBytes(StandardCharsets.UTF_8);
                CompressedKey compressed = PrefixCompressor.compress(key, previousKey);
                CompressedPage.insertCompressedRecord(buffer, i, compressed, value);
                previousKey = key;
            }

            // 扫描
            try (CompressedPageScanner scanner = new CompressedPageScanner(buffer)) {
                int count = 0;
                while (scanner.hasNext()) {
                    CompressedPageScanner.DecompressedEntry entry = scanner.next();
                    assertEquals(count, entry.getIndex());
                    assertArrayEquals(keys[count].getBytes(StandardCharsets.UTF_8), entry.getKey());
                    count++;
                }
                assertEquals(keys.length, count);
            }
        }

        @Test
        @DisplayName("跳过记录")
        void testSkip() {
            ByteBuffer buffer = ByteBuffer.allocate(CompressedPage.PAGE_SIZE);
            CompressedPage.initPage(buffer);

            for (int i = 0; i < 5; i++) {
                byte[] key = ("key_" + i).getBytes(StandardCharsets.UTF_8);
                byte[] value = ("value_" + i).getBytes(StandardCharsets.UTF_8);
                CompressedPage.insertCompressedRecord(buffer, i, CompressedKey.uncompressed(key), value);
            }

            try (CompressedPageScanner scanner = new CompressedPageScanner(buffer)) {
                int skipped = scanner.skip(2);
                assertEquals(2, skipped);
                assertEquals(2, scanner.getCurrentIndex());
                assertEquals(3, scanner.remaining());
            }
        }
    }

    // ==================== CompressionStats 测试 ====================

    @Nested
    @DisplayName("CompressionStats 测试")
    class CompressionStatsTests {

        @Test
        @DisplayName("统计收集")
        void testStatsCollection() {
            CompressionStats stats = new CompressionStats();

            // 添加未压缩的键
            stats.addKey(new CompressedKey(0, new byte[10], 10));
            assertEquals(1, stats.getTotalKeys());
            assertEquals(0, stats.getCompressedKeyCount());

            // 添加压缩的键
            stats.addKey(new CompressedKey(8, new byte[2], 10));
            assertEquals(2, stats.getTotalKeys());
            assertEquals(1, stats.getCompressedKeyCount());
            assertEquals(8, stats.getMaxPrefixLength());
        }

        @Test
        @DisplayName("合并统计")
        void testMerge() {
            CompressionStats stats1 = new CompressionStats();
            stats1.addKey(new CompressedKey(5, new byte[5], 10));

            CompressionStats stats2 = new CompressionStats();
            stats2.addKey(new CompressedKey(10, new byte[2], 12));

            stats1.merge(stats2);

            assertEquals(2, stats1.getTotalKeys());
            assertEquals(10, stats1.getMaxPrefixLength());
        }

        @Test
        @DisplayName("详细报告")
        void testDetailedReport() {
            CompressionStats stats = new CompressionStats();

            for (int i = 0; i < 100; i++) {
                int prefixLen = i > 0 ? 5 : 0;
                stats.addKey(new CompressedKey(prefixLen, new byte[5], 10));
            }

            String report = stats.toDetailedReport();
            assertNotNull(report);
            assertTrue(report.contains("Total Keys"));
            assertTrue(report.contains("Compression Ratio"));
            System.out.println(report);
        }
    }

    // ==================== PrefixCompressedBTree 集成测试 ====================

    @Nested
    @DisplayName("PrefixCompressedBTree 集成测试")
    class PrefixCompressedBTreeIntegrationTests {

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
            if (bufferPool != null) bufferPool.close();
            if (diskManager != null) diskManager.close();
        }

        @Test
        @DisplayName("创建压缩 B+Tree")
        void testCreate() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PrefixCompressedBTree tree = PrefixCompressedBTree.create(
                        1L, 0, bufferPool, new IntKeyComparator(), mtr);

                assertNotNull(tree);
                assertTrue(tree.isCompressionEnabled());
                assertEquals(0.8, tree.getCompressionThreshold());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("插入和搜索")
        void testInsertAndSearch() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PrefixCompressedBTree tree = PrefixCompressedBTree.create(
                        1L, 0, bufferPool, new IntKeyComparator(), mtr);

                for (int i = 1; i <= 20; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    tree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                assertEquals(20, tree.getRecordCount());

                for (int i = 1; i <= 20; i++) {
                    assertTrue(tree.containsKey(IntKeyComparator.intToBytes(i), mtr));
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("分析压缩效果")
        void testAnalyzeCompression() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PrefixCompressedBTree tree = PrefixCompressedBTree.create(
                        1L, 0, bufferPool, new IntKeyComparator(), mtr);

                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    tree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                PrefixCompressedBTree.CompressionAnalysis analysis = tree.analyzeCompression(mtr);
                assertNotNull(analysis);
                System.out.println(analysis);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("获取压缩统计")
        void testGetStats() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PrefixCompressedBTree tree = PrefixCompressedBTree.create(
                        1L, 0, bufferPool, new IntKeyComparator(), mtr);

                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    tree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                CompressionStats stats = tree.getStats();
                assertNotNull(stats);
                System.out.println(stats.toDetailedReport());

                mtr.commit();
            }
        }
    }
}
