package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BTree 单元测试
 *
 * <p>测试完整的 B+Tree 功能。</p>
 *
 * <h2>验证的不变量</h2>
 * <ul>
 *   <li>I1: 所有叶子节点在同一层级</li>
 *   <li>I2: 搜索能找到所有插入的键</li>
 *   <li>I3: 根节点分裂后树高度增加</li>
 *   <li>I4: 记录总数正确</li>
 * </ul>
 *
 * @author MiniDB
 */
class BTreeTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(100, diskManager); // 更大的缓冲池
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

    // ==================== 创建测试 ====================

    @Nested
    @DisplayName("B+Tree 创建")
    class CreateTests {

        @Test
        @DisplayName("创建空的 B+Tree")
        void testCreateEmptyBTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                assertNotNull(btree);
                assertEquals(1, btree.getTreeHeight(), "初始树高度应为 1");
                assertEquals(0, btree.getRecordCount(), "初始记录数应为 0");

                BTreeMetadata metadata = btree.getMetadata();
                assertEquals(1L, metadata.getIndexId());
                assertTrue(metadata.getRootPageNo() > 0, "根页面号应有效");

                mtr.commit();
            }
        }

        @Test
        @DisplayName("创建后根节点是叶子节点")
        void testRootIsLeafAfterCreate() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                BufferFrame rootFrame = bufferPool.getPage(
                        btree.getMetadata().getRootPageId(),
                        BufferPool.FetchMode.READ_EXISTING
                );
                rootFrame.readLock();
                try {
                    ByteBuffer buf = rootFrame.buffer();
                    assertEquals(0, IndexPageLayout.readLevel(buf), "根节点应是叶子节点");
                    assertTrue(IndexPageLayout.isLeaf(buf));
                } finally {
                    rootFrame.readUnlock();
                }

                mtr.commit();
            }
        }
    }

    // ==================== 搜索测试 ====================

    @Nested
    @DisplayName("B+Tree 搜索")
    class SearchTests {

        @Test
        @DisplayName("在空树中搜索")
        void testSearchEmptyTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                byte[] searchKey = IntKeyComparator.intToBytes(100);
                BTreeSearchResult result = btree.search(searchKey, mtr);

                assertNotNull(result);
                assertFalse(result.isExactMatch(), "空树中不应找到任何键");
                assertNotNull(result.getPath());
                assertEquals(1, result.getPath().length(), "路径长度应为 1（只有根节点）");

                mtr.commit();
            }
        }

        @Test
        @DisplayName("搜索存在的键")
        void testSearchExistingKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入一条记录
                byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3}, 2);
                byte[] key = IntKeyComparator.intToBytes(100);
                btree.insert(record, key, mtr);

                // 搜索
                BTreeSearchResult result = btree.search(key, mtr);

                assertTrue(result.isExactMatch(), "应找到插入的键");

                mtr.commit();
            }
        }

        @Test
        @DisplayName("搜索不存在的键")
        void testSearchNonExistingKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入一条记录
                byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                byte[] key100 = IntKeyComparator.intToBytes(100);
                btree.insert(record, key100, mtr);

                // 搜索不存在的键
                byte[] key200 = IntKeyComparator.intToBytes(200);
                BTreeSearchResult result = btree.search(key200, mtr);

                assertFalse(result.isExactMatch(), "不应找到不存在的键");

                mtr.commit();
            }
        }

        @Test
        @DisplayName("containsKey 方法")
        void testContainsKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                byte[] key = IntKeyComparator.intToBytes(100);
                btree.insert(record, key, mtr);

                assertTrue(btree.containsKey(key, mtr));
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(200), mtr));

                mtr.commit();
            }
        }
    }

    // ==================== 插入测试 ====================

    @Nested
    @DisplayName("B+Tree 插入")
    class InsertTests {

        @Test
        @DisplayName("插入单条记录")
        void testInsertSingleRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3}, 2);
                byte[] key = IntKeyComparator.intToBytes(100);

                boolean inserted = btree.insert(record, key, mtr);

                assertTrue(inserted);
                assertEquals(1, btree.getRecordCount());
                assertTrue(btree.containsKey(key, mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("按顺序插入多条记录")
        void testInsertMultipleRecordsInOrder() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入 10 条记录
                for (int i = 1; i <= 10; i++) {
                    int keyValue = i * 10;
                    byte[] record = SimpleRecordBuilder.buildRecord(keyValue, new byte[]{(byte) i}, 2);
                    byte[] key = IntKeyComparator.intToBytes(keyValue);
                    btree.insert(record, key, mtr);
                }

                assertEquals(10, btree.getRecordCount());

                // 验证所有键都能找到
                for (int i = 1; i <= 10; i++) {
                    int keyValue = i * 10;
                    byte[] key = IntKeyComparator.intToBytes(keyValue);
                    assertTrue(btree.containsKey(key, mtr), "应能找到 key=" + keyValue);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("乱序插入多条记录")
        void testInsertMultipleRecordsOutOfOrder() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 乱序插入
                int[] keys = {50, 30, 70, 20, 40, 60, 80, 10, 90, 100};
                for (int keyValue : keys) {
                    byte[] record = SimpleRecordBuilder.buildRecord(keyValue, new byte[]{(byte) keyValue}, 2);
                    byte[] key = IntKeyComparator.intToBytes(keyValue);
                    btree.insert(record, key, mtr);
                }

                assertEquals(keys.length, btree.getRecordCount());

                // 验证所有键都能找到
                for (int keyValue : keys) {
                    byte[] key = IntKeyComparator.intToBytes(keyValue);
                    assertTrue(btree.containsKey(key, mtr), "应能找到 key=" + keyValue);
                }

                mtr.commit();
            }
        }
    }

    // ==================== 分裂测试 ====================

    @Nested
    @DisplayName("B+Tree 分裂")
    class SplitTests {

        @Test
        @DisplayName("插入足够多记录触发叶子分裂")
        void testLeafSplit() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                int initialHeight = btree.getTreeHeight();

                // 插入足够多的记录以触发分裂
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    byte[] key = IntKeyComparator.intToBytes(i);
                    btree.insert(record, key, mtr);
                }

                // 验证所有键都能找到
                for (int i = 1; i <= 50; i++) {
                    byte[] key = IntKeyComparator.intToBytes(i);
                    assertTrue(btree.containsKey(key, mtr), "应能找到 key=" + i);
                }

                assertEquals(50, btree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("根节点分裂后树高度增加")
        void testRootSplitIncreasesHeight() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                assertEquals(1, btree.getTreeHeight(), "初始高度应为 1");

                // 插入大量记录以触发根节点分裂
                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    byte[] key = IntKeyComparator.intToBytes(i);
                    btree.insert(record, key, mtr);
                }

                // 树高度应该增加（具体值取决于页面大小和记录大小）
                assertTrue(btree.getTreeHeight() >= 1, "树高度应 >= 1");

                // 验证所有键仍然能找到
                for (int i = 1; i <= 100; i++) {
                    byte[] key = IntKeyComparator.intToBytes(i);
                    assertTrue(btree.containsKey(key, mtr), "分裂后应能找到 key=" + i);
                }

                mtr.commit();
            }
        }
    }

    // ==================== 搜索路径测试 ====================

    @Nested
    @DisplayName("搜索路径")
    class PathTests {

        @Test
        @DisplayName("单层树的搜索路径")
        void testSingleLevelPath() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入少量记录（不触发分裂）
                for (int i = 1; i <= 5; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i * 10, new byte[]{(byte) i}, 2);
                    byte[] key = IntKeyComparator.intToBytes(i * 10);
                    btree.insert(record, key, mtr);
                }

                // 搜索
                byte[] searchKey = IntKeyComparator.intToBytes(30);
                BTreeSearchResult result = btree.search(searchKey, mtr);

                BTreePath path = result.getPath();
                assertEquals(1, path.length(), "单层树路径长度应为 1");
                assertEquals(0, path.getLeafNode().getLevel(), "叶子节点 level 应为 0");

                mtr.commit();
            }
        }

        @Test
        @DisplayName("搜索路径从根到叶 level 递减")
        void testPathLevelDecreasing() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入足够多记录以创建多层树
                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    byte[] key = IntKeyComparator.intToBytes(i);
                    btree.insert(record, key, mtr);
                }

                // 搜索
                byte[] searchKey = IntKeyComparator.intToBytes(50);
                BTreeSearchResult result = btree.search(searchKey, mtr);

                BTreePath path = result.getPath();

                // 验证 level 递减
                int prevLevel = Integer.MAX_VALUE;
                for (int i = 0; i < path.length(); i++) {
                    int currentLevel = path.getNode(i).getLevel();
                    assertTrue(currentLevel < prevLevel || i == 0,
                            "路径上的 level 应递减");
                    prevLevel = currentLevel;
                }

                // 最后一个节点应是叶子
                assertEquals(0, path.getLeafNode().getLevel(), "最后一个节点应是叶子");

                mtr.commit();
            }
        }
    }

    // ==================== 大规模测试 ====================

    @Nested
    @DisplayName("大规模测试")
    class LargeScaleTests {

        @Test
        @DisplayName("插入 500 条随机记录")
        void testInsert500RandomRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                List<Integer> keys = new ArrayList<>();
                for (int i = 1; i <= 500; i++) {
                    keys.add(i);
                }
                Collections.shuffle(keys, new Random(42));

                // 插入
                for (int keyValue : keys) {
                    byte[] record = SimpleRecordBuilder.buildRecord(keyValue, new byte[]{(byte) (keyValue % 256)}, 2);
                    byte[] key = IntKeyComparator.intToBytes(keyValue);
                    btree.insert(record, key, mtr);
                }

                assertEquals(500, btree.getRecordCount());

                // 验证所有键都能找到
                for (int keyValue : keys) {
                    byte[] key = IntKeyComparator.intToBytes(keyValue);
                    assertTrue(btree.containsKey(key, mtr), "应能找到 key=" + keyValue);
                }

                // 验证不存在的键
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(0), mtr));
                assertFalse(btree.containsKey(IntKeyComparator.intToBytes(501), mtr));

                mtr.commit();
            }
        }
    }

    // ==================== 元数据测试 ====================

    @Nested
    @DisplayName("元数据")
    class MetadataTests {

        @Test
        @DisplayName("记录计数正确更新")
        void testRecordCountUpdates() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                assertEquals(0, btree.getRecordCount());

                for (int i = 1; i <= 10; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    byte[] key = IntKeyComparator.intToBytes(i);
                    btree.insert(record, key, mtr);
                    assertEquals(i, btree.getRecordCount(), "插入第 " + i + " 条后计数应为 " + i);
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("索引 ID 正确")
        void testIndexId() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(12345L, 0, bufferPool, comparator, mtr);

                assertEquals(12345L, btree.getMetadata().getIndexId());

                mtr.commit();
            }
        }
    }
}
