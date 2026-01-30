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

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageDelete 单元测试
 *
 * <p>测试 B+Tree 单页内的删除操作。</p>
 *
 * <h2>验证的不变量</h2>
 * <ul>
 *   <li>I1: 记录链完整性 - 删除后链表仍然完整</li>
 *   <li>I2: PAGE_N_RECS 准确性 - 删除后计数正确</li>
 *   <li>I3: 空闲链表正确维护</li>
 *   <li>I4: 垃圾空间计数正确</li>
 * </ul>
 *
 * @author MiniDB
 */
class PageDeleteTest {

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

    // ==================== 基本删除测试 ====================

    @Nested
    @DisplayName("基本删除")
    class BasicDeleteTests {

        @Test
        @DisplayName("删除唯一的记录")
        void testDeleteOnlyRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入一条记录
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3, 4}, 2);
                    byte[] searchKey = IntKeyComparator.intToBytes(100);
                    PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(1, IndexPageLayout.readRecordCount(buf));

                    // 删除记录
                    boolean deleted = PageDelete.deleteRecord(frame, searchKey, record.length, comparator, mtr);

                    assertTrue(deleted, "删除应成功");
                    assertEquals(0, IndexPageLayout.readRecordCount(buf), "记录数应为 0");

                    // 验证键不再存在
                    assertFalse(PageSearch.containsKey(buf, searchKey, comparator));

                    // 验证链表: Infimum -> Supremum
                    int infNext = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, infNext);

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("删除不存在的键")
        void testDeleteNonExistentKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 key=100
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] key100 = IntKeyComparator.intToBytes(100);
                    PageInsert.insertRecord(frame, record, key100, comparator, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 尝试删除 key=200（不存在）
                    byte[] key200 = IntKeyComparator.intToBytes(200);
                    boolean deleted = PageDelete.deleteRecord(frame, key200, record.length, comparator, mtr);

                    assertFalse(deleted, "删除不存在的键应返回 false");
                    assertEquals(1, IndexPageLayout.readRecordCount(buf), "记录数应保持不变");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 多记录删除测试 ====================

    @Nested
    @DisplayName("多记录删除")
    class MultipleRecordDeleteTests {

        @Test
        @DisplayName("删除第一条记录")
        void testDeleteFirstRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录: 100, 200, 300
                    int[] keys = {100, 200, 300};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();
                    assertEquals(3, IndexPageLayout.readRecordCount(buf));

                    // 删除第一条 key=100
                    byte[] key100 = IntKeyComparator.intToBytes(100);
                    int recordSize = SimpleRecordBuilder.calculateRecordSize(1);
                    boolean deleted = PageDelete.deleteRecord(frame, key100, recordSize, comparator, mtr);

                    assertTrue(deleted);
                    assertEquals(2, IndexPageLayout.readRecordCount(buf));

                    // 验证 key=100 不存在，其他存在
                    assertFalse(PageSearch.containsKey(buf, key100, comparator));
                    assertTrue(PageSearch.containsKey(buf, IntKeyComparator.intToBytes(200), comparator));
                    assertTrue(PageSearch.containsKey(buf, IntKeyComparator.intToBytes(300), comparator));

                    // 验证链表顺序: Infimum -> 200 -> 300 -> Supremum
                    int first = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(200, SimpleRecordBuilder.readKey(buf, first));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("删除中间记录")
        void testDeleteMiddleRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录: 100, 200, 300
                    int[] keys = {100, 200, 300};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 删除中间 key=200
                    byte[] key200 = IntKeyComparator.intToBytes(200);
                    int recordSize = SimpleRecordBuilder.calculateRecordSize(1);
                    boolean deleted = PageDelete.deleteRecord(frame, key200, recordSize, comparator, mtr);

                    assertTrue(deleted);
                    assertEquals(2, IndexPageLayout.readRecordCount(buf));

                    // 验证链表顺序: Infimum -> 100 -> 300 -> Supremum
                    int first = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(100, SimpleRecordBuilder.readKey(buf, first));

                    int second = IndexPageLayout.readRecordNext(buf, first);
                    assertEquals(300, SimpleRecordBuilder.readKey(buf, second));

                    int third = IndexPageLayout.readRecordNext(buf, second);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, third);

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("删除最后一条记录")
        void testDeleteLastRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录: 100, 200, 300
                    int[] keys = {100, 200, 300};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 删除最后一条 key=300
                    byte[] key300 = IntKeyComparator.intToBytes(300);
                    int recordSize = SimpleRecordBuilder.calculateRecordSize(1);
                    boolean deleted = PageDelete.deleteRecord(frame, key300, recordSize, comparator, mtr);

                    assertTrue(deleted);
                    assertEquals(2, IndexPageLayout.readRecordCount(buf));

                    // 验证链表顺序: Infimum -> 100 -> 200 -> Supremum
                    int first = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(100, SimpleRecordBuilder.readKey(buf, first));

                    int second = IndexPageLayout.readRecordNext(buf, first);
                    assertEquals(200, SimpleRecordBuilder.readKey(buf, second));

                    int third = IndexPageLayout.readRecordNext(buf, second);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, third);

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("删除所有记录")
        void testDeleteAllRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录
                    int[] keys = {100, 200, 300};
                    int recordSize = SimpleRecordBuilder.calculateRecordSize(1);

                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 删除所有记录
                    for (int key : keys) {
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageDelete.deleteRecord(frame, searchKey, recordSize, comparator, mtr);
                    }

                    assertEquals(0, IndexPageLayout.readRecordCount(buf));

                    // 验证链表: Infimum -> Supremum
                    int infNext = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, infNext);

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 逻辑删除测试 ====================

    @Nested
    @DisplayName("逻辑删除（delete-mark）")
    class LogicalDeleteTests {

        @Test
        @DisplayName("标记记录为已删除")
        void testMarkDeleted() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入一条记录
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] searchKey = IntKeyComparator.intToBytes(100);
                    int recOffset = PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 验证初始状态未删除
                    assertFalse(PageDelete.isMarkedDeleted(buf, recOffset));

                    // 标记为已删除
                    PageDelete.markDeleted(frame, recOffset, mtr);

                    // 验证已标记
                    assertTrue(PageDelete.isMarkedDeleted(buf, recOffset));

                    // 记录仍在链表中（逻辑删除不移除）
                    assertEquals(1, IndexPageLayout.readRecordCount(buf));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 空闲链表测试 ====================

    @Nested
    @DisplayName("空闲链表")
    class FreeListTests {

        @Test
        @DisplayName("删除后记录加入空闲链表")
        void testDeleteAddsToFreeList() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入一条记录
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                    byte[] searchKey = IntKeyComparator.intToBytes(100);
                    int recOffset = PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 初始空闲链表为空
                    assertEquals(0, IndexPageLayout.readFreeListHead(buf));
                    assertEquals(0, IndexPageLayout.readGarbageSize(buf));

                    // 删除记录
                    PageDelete.deleteRecord(frame, searchKey, record.length, comparator, mtr);

                    // 验证空闲链表
                    assertEquals(recOffset, IndexPageLayout.readFreeListHead(buf),
                            "删除的记录应在空闲链表头");
                    assertEquals(record.length, IndexPageLayout.readGarbageSize(buf),
                            "垃圾空间应增加");

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
        @DisplayName("多次删除后链表仍完整")
        void testChainIntegrityAfterMultipleDeletes() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 5 条记录
                    int[] keys = {100, 200, 300, 400, 500};
                    int recordSize = SimpleRecordBuilder.calculateRecordSize(1);

                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 删除 200, 400（间隔删除）
                    PageDelete.deleteRecord(frame, IntKeyComparator.intToBytes(200), recordSize, comparator, mtr);
                    PageDelete.deleteRecord(frame, IntKeyComparator.intToBytes(400), recordSize, comparator, mtr);

                    // 验证链表完整性
                    int current = IndexPageLayout.INFIMUM_OFFSET;
                    int count = 0;
                    int[] expectedKeys = {100, 300, 500};

                    current = IndexPageLayout.readRecordNext(buf, current);
                    while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
                        int key = SimpleRecordBuilder.readKey(buf, current);
                        assertEquals(expectedKeys[count], key, "链表顺序应正确");
                        count++;
                        current = IndexPageLayout.readRecordNext(buf, current);
                    }

                    assertEquals(3, count, "应剩余 3 条记录");
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, current, "链表应到达 Supremum");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }
}
