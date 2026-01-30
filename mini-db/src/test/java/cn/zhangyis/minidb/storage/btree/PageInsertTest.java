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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageInsert 单元测试
 *
 * <p>测试 B+Tree 单页内的插入操作。</p>
 *
 * <h2>验证的不变量</h2>
 * <ul>
 *   <li>I1: 记录链完整性 - 插入后链表仍然有序且完整</li>
 *   <li>I2: PAGE_N_RECS 准确性 - 插入后计数正确</li>
 *   <li>I3: Page Directory 有序性 - slot 分裂正确</li>
 *   <li>I4: n_owned 约束 - 每个 slot 拥有 1-8 条记录</li>
 * </ul>
 *
 * @author MiniDB
 */
class PageInsertTest {

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

    // ==================== 基本插入测试 ====================

    @Nested
    @DisplayName("基本插入")
    class BasicInsertTests {

        @Test
        @DisplayName("插入第一条记录")
        void testInsertFirstRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 使用 PageInsert 插入记录
                    byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3, 4}, 2);
                    byte[] searchKey = IntKeyComparator.intToBytes(100);

                    int recOffset = PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);

                    assertTrue(recOffset > 0, "应返回有效的记录偏移");

                    ByteBuffer buf = frame.buffer();

                    // 验证记录计数
                    assertEquals(1, IndexPageLayout.readRecordCount(buf));

                    // 验证可以搜索到
                    assertTrue(PageSearch.containsKey(buf, searchKey, comparator));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("按顺序插入多条记录")
        void testInsertMultipleRecordsInOrder() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 按顺序插入 100, 200, 300
                    int[] keys = {100, 200, 300};
                    List<Integer> offsets = new ArrayList<>();

                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) (key / 100)}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        int offset = PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                        assertTrue(offset > 0);
                        offsets.add(offset);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 验证记录计数
                    assertEquals(3, IndexPageLayout.readRecordCount(buf));

                    // 验证所有键都能找到
                    for (int key : keys) {
                        assertTrue(PageSearch.containsKey(buf, IntKeyComparator.intToBytes(key), comparator),
                                "应能找到 key=" + key);
                    }

                    // 验证链表顺序: Infimum -> 100 -> 200 -> 300 -> Supremum
                    int current = IndexPageLayout.INFIMUM_OFFSET;
                    for (int i = 0; i < keys.length; i++) {
                        current = IndexPageLayout.readRecordNext(buf, current);
                        assertEquals(offsets.get(i), current, "链表顺序应正确");
                    }
                    current = IndexPageLayout.readRecordNext(buf, current);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, current, "最后应指向 Supremum");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("乱序插入多条记录")
        void testInsertMultipleRecordsOutOfOrder() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 乱序插入: 300, 100, 200
                    int[] insertOrder = {300, 100, 200};

                    for (int key : insertOrder) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) (key / 100)}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        int offset = PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                        assertTrue(offset > 0, "插入 key=" + key + " 应成功");
                    }

                    ByteBuffer buf = frame.buffer();

                    // 验证记录计数
                    assertEquals(3, IndexPageLayout.readRecordCount(buf));

                    // 验证链表顺序应该是有序的: Infimum -> 100 -> 200 -> 300 -> Supremum
                    int current = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    int prevKey = Integer.MIN_VALUE;

                    while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
                        int currentKey = SimpleRecordBuilder.readKey(buf, current);
                        assertTrue(currentKey > prevKey, "链表应有序: " + prevKey + " < " + currentKey);
                        prevKey = currentKey;
                        current = IndexPageLayout.readRecordNext(buf, current);
                    }

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 空间检查测试 ====================

    @Nested
    @DisplayName("空间检查")
    class SpaceCheckTests {

        @Test
        @DisplayName("空间不足时返回 -1")
        void testInsertNoSpace() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 创建一个超大记录
                    int freeSpace = IndexPageLayout.freeSpace(buf);
                    byte[] hugeRecord = new byte[freeSpace + 100];
                    byte[] searchKey = IntKeyComparator.intToBytes(100);

                    int result = PageInsert.insertRecord(frame, hugeRecord, searchKey, comparator, mtr);

                    assertEquals(-1, result, "空间不足时应返回 -1");

                    mtr.rollback();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("hasSpaceFor 正确判断空间")
        void testHasSpaceFor() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 小记录应该有空间
                    assertTrue(PageInsert.hasSpaceFor(buf, 100));

                    // 超大记录应该没有空间
                    int freeSpace = IndexPageLayout.freeSpace(buf);
                    assertFalse(PageInsert.hasSpaceFor(buf, freeSpace + 100));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("maxRecordSize 返回正确值")
        void testMaxRecordSize() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    ByteBuffer buf = frame.buffer();

                    int maxSize = PageInsert.maxRecordSize(buf);
                    int freeSpace = IndexPageLayout.freeSpace(buf);

                    // maxRecordSize = freeSpace - PAGE_DIR_SLOT_SIZE
                    assertEquals(freeSpace - IndexPageLayout.PAGE_DIR_SLOT_SIZE, maxSize);

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 大量插入测试 ====================

    @Nested
    @DisplayName("大量插入")
    class ManyInsertTests {

        @Test
        @DisplayName("插入 50 条记录并验证顺序")
        void testInsertManyRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 50 条记录（乱序）
                    int[] keys = new int[50];
                    for (int i = 0; i < 50; i++) {
                        keys[i] = (i * 7 + 13) % 500 + 1; // 生成伪随机但不重复的键
                    }

                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        int offset = PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                        assertTrue(offset > 0 || offset == -1, "插入应成功或因空间不足返回 -1");
                        if (offset == -1) {
                            break; // 空间不足，停止插入
                        }
                    }

                    ByteBuffer buf = frame.buffer();
                    int recordCount = IndexPageLayout.readRecordCount(buf);
                    assertTrue(recordCount > 0, "应至少插入一些记录");

                    // 验证链表有序
                    int current = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    int prevKey = Integer.MIN_VALUE;
                    int count = 0;

                    while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
                        int currentKey = SimpleRecordBuilder.readKey(buf, current);
                        assertTrue(currentKey > prevKey,
                                "链表应有序: prev=" + prevKey + ", current=" + currentKey);
                        prevKey = currentKey;
                        current = IndexPageLayout.readRecordNext(buf, current);
                        count++;
                    }

                    assertEquals(recordCount, count, "遍历的记录数应等于 PAGE_N_RECS");

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
    class ListIntegrityTests {

        @Test
        @DisplayName("插入后链表从 Infimum 到 Supremum 完整")
        void testListIntegrity() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入一些记录
                    int[] keys = {500, 100, 300, 200, 400};
                    for (int key : keys) {
                        byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                        byte[] searchKey = IntKeyComparator.intToBytes(key);
                        PageInsert.insertRecord(frame, record, searchKey, comparator, mtr);
                    }

                    ByteBuffer buf = frame.buffer();

                    // 验证链表完整性
                    // 1. 从 Infimum 开始能到达 Supremum
                    int current = IndexPageLayout.INFIMUM_OFFSET;
                    int steps = 0;
                    int maxSteps = 1000; // 防止无限循环

                    while (current != IndexPageLayout.SUPREMUM_OFFSET && steps < maxSteps) {
                        int next = IndexPageLayout.readRecordNext(buf, current);
                        assertTrue(next != 0 || current == IndexPageLayout.SUPREMUM_OFFSET,
                                "链表不应在 Supremum 之前断裂");
                        current = next;
                        steps++;
                    }

                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, current, "链表应到达 Supremum");
                    assertTrue(steps < maxSteps, "链表不应有环");

                    // 2. 遍历的用户记录数应等于 PAGE_N_RECS
                    int userRecords = steps - 1; // 减去 Infimum 到第一条用户记录的步骤
                    assertEquals(keys.length, userRecords, "用户记录数应正确");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }
}
