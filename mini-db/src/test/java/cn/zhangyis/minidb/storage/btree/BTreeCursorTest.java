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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BTreeCursor 单元测试
 *
 * @author MiniDB
 */
class BTreeCursorTest {

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

    // ==================== 基本定位测试 ====================

    @Nested
    @DisplayName("基本定位")
    class BasicPositioningTests {

        @Test
        @DisplayName("空树中 seekFirst")
        void testSeekFirstEmptyTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seekFirst();
                    assertFalse(cursor.isValid(), "空树中游标应无效");
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("seekFirst 定位到第一条记录")
        void testSeekFirst() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seekFirst();
                    assertTrue(cursor.isValid());
                    assertEquals(10, cursor.getKeyAsInt());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("seekLast 定位到最后一条记录")
        void testSeekLast() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seekLast();
                    assertTrue(cursor.isValid());
                    assertEquals(50, cursor.getKeyAsInt());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("seek 定位到指定键")
        void testSeekExactKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seek(IntKeyComparator.intToBytes(30));
                    assertTrue(cursor.isValid());
                    assertEquals(30, cursor.getKeyAsInt());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("seek 定位到不存在的键（找到下一个）")
        void testSeekNonExistentKey() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录 10, 20, 30, 40, 50
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    // 搜索 25，应定位到 30
                    cursor.seek(IntKeyComparator.intToBytes(25));
                    assertTrue(cursor.isValid());
                    assertEquals(30, cursor.getKeyAsInt());
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("seekGreater 定位到大于指定键的第一条")
        void testSeekGreater() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    // 搜索 > 30，应定位到 40
                    cursor.seekGreater(IntKeyComparator.intToBytes(30));
                    assertTrue(cursor.isValid());
                    assertEquals(40, cursor.getKeyAsInt());
                }

                mtr.commit();
            }
        }
    }

    // ==================== 遍历测试 ====================

    @Nested
    @DisplayName("遍历操作")
    class TraversalTests {

        @Test
        @DisplayName("正向遍历所有记录")
        void testForwardTraversal() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                int[] keys = {30, 10, 50, 20, 40};
                for (int key : keys) {
                    byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(key), mtr);
                }

                // 正向遍历
                List<Integer> traversed = new ArrayList<>();
                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seekFirst();
                    while (cursor.isValid()) {
                        traversed.add(cursor.getKeyAsInt());
                        cursor.next();
                    }
                }

                // 验证顺序
                assertEquals(List.of(10, 20, 30, 40, 50), traversed);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("反向遍历所有记录")
        void testBackwardTraversal() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 反向遍历
                List<Integer> traversed = new ArrayList<>();
                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seekLast();
                    while (cursor.isValid()) {
                        traversed.add(cursor.getKeyAsInt());
                        cursor.prev();
                    }
                }

                // 验证顺序
                assertEquals(List.of(50, 40, 30, 20, 10), traversed);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("从中间位置向前遍历")
        void testTraversalFromMiddle() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 从 30 开始向前遍历
                List<Integer> traversed = new ArrayList<>();
                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seek(IntKeyComparator.intToBytes(30));
                    while (cursor.isValid()) {
                        traversed.add(cursor.getKeyAsInt());
                        cursor.next();
                    }
                }

                assertEquals(List.of(30, 40, 50), traversed);

                mtr.commit();
            }
        }
    }

    // ==================== 范围限制测试 ====================

    @Nested
    @DisplayName("范围限制")
    class RangeBoundTests {

        @Test
        @DisplayName("设置下界（包含）")
        void testLowerBoundInclusive() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 设置下界 >= 30
                List<Integer> traversed = new ArrayList<>();
                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.setLowerBound(RangeBound.inclusive(IntKeyComparator.intToBytes(30)));
                    cursor.seekFirst();
                    while (cursor.isValid()) {
                        traversed.add(cursor.getKeyAsInt());
                        cursor.next();
                    }
                }

                assertEquals(List.of(30, 40, 50), traversed);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("设置下界（不包含）")
        void testLowerBoundExclusive() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 设置下界 > 30
                List<Integer> traversed = new ArrayList<>();
                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.setLowerBound(RangeBound.exclusive(IntKeyComparator.intToBytes(30)));
                    cursor.seekFirst();
                    while (cursor.isValid()) {
                        traversed.add(cursor.getKeyAsInt());
                        cursor.next();
                    }
                }

                assertEquals(List.of(40, 50), traversed);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("设置上界（包含）")
        void testUpperBoundInclusive() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 设置上界 <= 30
                List<Integer> traversed = new ArrayList<>();
                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.setUpperBound(RangeBound.inclusive(IntKeyComparator.intToBytes(30)));
                    cursor.seekFirst();
                    while (cursor.isValid()) {
                        traversed.add(cursor.getKeyAsInt());
                        cursor.next();
                    }
                }

                assertEquals(List.of(10, 20, 30), traversed);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("设置上下界（BETWEEN）")
        void testBothBounds() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录
                for (int i = 10; i <= 50; i += 10) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                // 设置范围 20 <= key <= 40
                List<Integer> traversed = new ArrayList<>();
                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.setRange(
                            RangeBound.inclusive(IntKeyComparator.intToBytes(20)),
                            RangeBound.inclusive(IntKeyComparator.intToBytes(40))
                    );
                    cursor.seekFirst();
                    while (cursor.isValid()) {
                        traversed.add(cursor.getKeyAsInt());
                        cursor.next();
                    }
                }

                assertEquals(List.of(20, 30, 40), traversed);

                mtr.commit();
            }
        }
    }

    // ==================== 读取值测试 ====================

    @Nested
    @DisplayName("读取值")
    class ReadValueTests {

        @Test
        @DisplayName("读取记录的值")
        void testGetValue() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                // 插入记录，值为键的字节表示
                byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1, 2, 3}, 2);
                btree.insert(record, IntKeyComparator.intToBytes(100), mtr);

                try (BTreeCursor cursor = btree.openCursor(mtr)) {
                    cursor.seek(IntKeyComparator.intToBytes(100));
                    assertTrue(cursor.isValid());

                    byte[] value = cursor.getValue(3);
                    assertArrayEquals(new byte[]{1, 2, 3}, value);
                }

                mtr.commit();
            }
        }
    }

    // ==================== 资源管理测试 ====================

    @Nested
    @DisplayName("资源管理")
    class ResourceManagementTests {

        @Test
        @DisplayName("关闭后游标无效")
        void testCursorInvalidAfterClose() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                btree.insert(record, IntKeyComparator.intToBytes(100), mtr);

                BTreeCursor cursor = btree.openCursor(mtr);
                cursor.seekFirst();
                assertTrue(cursor.isValid());

                cursor.close();
                assertFalse(cursor.isValid());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("关闭后操作抛异常")
        void testOperationAfterCloseThrows() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                BTreeCursor cursor = btree.openCursor(mtr);
                cursor.close();

                assertThrows(IllegalStateException.class, cursor::seekFirst);

                mtr.commit();
            }
        }
    }
}
