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

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageSearch 单元测试
 *
 * <p>测试 B+Tree 单页内的搜索算法。</p>
 *
 * <h2>验证的不变量</h2>
 * <ul>
 *   <li>I1: 二分查找正确定位到目标 slot</li>
 *   <li>I2: 线性扫描找到精确位置或插入点</li>
 *   <li>I3: 搜索结果的 exactMatch 标志正确</li>
 * </ul>
 *
 * @author MiniDB
 */
class PageSearchTest {

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

    // ==================== 空页面搜索测试 ====================

    @Nested
    @DisplayName("空页面搜索")
    class EmptyPageSearchTests {

        @Test
        @DisplayName("在空页面中搜索 - 应返回 Infimum 作为插入点")
        void testSearchEmptyPage() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索任意键
                    byte[] searchKey = IntKeyComparator.intToBytes(100);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    // 空页面中，应返回 Infimum 作为插入点
                    assertFalse(result.isExactMatch(), "空页面不应有精确匹配");
                    assertEquals(IndexPageLayout.INFIMUM_OFFSET, result.getRecordOffset(),
                            "应返回 Infimum 作为插入点");
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 单记录搜索测试 ====================

    @Nested
    @DisplayName("单记录搜索")
    class SingleRecordSearchTests {

        @Test
        @DisplayName("搜索存在的键 - 精确匹配")
        void testSearchExistingKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入一条记录 key=100
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3, 4}, 2);
                    int recOffset = IndexPageOps.insertRecord(frame, record,
                            IndexPageLayout.INFIMUM_OFFSET, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索 key=100
                    byte[] searchKey = IntKeyComparator.intToBytes(100);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    assertTrue(result.isExactMatch(), "应找到精确匹配");
                    assertEquals(recOffset, result.getRecordOffset(), "应返回正确的记录偏移");
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("搜索不存在的键（小于现有键）")
        void testSearchSmallerKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 key=100
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3, 4}, 2);
                    IndexPageOps.insertRecord(frame, record, IndexPageLayout.INFIMUM_OFFSET, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索 key=50（小于 100）
                    byte[] searchKey = IntKeyComparator.intToBytes(50);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    assertFalse(result.isExactMatch(), "不应有精确匹配");
                    assertEquals(IndexPageLayout.INFIMUM_OFFSET, result.getRecordOffset(),
                            "应返回 Infimum 作为插入点（在第一条记录之前插入）");
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("搜索不存在的键（大于现有键）")
        void testSearchLargerKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 key=100
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3, 4}, 2);
                    int recOffset = IndexPageOps.insertRecord(frame, record,
                            IndexPageLayout.INFIMUM_OFFSET, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索 key=200（大于 100）
                    byte[] searchKey = IntKeyComparator.intToBytes(200);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    assertFalse(result.isExactMatch(), "不应有精确匹配");
                    assertEquals(recOffset, result.getRecordOffset(),
                            "应返回现有记录作为插入点（在其之后插入）");
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 多记录搜索测试 ====================

    @Nested
    @DisplayName("多记录搜索")
    class MultipleRecordSearchTests {

        @Test
        @DisplayName("搜索多条记录中的第一条")
        void testSearchFirstRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录: 100, 200, 300
                    byte[] rec1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] rec2 = SimpleRecordBuilder.buildRecord(200, new byte[]{2}, 3);
                    byte[] rec3 = SimpleRecordBuilder.buildRecord(300, new byte[]{3}, 4);

                    int off1 = IndexPageOps.insertRecord(frame, rec1, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    int off2 = IndexPageOps.insertRecord(frame, rec2, off1, mtr);
                    int off3 = IndexPageOps.insertRecord(frame, rec3, off2, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索 key=100
                    byte[] searchKey = IntKeyComparator.intToBytes(100);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    assertTrue(result.isExactMatch(), "应找到精确匹配");
                    assertEquals(off1, result.getRecordOffset());
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("搜索多条记录中的中间记录")
        void testSearchMiddleRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录: 100, 200, 300
                    byte[] rec1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] rec2 = SimpleRecordBuilder.buildRecord(200, new byte[]{2}, 3);
                    byte[] rec3 = SimpleRecordBuilder.buildRecord(300, new byte[]{3}, 4);

                    int off1 = IndexPageOps.insertRecord(frame, rec1, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    int off2 = IndexPageOps.insertRecord(frame, rec2, off1, mtr);
                    int off3 = IndexPageOps.insertRecord(frame, rec3, off2, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索 key=200
                    byte[] searchKey = IntKeyComparator.intToBytes(200);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    assertTrue(result.isExactMatch(), "应找到精确匹配");
                    assertEquals(off2, result.getRecordOffset());
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("搜索多条记录中的最后一条")
        void testSearchLastRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录: 100, 200, 300
                    byte[] rec1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] rec2 = SimpleRecordBuilder.buildRecord(200, new byte[]{2}, 3);
                    byte[] rec3 = SimpleRecordBuilder.buildRecord(300, new byte[]{3}, 4);

                    int off1 = IndexPageOps.insertRecord(frame, rec1, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    int off2 = IndexPageOps.insertRecord(frame, rec2, off1, mtr);
                    int off3 = IndexPageOps.insertRecord(frame, rec3, off2, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索 key=300
                    byte[] searchKey = IntKeyComparator.intToBytes(300);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    assertTrue(result.isExactMatch(), "应找到精确匹配");
                    assertEquals(off3, result.getRecordOffset());
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("搜索间隙中的键")
        void testSearchGapKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录: 100, 200, 300
                    byte[] rec1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] rec2 = SimpleRecordBuilder.buildRecord(200, new byte[]{2}, 3);
                    byte[] rec3 = SimpleRecordBuilder.buildRecord(300, new byte[]{3}, 4);

                    int off1 = IndexPageOps.insertRecord(frame, rec1, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    int off2 = IndexPageOps.insertRecord(frame, rec2, off1, mtr);
                    int off3 = IndexPageOps.insertRecord(frame, rec3, off2, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 搜索 key=150（在 100 和 200 之间）
                    byte[] searchKey = IntKeyComparator.intToBytes(150);
                    PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                    assertFalse(result.isExactMatch(), "不应有精确匹配");
                    assertEquals(off1, result.getRecordOffset(),
                            "应返回 key=100 的记录作为插入点");
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 大量记录搜索测试 ====================

    @Nested
    @DisplayName("大量记录搜索")
    class ManyRecordSearchTests {

        @Test
        @DisplayName("插入并搜索 100 条记录")
        void testSearchManyRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 100 条记录 (key = 10, 20, 30, ..., 1000)
                    List<Integer> offsets = new ArrayList<>();
                    int prevOffset = IndexPageLayout.INFIMUM_OFFSET;

                    for (int i = 1; i <= 100; i++) {
                        int key = i * 10;
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) i}, i + 1);
                        int offset = IndexPageOps.insertRecord(frame, record, prevOffset, mtr);
                        offsets.add(offset);
                        prevOffset = offset;
                    }

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 验证所有键都能找到
                    for (int i = 0; i < 100; i++) {
                        int key = (i + 1) * 10;
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageSearchResult result = PageSearch.search(buf, searchKey, comparator);

                        assertTrue(result.isExactMatch(),
                                "应找到 key=" + key + " 的精确匹配");
                        assertEquals(offsets.get(i), result.getRecordOffset(),
                                "key=" + key + " 的偏移应正确");
                    }

                    // 验证不存在的键
                    byte[] notExist = IntKeyComparator.intToBytes(15); // 在 10 和 20 之间
                    PageSearchResult result = PageSearch.search(buf, notExist, comparator);
                    assertFalse(result.isExactMatch(), "key=15 不应存在");
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
        @DisplayName("findRecord - 找到返回偏移，未找到返回 -1")
        void testFindRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2}, 2);
                    int recOffset = IndexPageOps.insertRecord(frame, record,
                            IndexPageLayout.INFIMUM_OFFSET, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // 找到的情况
                    int found = PageSearch.findRecord(buf, IntKeyComparator.intToBytes(100), comparator);
                    assertEquals(recOffset, found);

                    // 未找到的情况
                    int notFound = PageSearch.findRecord(buf, IntKeyComparator.intToBytes(200), comparator);
                    assertEquals(-1, notFound);
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("containsKey - 检查键是否存在")
        void testContainsKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2}, 2);
                    IndexPageOps.insertRecord(frame, record, IndexPageLayout.INFIMUM_OFFSET, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    assertTrue(PageSearch.containsKey(buf, IntKeyComparator.intToBytes(100), comparator));
                    assertFalse(PageSearch.containsKey(buf, IntKeyComparator.intToBytes(200), comparator));
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("findInsertPosition - 返回插入位置")
        void testFindInsertPosition() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 key=100, 300
                    byte[] rec1 = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] rec2 = SimpleRecordBuilder.buildRecord(300, new byte[]{3}, 3);

                    int off1 = IndexPageOps.insertRecord(frame, rec1, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    int off2 = IndexPageOps.insertRecord(frame, rec2, off1, mtr);

                    mtr.commit();

                    ByteBuffer buf = frame.buffer();

                    // key=50 应插入在 Infimum 之后
                    int pos1 = PageSearch.findInsertPosition(buf, IntKeyComparator.intToBytes(50), comparator);
                    assertEquals(IndexPageLayout.INFIMUM_OFFSET, pos1);

                    // key=200 应插入在 key=100 之后
                    int pos2 = PageSearch.findInsertPosition(buf, IntKeyComparator.intToBytes(200), comparator);
                    assertEquals(off1, pos2);

                    // key=400 应插入在 key=300 之后
                    int pos3 = PageSearch.findInsertPosition(buf, IntKeyComparator.intToBytes(400), comparator);
                    assertEquals(off2, pos3);
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }
}
