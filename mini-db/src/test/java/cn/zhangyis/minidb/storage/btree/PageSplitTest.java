package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.IndexPageOps;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageSplit 单元测试
 *
 * <p>测试 B+Tree 页分裂操作。</p>
 *
 * <h2>验证的不变量</h2>
 * <ul>
 *   <li>I1: 分裂后原页面所有键 < 分裂键</li>
 *   <li>I2: 分裂后新页面所有键 >= 分裂键</li>
 *   <li>I3: 两个页面的记录链都完整</li>
 *   <li>I4: 记录总数不变</li>
 * </ul>
 *
 * @author MiniDB
 */
class PageSplitTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(20, diskManager);
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

    // ==================== 基本分裂测试 ====================

    @Nested
    @DisplayName("基本分裂")
    class BasicSplitTests {

        @Test
        @DisplayName("分裂包含偶数条记录的页面")
        void testSplitEvenRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 10 条记录: 10, 20, 30, ..., 100
                    for (int i = 1; i <= 10; i++) {
                        int key = i * 10;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();
                    int originalCount = IndexPageLayout.readRecordCount(buf);
                    assertEquals(10, originalCount);

                    // 执行分裂
                    SplitResult result = PageSplit.split(frame, bufferPool, comparator, mtr);

                    assertNotNull(result);
                    assertNotNull(result.getSplitKey());
                    assertNotNull(result.getNewPageId());

                    // 验证分裂键
                    int splitKeyValue = IntKeyComparator.bytesToInt(result.getSplitKey());
                    assertEquals(60, splitKeyValue, "分裂键应为第 6 条记录的键");

                    // 验证原页面
                    int oldPageCount = IndexPageLayout.readRecordCount(buf);
                    assertEquals(5, oldPageCount, "原页面应保留 5 条记录");

                    // 验证新页面
                    BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
                    newFrame.readLock();
                    try {
                        ByteBuffer newBuf = newFrame.buffer();
                        int newPageCount = IndexPageLayout.readRecordCount(newBuf);
                        assertEquals(5, newPageCount, "新页面应有 5 条记录");
                    } finally {
                        newFrame.readUnlock();
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("分裂包含奇数条记录的页面")
        void testSplitOddRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 7 条记录
                    for (int i = 1; i <= 7; i++) {
                        int key = i * 10;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    // 执行分裂
                    SplitResult result = PageSplit.split(frame, bufferPool, comparator, mtr);

                    ByteBuffer buf = frame.buffer();
                    int oldPageCount = IndexPageLayout.readRecordCount(buf);

                    BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
                    newFrame.readLock();
                    try {
                        ByteBuffer newBuf = newFrame.buffer();
                        int newPageCount = IndexPageLayout.readRecordCount(newBuf);

                        // 总数应为 7
                        assertEquals(7, oldPageCount + newPageCount, "记录总数应保持不变");
                        // 分裂应大致平衡
                        assertTrue(Math.abs(oldPageCount - newPageCount) <= 1, "分裂应大致平衡");
                    } finally {
                        newFrame.readUnlock();
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 分裂键验证测试 ====================

    @Nested
    @DisplayName("分裂键验证")
    class SplitKeyTests {

        @Test
        @DisplayName("原页面所有键 < 分裂键")
        void testOriginalPageKeysLessThanSplitKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入记录
                    for (int i = 1; i <= 8; i++) {
                        int key = i * 10;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    // 执行分裂
                    SplitResult result = PageSplit.split(frame, bufferPool, comparator, mtr);
                    int splitKeyValue = IntKeyComparator.bytesToInt(result.getSplitKey());

                    // 验证原页面所有键 < 分裂键
                    ByteBuffer buf = frame.buffer();
                    List<Integer> oldPageRecords = BTreePageOperations.getAllUserRecords(buf);

                    for (int offset : oldPageRecords) {
                        int key = SimpleRecordBuilder.readKey(buf, offset);
                        assertTrue(key < splitKeyValue,
                                "原页面键 " + key + " 应 < 分裂键 " + splitKeyValue);
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("新页面所有键 >= 分裂键")
        void testNewPageKeysGreaterOrEqualSplitKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入记录
                    for (int i = 1; i <= 8; i++) {
                        int key = i * 10;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    // 执行分裂
                    SplitResult result = PageSplit.split(frame, bufferPool, comparator, mtr);
                    int splitKeyValue = IntKeyComparator.bytesToInt(result.getSplitKey());

                    // 验证新页面所有键 >= 分裂键
                    BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
                    newFrame.readLock();
                    try {
                        ByteBuffer newBuf = newFrame.buffer();
                        List<Integer> newPageRecords = BTreePageOperations.getAllUserRecords(newBuf);

                        for (int offset : newPageRecords) {
                            int key = SimpleRecordBuilder.readKey(newBuf, offset);
                            assertTrue(key >= splitKeyValue,
                                    "新页面键 " + key + " 应 >= 分裂键 " + splitKeyValue);
                        }
                    } finally {
                        newFrame.readUnlock();
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 链表完整性测试 ====================

    @Nested
    @DisplayName("链表完整性")
    class ChainIntegrityTests {

        @Test
        @DisplayName("分裂后两个页面链表都完整")
        void testBothPagesChainIntegrity() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入记录
                    for (int i = 1; i <= 10; i++) {
                        int key = i * 10;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    // 执行分裂
                    SplitResult result = PageSplit.split(frame, bufferPool, comparator, mtr);

                    // 验证原页面链表完整性
                    ByteBuffer buf = frame.buffer();
                    verifyChainIntegrity(buf, "原页面");

                    // 验证新页面链表完整性
                    BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
                    newFrame.readLock();
                    try {
                        ByteBuffer newBuf = newFrame.buffer();
                        verifyChainIntegrity(newBuf, "新页面");
                    } finally {
                        newFrame.readUnlock();
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        private void verifyChainIntegrity(ByteBuffer buf, String pageName) {
            int current = IndexPageLayout.INFIMUM_OFFSET;
            int steps = 0;
            int maxSteps = 1000;

            while (current != IndexPageLayout.SUPREMUM_OFFSET && steps < maxSteps) {
                int next = IndexPageLayout.readRecordNext(buf, current);
                assertTrue(next != 0 || current == IndexPageLayout.SUPREMUM_OFFSET,
                        pageName + " 链表不应在 Supremum 之前断裂");
                current = next;
                steps++;
            }

            assertEquals(IndexPageLayout.SUPREMUM_OFFSET, current,
                    pageName + " 链表应到达 Supremum");
            assertTrue(steps < maxSteps, pageName + " 链表不应有环");
        }
    }

    // ==================== splitAndInsert 测试 ====================

    @Nested
    @DisplayName("分裂并插入")
    class SplitAndInsertTests {

        @Test
        @DisplayName("分裂后插入到原页面")
        void testSplitAndInsertToOriginal() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入记录 20, 40, 60, 80, 100
                    for (int i = 1; i <= 5; i++) {
                        int key = i * 20;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    // 分裂并插入 key=30（应插入原页面，因为 30 < 分裂键）
                    byte[] newRecord = SimpleRecordBuilder.buildRecord(30, new byte[]{30}, 2);
                    byte[] newKey = IntKeyComparator.intToBytes(30);

                    SplitResult result = PageSplit.splitAndInsert(frame, newRecord, newKey,
                            bufferPool, comparator, mtr);

                    int splitKeyValue = IntKeyComparator.bytesToInt(result.getSplitKey());

                    // 如果 30 < splitKey，应在原页面找到
                    if (30 < splitKeyValue) {
                        ByteBuffer buf = frame.buffer();
                        assertTrue(PageSearch.containsKey(buf, newKey, comparator),
                                "key=30 应在原页面");
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("分裂后插入到新页面")
        void testSplitAndInsertToNew() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入记录 20, 40, 60, 80, 100
                    for (int i = 1; i <= 5; i++) {
                        int key = i * 20;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    // 分裂并插入 key=90（应插入新页面，因为 90 >= 分裂键）
                    byte[] newRecord = SimpleRecordBuilder.buildRecord(90, new byte[]{90}, 2);
                    byte[] newKey = IntKeyComparator.intToBytes(90);

                    SplitResult result = PageSplit.splitAndInsert(frame, newRecord, newKey,
                            bufferPool, comparator, mtr);

                    int splitKeyValue = IntKeyComparator.bytesToInt(result.getSplitKey());

                    // 如果 90 >= splitKey，应在新页面找到
                    if (90 >= splitKeyValue) {
                        BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
                        newFrame.readLock();
                        try {
                            ByteBuffer newBuf = newFrame.buffer();
                            assertTrue(PageSearch.containsKey(newBuf, newKey, comparator),
                                    "key=90 应在新页面");
                        } finally {
                            newFrame.readUnlock();
                        }
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 辅助方法测试 ====================

    @Nested
    @DisplayName("辅助方法")
    class HelperMethodTests {

        @Test
        @DisplayName("needsSplit 正确判断")
        void testNeedsSplit() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                    ByteBuffer buf = frame.buffer();

                    // 小记录不需要分裂
                    assertFalse(PageSplit.needsSplit(buf, 100));

                    // 超大记录需要分裂
                    int freeSpace = IndexPageLayout.freeSpace(buf);
                    assertTrue(PageSplit.needsSplit(buf, freeSpace + 100));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("suggestSplitPoint 返回中间位置")
        void testSuggestSplitPoint() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 10 条记录
                    for (int i = 1; i <= 10; i++) {
                        int key = i * 10;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();
                    int splitPoint = PageSplit.suggestSplitPoint(buf);

                    assertEquals(5, splitPoint, "10 条记录的分裂点应为 5");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    @DisplayName("边界条件")
    class BoundaryTests {

        @Test
        @DisplayName("分裂只有 2 条记录的页面")
        void testSplitMinimumRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 只插入 2 条记录
                    byte[] rec1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] rec2 = SimpleRecordBuilder.buildRecord(200, new byte[]{2}, 3);

                    PageInsert.insertRecord(frame, rec1, IntKeyComparator.intToBytes(100), comparator, mtr);
                    PageInsert.insertRecord(frame, rec2, IntKeyComparator.intToBytes(200), comparator, mtr);

                    // 执行分裂
                    SplitResult result = PageSplit.split(frame, bufferPool, comparator, mtr);

                    ByteBuffer buf = frame.buffer();
                    int oldCount = IndexPageLayout.readRecordCount(buf);

                    BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
                    newFrame.readLock();
                    try {
                        ByteBuffer newBuf = newFrame.buffer();
                        int newCount = IndexPageLayout.readRecordCount(newBuf);

                        // 总数应为 2
                        assertEquals(2, oldCount + newCount);
                        // 每个页面至少 1 条
                        assertTrue(oldCount >= 1);
                        assertTrue(newCount >= 1);
                    } finally {
                        newFrame.readUnlock();
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("分裂只有 1 条记录的页面应抛异常")
        void testSplitSingleRecordThrows() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 只插入 1 条记录
                    byte[] rec = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    PageInsert.insertRecord(frame, rec, IntKeyComparator.intToBytes(100), comparator, mtr);

                    // 分裂应抛异常
                    assertThrows(IllegalStateException.class, () -> {
                        PageSplit.split(frame, bufferPool, comparator, mtr);
                    }, "分裂只有 1 条记录的页面应抛异常");

                    mtr.rollback();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }
}
