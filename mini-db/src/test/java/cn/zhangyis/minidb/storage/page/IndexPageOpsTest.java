package cn.zhangyis.minidb.storage.page;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
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
 * IndexPageOps 单元测试
 *
 * <p>测试内容：</p>
 * <ul>
 *   <li>页面初始化 (initPage)</li>
 *   <li>记录插入 (insertRecord)</li>
 *   <li>记录删除 (deleteRecord)</li>
 *   <li>Slot 操作 (setSlotValue, insertSlot, deleteSlot)</li>
 *   <li>X-latch 断言</li>
 * </ul>
 *
 * <h2>验证的不变量</h2>
 * <ul>
 *   <li>所有写操作必须通过 MTR</li>
 *   <li>所有写操作必须持有 X-latch</li>
 *   <li>写操作后 buffer 内容正确</li>
 * </ul>
 *
 * @author MiniDB
 */
class IndexPageOpsTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(10, diskManager);
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

    // ==================== 页面初始化测试 ====================

    @Nested
    @DisplayName("页面初始化 (initPage)")
    class InitPageTests {

        @Test
        @DisplayName("初始化空页面 - 基本结构正确")
        void testInitPageBasicStructure() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                // 分配新页面
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    // 初始化为 IndexPage
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 验证 Page Header
                    assertEquals(2, IndexPageLayout.readSlotCount(buf), "应有 2 个 slot");
                    assertEquals(IndexPageLayout.USER_RECORDS_START, IndexPageLayout.readHeapTop(buf),
                            "heap top 应指向用户记录区");
                    assertEquals(0, IndexPageLayout.readRecordCount(buf), "用户记录数应为 0");
                    assertEquals(0, IndexPageLayout.readLevel(buf), "level 应为 0 (叶子节点)");
                    assertEquals(1L, IndexPageLayout.readIndexId(buf), "index ID 应为 1");
                    assertTrue(IndexPageLayout.isCompactFormat(buf), "应使用 Compact 格式");

                    // 验证 Infimum
                    int infNext = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, infNext, "Infimum 应指向 Supremum");

                    // 验证 Supremum
                    int supNext = IndexPageLayout.readRecordNext(buf, IndexPageLayout.SUPREMUM_OFFSET);
                    assertEquals(0, supNext, "Supremum 的 next 应为 0");

                    // 验证 Page Directory
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, IndexPageLayout.readSlotValue(buf, 0),
                            "slot 0 应指向 Supremum");
                    assertEquals(IndexPageLayout.INFIMUM_OFFSET, IndexPageLayout.readSlotValue(buf, 1),
                            "slot 1 应指向 Infimum");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("初始化非叶子节点页面")
        void testInitPageNonLeaf() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    // 初始化为 level=2 的非叶子节点
                    IndexPageOps.initPage(frame, 100L, 2, mtr);

                    ByteBuffer buf = frame.buffer();

                    assertEquals(2, IndexPageLayout.readLevel(buf), "level 应为 2");
                    assertEquals(100L, IndexPageLayout.readIndexId(buf), "index ID 应为 100");
                    assertFalse(IndexPageLayout.isLeaf(buf), "不应是叶子节点");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("未持有 X-latch 时初始化应抛异常")
        void testInitPageWithoutXLatch() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);

                // 不获取写锁
                assertThrows(IllegalStateException.class, () -> {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                }, "未持有 X-latch 时应抛出 IllegalStateException");
            }
        }
    }

    // ==================== 记录插入测试 ====================

    @Nested
    @DisplayName("记录插入 (insertRecord)")
    class InsertRecordTests {

        @Test
        @DisplayName("插入第一条记录")
        void testInsertFirstRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    // 初始化页面
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 准备记录数据 (简化：5 字节 header + 8 字节数据)
                    byte[] recordData = new byte[13];
                    recordData[0] = 0x10;  // n_owned=1
                    recordData[3] = 0x00;  // rec_type=ordinary
                    // 后面是数据
                    for (int i = 5; i < 13; i++) {
                        recordData[i] = (byte) (i - 5);
                    }

                    // 在 Infimum 之后插入
                    int newRecOffset = IndexPageOps.insertRecord(frame, recordData,
                            IndexPageLayout.INFIMUM_OFFSET, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 验证记录偏移
                    assertEquals(IndexPageLayout.USER_RECORDS_START, newRecOffset,
                            "新记录应从 USER_RECORDS_START 开始");

                    // 验证 heap top 增加
                    assertEquals(IndexPageLayout.USER_RECORDS_START + 13, IndexPageLayout.readHeapTop(buf),
                            "heap top 应增加记录大小");

                    // 验证记录计数
                    assertEquals(1, IndexPageLayout.readRecordCount(buf), "用户记录数应为 1");

                    // 验证链表
                    int infNext = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(newRecOffset, infNext, "Infimum 应指向新记录");

                    int recNext = IndexPageLayout.readRecordNext(buf, newRecOffset);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, recNext, "新记录应指向 Supremum");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("插入多条记录")
        void testInsertMultipleRecords() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入 3 条记录
                    byte[] rec1 = createMockRecord(10);
                    byte[] rec2 = createMockRecord(10);
                    byte[] rec3 = createMockRecord(10);

                    int off1 = IndexPageOps.insertRecord(frame, rec1, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    int off2 = IndexPageOps.insertRecord(frame, rec2, off1, mtr);
                    int off3 = IndexPageOps.insertRecord(frame, rec3, off2, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 验证记录计数
                    assertEquals(3, IndexPageLayout.readRecordCount(buf), "用户记录数应为 3");

                    // 验证链表完整性: Infimum -> rec1 -> rec2 -> rec3 -> Supremum
                    assertEquals(off1, IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET));
                    assertEquals(off2, IndexPageLayout.readRecordNext(buf, off1));
                    assertEquals(off3, IndexPageLayout.readRecordNext(buf, off2));
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, IndexPageLayout.readRecordNext(buf, off3));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("空间不足时插入应抛异常")
        void testInsertRecordNoSpace() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 创建一个超大记录（超过可用空间）
                    int freeSpace = IndexPageLayout.freeSpace(frame.buffer());
                    byte[] hugeRecord = new byte[freeSpace + 100];

                    assertThrows(IllegalStateException.class, () -> {
                        IndexPageOps.insertRecord(frame, hugeRecord, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    }, "空间不足时应抛出 IllegalStateException");

                    mtr.rollback();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 记录删除测试 ====================

    @Nested
    @DisplayName("记录删除 (deleteRecord)")
    class DeleteRecordTests {

        @Test
        @DisplayName("删除单条记录")
        void testDeleteSingleRecord() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 插入一条记录
                    byte[] rec = createMockRecord(10);
                    int recOff = IndexPageOps.insertRecord(frame, rec, IndexPageLayout.INFIMUM_OFFSET, mtr);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(1, IndexPageLayout.readRecordCount(buf));

                    // 删除记录
                    IndexPageOps.deleteRecord(frame, IndexPageLayout.INFIMUM_OFFSET, recOff, rec.length, mtr);

                    // 验证记录计数减少
                    assertEquals(0, IndexPageLayout.readRecordCount(buf), "用户记录数应为 0");

                    // 验证空闲链表
                    assertEquals(recOff, IndexPageLayout.readFreeListHead(buf), "删除的记录应在空闲链表头");

                    // 验证垃圾空间增加
                    assertEquals(rec.length, IndexPageLayout.readGarbageSize(buf), "垃圾空间应增加");

                    // 验证链表绕过删除的记录
                    int infNext = IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET);
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, infNext, "Infimum 应直接指向 Supremum");

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

                    // 插入 3 条记录
                    byte[] rec1 = createMockRecord(10);
                    byte[] rec2 = createMockRecord(10);
                    byte[] rec3 = createMockRecord(10);

                    int off1 = IndexPageOps.insertRecord(frame, rec1, IndexPageLayout.INFIMUM_OFFSET, mtr);
                    int off2 = IndexPageOps.insertRecord(frame, rec2, off1, mtr);
                    int off3 = IndexPageOps.insertRecord(frame, rec3, off2, mtr);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(3, IndexPageLayout.readRecordCount(buf));

                    // 删除中间记录 (rec2)
                    IndexPageOps.deleteRecord(frame, off1, off2, rec2.length, mtr);

                    // 验证记录计数
                    assertEquals(2, IndexPageLayout.readRecordCount(buf), "用户记录数应为 2");

                    // 验证链表绕过删除的记录: Infimum -> rec1 -> rec3 -> Supremum
                    assertEquals(off1, IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET));
                    assertEquals(off3, IndexPageLayout.readRecordNext(buf, off1));
                    assertEquals(IndexPageLayout.SUPREMUM_OFFSET, IndexPageLayout.readRecordNext(buf, off3));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== Slot 操作测试 ====================

    @Nested
    @DisplayName("Slot 操作")
    class SlotOperationTests {

        @Test
        @DisplayName("设置 slot 值")
        void testSetSlotValue() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 修改 slot 0 的值
                    IndexPageOps.setSlotValue(frame, 0, 200, mtr);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(200, IndexPageLayout.readSlotValue(buf, 0));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("插入新 slot")
        void testInsertSlot() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(2, IndexPageLayout.readSlotCount(buf), "初始应有 2 个 slot");

                    // 保存原有 slot 值
                    int slot0Val = IndexPageLayout.readSlotValue(buf, 0);
                    int slot1Val = IndexPageLayout.readSlotValue(buf, 1);

                    // 在 slot 1 位置插入新 slot
                    IndexPageOps.insertSlot(frame, 1, mtr);

                    assertEquals(3, IndexPageLayout.readSlotCount(buf), "应有 3 个 slot");

                    // 验证原有 slot 被正确移动
                    // slot 0 保持不变
                    assertEquals(slot0Val, IndexPageLayout.readSlotValue(buf, 0), "slot 0 应保持不变");
                    // 原 slot 1 现在是 slot 2
                    assertEquals(slot1Val, IndexPageLayout.readSlotValue(buf, 2), "原 slot 1 应移动到 slot 2");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("删除 slot")
        void testDeleteSlot() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    ByteBuffer buf = frame.buffer();

                    // 先插入一个额外的 slot (共 3 个)
                    IndexPageOps.insertSlot(frame, 1, mtr);
                    IndexPageOps.setSlotValue(frame, 1, 150, mtr);
                    assertEquals(3, IndexPageLayout.readSlotCount(buf));

                    int slot0Val = IndexPageLayout.readSlotValue(buf, 0);
                    int slot2Val = IndexPageLayout.readSlotValue(buf, 2);

                    // 删除 slot 1
                    IndexPageOps.deleteSlot(frame, 1, mtr);

                    assertEquals(2, IndexPageLayout.readSlotCount(buf), "应有 2 个 slot");
                    assertEquals(slot0Val, IndexPageLayout.readSlotValue(buf, 0), "slot 0 应保持不变");
                    assertEquals(slot2Val, IndexPageLayout.readSlotValue(buf, 1), "原 slot 2 应移动到 slot 1");

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 记录链表操作测试 ====================

    @Nested
    @DisplayName("记录链表操作")
    class RecordListOperationTests {

        @Test
        @DisplayName("设置记录的 next 指针")
        void testSetRecordNext() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 修改 Infimum 的 next 指向一个自定义位置
                    IndexPageOps.setRecordNext(frame, IndexPageLayout.INFIMUM_OFFSET, 200, mtr);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(200, IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET));

                    // 设置 next 为 0（链表结束）
                    IndexPageOps.setRecordNext(frame, IndexPageLayout.INFIMUM_OFFSET, 0, mtr);
                    assertEquals(0, IndexPageLayout.readRecordNext(buf, IndexPageLayout.INFIMUM_OFFSET));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }

        @Test
        @DisplayName("设置记录的 n_owned 值")
        void testSetRecordOwned() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);

                    // 设置 Supremum 的 n_owned
                    IndexPageOps.setRecordOwned(frame, IndexPageLayout.SUPREMUM_OFFSET, 8, mtr);

                    ByteBuffer buf = frame.buffer();
                    assertEquals(8, IndexPageLayout.readRecordOwned(buf, IndexPageLayout.SUPREMUM_OFFSET));

                    // 边界值测试
                    IndexPageOps.setRecordOwned(frame, IndexPageLayout.SUPREMUM_OFFSET, 15, mtr);
                    assertEquals(15, IndexPageLayout.readRecordOwned(buf, IndexPageLayout.SUPREMUM_OFFSET));

                    IndexPageOps.setRecordOwned(frame, IndexPageLayout.SUPREMUM_OFFSET, 0, mtr);
                    assertEquals(0, IndexPageLayout.readRecordOwned(buf, IndexPageLayout.SUPREMUM_OFFSET));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== X-latch 断言测试 ====================

    @Nested
    @DisplayName("X-latch 断言")
    class XLatchAssertionTests {

        @Test
        @DisplayName("无 X-latch 时 setSlotValue 应抛异常")
        void testSetSlotValueWithoutXLatch() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);

                // 不获取写锁
                assertThrows(IllegalStateException.class, () -> {
                    IndexPageOps.setSlotValue(frame, 0, 100, mtr);
                });
            }
        }

        @Test
        @DisplayName("无 X-latch 时 insertRecord 应抛异常")
        void testInsertRecordWithoutXLatch() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);

                byte[] rec = createMockRecord(10);
                assertThrows(IllegalStateException.class, () -> {
                    IndexPageOps.insertRecord(frame, rec, IndexPageLayout.INFIMUM_OFFSET, mtr);
                });
            }
        }

        @Test
        @DisplayName("无 X-latch 时 deleteRecord 应抛异常")
        void testDeleteRecordWithoutXLatch() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);

                assertThrows(IllegalStateException.class, () -> {
                    IndexPageOps.deleteRecord(frame, IndexPageLayout.INFIMUM_OFFSET, 120, 10, mtr);
                });
            }
        }

        @Test
        @DisplayName("持有 S-latch 时写操作应抛异常")
        void testWriteOperationWithSLatch() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);

                // 获取读锁而非写锁
                frame.readLock();
                try {
                    assertThrows(IllegalStateException.class, () -> {
                        IndexPageOps.initPage(frame, 1L, 0, mtr);
                    }, "持有 S-latch 时写操作应抛出 IllegalStateException");
                } finally {
                    frame.readUnlock();
                }
            }
        }
    }

    // ==================== Page Header 修改测试 ====================

    @Nested
    @DisplayName("Page Header 修改")
    class PageHeaderModificationTests {

        @Test
        @DisplayName("修改各种 Page Header 字段")
        void testModifyPageHeaderFields() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.newPage(0);
                BufferFrame frame = bufferPool.getPage(page.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexPageOps.initPage(frame, 1L, 0, mtr);
                    ByteBuffer buf = frame.buffer();

                    // 测试各种 setter
                    IndexPageOps.setSlotCount(frame, 10, mtr);
                    assertEquals(10, IndexPageLayout.readSlotCount(buf));

                    IndexPageOps.setHeapTop(frame, 500, mtr);
                    assertEquals(500, IndexPageLayout.readHeapTop(buf));

                    IndexPageOps.setRecordCount(frame, 50, mtr);
                    assertEquals(50, IndexPageLayout.readRecordCount(buf));

                    IndexPageOps.setFreeListHead(frame, 300, mtr);
                    assertEquals(300, IndexPageLayout.readFreeListHead(buf));

                    IndexPageOps.setGarbageSize(frame, 100, mtr);
                    assertEquals(100, IndexPageLayout.readGarbageSize(buf));

                    IndexPageOps.setLevel(frame, 3, mtr);
                    assertEquals(3, IndexPageLayout.readLevel(buf));

                    IndexPageOps.setIndexId(frame, 999L, mtr);
                    assertEquals(999L, IndexPageLayout.readIndexId(buf));

                    IndexPageOps.setMaxTrxId(frame, 12345L, mtr);
                    assertEquals(12345L, IndexPageLayout.readMaxTrxId(buf));

                    mtr.commit();
                } finally {
                    frame.writeUnlock();
                }
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建模拟记录数据
     *
     * @param size 记录总大小
     * @return 记录字节数组
     */
    private byte[] createMockRecord(int size) {
        byte[] record = new byte[size];
        record[0] = 0x10;  // n_owned=1
        record[3] = 0x00;  // rec_type=ordinary
        // 填充测试数据
        for (int i = 5; i < size; i++) {
            record[i] = (byte) (i % 256);
        }
        return record;
    }
}
