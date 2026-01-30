package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.IndexPageOps;
import cn.zhangyis.minidb.storage.page.Page;
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
 * BTreePageOperations 综合测试
 *
 * <p>测试 B+Tree 单页操作的完整功能和不变量。</p>
 *
 * @author MiniDB
 */
class BTreePageOperationsTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(10, diskManager);
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

    // ==================== 综合操作测试 ====================

    @Nested
    @DisplayName("综合操作")
    class IntegratedOperationsTests {

        @Test
        @DisplayName("插入-搜索-删除完整流程")
        void testInsertSearchDeleteFlow() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                    ByteBuffer buf = frame.buffer();

                    // 1. 插入多条记录
                    int[] keys = {50, 30, 70, 20, 40, 60, 80};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        int offset = BTreePageOperations.insert(frame, record, searchKey, comparator, mtr);
                        assertTrue(offset > 0, "插入 key=" + key + " 应成功");
                    }

                    // 2. 验证所有键都能搜索到
                    for (int key : keys) {
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        assertTrue(BTreePageOperations.containsKey(buf, searchKey, comparator),
                                "应能找到 key=" + key);
                    }

                    // 3. 验证记录有序
                    List<Integer> records = BTreePageOperations.getAllUserRecords(buf);
                    assertEquals(keys.length, records.size());

                    int prevKey = Integer.MIN_VALUE;
                    for (int offset : records) {
                        int key = SimpleRecordBuilder.readKey(buf, offset);
                        assertTrue(key > prevKey, "记录应有序");
                        prevKey = key;
                    }

                    // 4. 删除部分记录
                    int recordSize = SimpleRecordBuilder.calculateRecordSize(1);
                    BTreePageOperations.delete(frame, IntKeyComparator.intToBytes(30), recordSize, comparator, mtr);
                    BTreePageOperations.delete(frame, IntKeyComparator.intToBytes(60), recordSize, comparator, mtr);

                    // 5. 验证删除后状态
                    assertFalse(BTreePageOperations.containsKey(buf, IntKeyComparator.intToBytes(30), comparator));
                    assertFalse(BTreePageOperations.containsKey(buf, IntKeyComparator.intToBytes(60), comparator));
                    assertTrue(BTreePageOperations.containsKey(buf, IntKeyComparator.intToBytes(50), comparator));

                    assertEquals(5, BTreePageOperations.getRecordCount(buf));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("随机插入删除压力测试")
        void testRandomInsertDelete() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                    ByteBuffer buf = frame.buffer();

                    Random random = new Random(42); // 固定种子保证可重复
                    List<Integer> insertedKeys = new ArrayList<>();
                    int recordSize = SimpleRecordBuilder.calculateRecordSize(1);

                    // 执行 100 次随机操作
                    for (int i = 0; i < 100; i++) {
                        if (insertedKeys.isEmpty() || random.nextBoolean()) {
                            // 插入
                            int key = random.nextInt(10000);
                            if (!insertedKeys.contains(key)) {
                                byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                                byte[] searchKey = IntKeyComparator.intToBytes(key);
                                int offset = BTreePageOperations.insert(frame, record, searchKey, comparator, mtr);
                                if (offset > 0) {
                                    insertedKeys.add(key);
                                }
                            }
                        } else {
                            // 删除
                            int idx = random.nextInt(insertedKeys.size());
                            int key = insertedKeys.get(idx);
                            byte[] searchKey = IntKeyComparator.intToBytes(key);
                            boolean deleted = BTreePageOperations.delete(frame, searchKey, recordSize, comparator, mtr);
                            if (deleted) {
                                insertedKeys.remove(idx);
                            }
                        }

                        // 每次操作后验证不变量
                        assertEquals(insertedKeys.size(), BTreePageOperations.getRecordCount(buf),
                                "记录数应匹配");
                    }

                    // 最终验证
                    for (int key : insertedKeys) {
                        assertTrue(BTreePageOperations.containsKey(buf, IntKeyComparator.intToBytes(key), comparator),
                                "key=" + key + " 应存在");
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 不变量验证测试 ====================

    @Nested
    @DisplayName("不变量验证")
    class InvariantTests {

        @Test
        @DisplayName("verifyInvariants 通过正常页面")
        void testVerifyInvariantsPass() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入一些记录
                    int[] keys = {100, 200, 300, 400, 500};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        BTreePageOperations.insert(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 验证不变量应通过
                    assertDoesNotThrow(() -> BTreePageOperations.verifyInvariants(buf, comparator));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("空页面不变量验证")
        void testVerifyInvariantsEmptyPage() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                    ByteBuffer buf = frame.buffer();

                    // 空页面也应通过验证
                    assertDoesNotThrow(() -> BTreePageOperations.verifyInvariants(buf, comparator));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 遍历操作测试 ====================

    @Nested
    @DisplayName("遍历操作")
    class TraversalTests {

        @Test
        @DisplayName("getAllUserRecords 返回正确顺序")
        void testGetAllUserRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 乱序插入
                    int[] insertOrder = {300, 100, 500, 200, 400};
                    for (int key : insertOrder) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        BTreePageOperations.insert(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();
                    List<Integer> records = BTreePageOperations.getAllUserRecords(buf);

                    // 验证返回有序
                    assertEquals(5, records.size());
                    int[] expectedOrder = {100, 200, 300, 400, 500};
                    for (int i = 0; i < records.size(); i++) {
                        int key = SimpleRecordBuilder.readKey(buf, records.get(i));
                        assertEquals(expectedOrder[i], key);
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("手动遍历记录链")
        void testManualTraversal() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    int[] keys = {10, 20, 30};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        BTreePageOperations.insert(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 手动遍历
                    int current = BTreePageOperations.getFirstUserRecord(buf);
                    int count = 0;

                    while (!BTreePageOperations.isSupremum(current) && current != 0) {
                        int key = SimpleRecordBuilder.readKey(buf, current);
                        assertEquals(keys[count], key);
                        count++;
                        current = BTreePageOperations.getNextRecord(buf, current);
                    }

                    assertEquals(3, count);

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 页面信息测试 ====================

    @Nested
    @DisplayName("页面信息")
    class PageInfoTests {

        @Test
        @DisplayName("getFreeSpace 随插入减少")
        void testFreeSpaceDecreases() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                    ByteBuffer buf = frame.buffer();

                    int initialFreeSpace = BTreePageOperations.getFreeSpace(buf);

                    // 插入记录
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3, 4}, 2);
                    byte[] searchKey = IntKeyComparator.intToBytes(100);
                    BTreePageOperations.insert(frame, record, searchKey, comparator, mtr);

                    int afterInsertFreeSpace = BTreePageOperations.getFreeSpace(buf);

                    assertTrue(afterInsertFreeSpace < initialFreeSpace,
                            "插入后空闲空间应减少");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("isLeaf 和 getLevel")
        void testLevelInfo() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                // 创建叶子节点
                Page leafPage = mtr.newPage(0);
                BufferFrame leafFrame = bufferPool.getPage(leafPage.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                leafFrame.writeLock();

                try {
                    IndexPageOps.initPage(leafFrame, 1L, 0, mtr); // level=0
                    ByteBuffer leafBuf = leafFrame.buffer();

                    assertTrue(BTreePageOperations.isLeaf(leafBuf));
                    assertEquals(0, BTreePageOperations.getLevel(leafBuf));
                } finally {
                    leafFrame.writeUnlock();
                }

                // 创建非叶子节点
                Page internalPage = mtr.newPage(0);
                BufferFrame internalFrame = bufferPool.getPage(internalPage.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                internalFrame.writeLock();

                try {
                    IndexPageOps.initPage(internalFrame, 1L, 2, mtr); // level=2
                    ByteBuffer internalBuf = internalFrame.buffer();

                    assertFalse(BTreePageOperations.isLeaf(internalBuf));
                    assertEquals(2, BTreePageOperations.getLevel(internalBuf));
                } finally {
                    internalFrame.writeUnlock();
                }

                mtr.commit();
            }
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    @DisplayName("边界条件")
    class BoundaryTests {

        @Test
        @DisplayName("插入重复键")
        void testInsertDuplicateKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    byte[] record1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] searchKey = IntKeyComparator.intToBytes(100);

                    // 第一次插入
                    int offset1 = BTreePageOperations.insert(frame, record1, searchKey, comparator, mtr);
                    assertTrue(offset1 > 0);

                    // 第二次插入相同键（当前实现允许重复键）
                    byte[] record2 = SimpleRecordBuilder.buildRecord(100, new byte[]{2}, 3);
                    int offset2 = BTreePageOperations.insert(frame, record2, searchKey, comparator, mtr);

                    // 注意：当前实现允许重复键，两次插入都应成功
                    // 如果需要唯一键约束，应在上层实现
                    assertTrue(offset2 > 0);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(2, BTreePageOperations.getRecordCount(buf));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("最小和最大整数键")
        void testMinMaxIntKeys() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入最小和最大整数
                    int[] keys = {Integer.MIN_VALUE, 0, Integer.MAX_VALUE};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{1}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        int offset = BTreePageOperations.insert(frame, record, searchKey, comparator, mtr);
                        assertTrue(offset > 0, "插入 key=" + key + " 应成功");
                    }

                    ByteBuffer buf = frame.buffer();

                    // 验证都能找到
                    for (int key : keys) {
                        assertTrue(BTreePageOperations.containsKey(buf, IntKeyComparator.intToBytes(key), comparator),
                                "应能找到 key=" + key);
                    }

                    // 验证顺序正确
                    List<Integer> records = BTreePageOperations.getAllUserRecords(buf);
                    assertEquals(Integer.MIN_VALUE, SimpleRecordBuilder.readKey(buf, records.get(0)));
                    assertEquals(0, SimpleRecordBuilder.readKey(buf, records.get(1)));
                    assertEquals(Integer.MAX_VALUE, SimpleRecordBuilder.readKey(buf, records.get(2)));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }
}
