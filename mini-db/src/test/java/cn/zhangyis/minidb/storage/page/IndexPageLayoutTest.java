package cn.zhangyis.minidb.storage.page;

import cn.zhangyis.minidb.storage.constants.StorageConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IndexPageLayout 单元测试
 *
 * <p>测试内容：</p>
 * <ul>
 *   <li>slot 偏移计算边界条件</li>
 *   <li>Page Header 读取方法</li>
 *   <li>空间计算方法</li>
 *   <li>记录链表读取</li>
 * </ul>
 *
 * <h2>验证的不变量</h2>
 * <ul>
 *   <li>slot 偏移公式唯一实现</li>
 *   <li>所有读取方法为纯函数</li>
 * </ul>
 *
 * @author MiniDB
 */
class IndexPageLayoutTest {

    private ByteBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        // 初始化为 0
        for (int i = 0; i < StorageConstants.PAGE_SIZE; i++) {
            buffer.put(i, (byte) 0);
        }
    }

    // ==================== Slot 偏移计算测试 ====================

    @Nested
    @DisplayName("Slot 偏移计算")
    class SlotOffsetTests {

        /**
         * 测试：slot 0 的偏移（页尾第一个槽，指向 Supremum）
         *
         * <pre>
         * 公式：PAGE_SIZE - FIL_TRAILER_SIZE - (slotNo + 1) * 2
         * slotNo=0: 16384 - 8 - 2 = 16374
         * </pre>
         */
        @Test
        @DisplayName("slot 0 偏移应为 16374")
        void testSlot0Offset() {
            int expected = StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE
                    - IndexPageLayout.PAGE_DIR_SLOT_SIZE;
            assertEquals(expected, IndexPageLayout.slotOffset(0));
            assertEquals(16374, IndexPageLayout.slotOffset(0));
        }

        /**
         * 测试：slot 1 的偏移（指向 Infimum）
         */
        @Test
        @DisplayName("slot 1 偏移应为 16372")
        void testSlot1Offset() {
            assertEquals(16372, IndexPageLayout.slotOffset(1));
        }

        /**
         * 测试：slot 偏移递减规律
         *
         * <p>每增加一个 slot，偏移减少 2 字节</p>
         */
        @Test
        @DisplayName("slot 偏移应递减 2 字节")
        void testSlotOffsetDecrement() {
            for (int i = 0; i < 100; i++) {
                int current = IndexPageLayout.slotOffset(i);
                int next = IndexPageLayout.slotOffset(i + 1);
                assertEquals(2, current - next, "slot " + i + " 和 slot " + (i + 1) + " 偏移差应为 2");
            }
        }

        /**
         * 测试：边界条件 - 最大 slot 数量
         *
         * <p>理论最大 slot 数约为 (16384 - 38 - 56 - 26 - 8) / 2 ≈ 8128</p>
         */
        @Test
        @DisplayName("最大 slot 偏移不应低于 USER_RECORDS_START")
        void testMaxSlotBoundary() {
            // 计算理论最大 slot 数
            int maxSlots = (StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE
                    - IndexPageLayout.USER_RECORDS_START) / IndexPageLayout.PAGE_DIR_SLOT_SIZE;

            // 最后一个有效 slot 的偏移应该大于等于 USER_RECORDS_START
            int lastValidSlotOffset = IndexPageLayout.slotOffset(maxSlots - 1);
            assertTrue(lastValidSlotOffset >= IndexPageLayout.USER_RECORDS_START,
                    "最后一个有效 slot 偏移应大于等于 USER_RECORDS_START");
        }

        /**
         * 符号追踪：slot 偏移计算 dry-run
         *
         * <pre>
         * 输入: slotNo = 5
         * 计算: 16384 - 8 - (5 + 1) * 2
         *     = 16376 - 12
         *     = 16364
         * </pre>
         */
        @Test
        @DisplayName("符号追踪: slot 5 偏移计算")
        void testSlotOffsetSymbolicTrace() {
            int slotNo = 5;
            int expected = 16384 - 8 - (slotNo + 1) * 2;
            assertEquals(expected, IndexPageLayout.slotOffset(slotNo));
            assertEquals(16364, IndexPageLayout.slotOffset(5));
        }
    }

    // ==================== Page Header 读取测试 ====================

    @Nested
    @DisplayName("Page Header 读取")
    class PageHeaderReadTests {

        @Test
        @DisplayName("读取槽数量")
        void testReadSlotCount() {
            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 10);
            assertEquals(10, IndexPageLayout.readSlotCount(buffer));
        }

        @Test
        @DisplayName("读取槽数量 - 无符号处理")
        void testReadSlotCountUnsigned() {
            // 测试无符号处理：写入 0xFFFF (-1 as signed short)
            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 0xFFFF);
            assertEquals(65535, IndexPageLayout.readSlotCount(buffer));
        }

        @Test
        @DisplayName("读取堆顶位置")
        void testReadHeapTop() {
            buffer.putShort(IndexPageLayout.PAGE_HEAP_TOP, (short) IndexPageLayout.USER_RECORDS_START);
            assertEquals(IndexPageLayout.USER_RECORDS_START, IndexPageLayout.readHeapTop(buffer));
        }

        @Test
        @DisplayName("读取记录数")
        void testReadRecordCount() {
            buffer.putShort(IndexPageLayout.PAGE_N_RECS, (short) 100);
            assertEquals(100, IndexPageLayout.readRecordCount(buffer));
        }

        @Test
        @DisplayName("读取 B+Tree 层级")
        void testReadLevel() {
            buffer.putShort(IndexPageLayout.PAGE_LEVEL, (short) 0);
            assertEquals(0, IndexPageLayout.readLevel(buffer));
            assertTrue(IndexPageLayout.isLeaf(buffer));

            buffer.putShort(IndexPageLayout.PAGE_LEVEL, (short) 1);
            assertEquals(1, IndexPageLayout.readLevel(buffer));
            assertFalse(IndexPageLayout.isLeaf(buffer));
        }

        @Test
        @DisplayName("读取 Compact 格式标志")
        void testReadCompactFormat() {
            // 设置 Compact 标志 (最高位)
            buffer.putShort(IndexPageLayout.PAGE_N_HEAP, (short) 0x8002);
            assertTrue(IndexPageLayout.isCompactFormat(buffer));
            assertEquals(2, IndexPageLayout.readHeapRecordCount(buffer));

            // 清除 Compact 标志
            buffer.putShort(IndexPageLayout.PAGE_N_HEAP, (short) 0x0002);
            assertFalse(IndexPageLayout.isCompactFormat(buffer));
            assertEquals(2, IndexPageLayout.readHeapRecordCount(buffer));
        }

        @Test
        @DisplayName("读取索引 ID")
        void testReadIndexId() {
            buffer.putLong(IndexPageLayout.PAGE_INDEX_ID, 12345678L);
            assertEquals(12345678L, IndexPageLayout.readIndexId(buffer));
        }

        @Test
        @DisplayName("读取最大事务 ID")
        void testReadMaxTrxId() {
            buffer.putLong(IndexPageLayout.PAGE_MAX_TRX_ID, 999L);
            assertEquals(999L, IndexPageLayout.readMaxTrxId(buffer));
        }

        @Test
        @DisplayName("读取空闲链表头")
        void testReadFreeListHead() {
            buffer.putShort(IndexPageLayout.PAGE_FREE, (short) 200);
            assertEquals(200, IndexPageLayout.readFreeListHead(buffer));
        }

        @Test
        @DisplayName("读取垃圾空间大小")
        void testReadGarbageSize() {
            buffer.putShort(IndexPageLayout.PAGE_GARBAGE, (short) 50);
            assertEquals(50, IndexPageLayout.readGarbageSize(buffer));
        }
    }

    // ==================== Slot 值读取测试 ====================

    @Nested
    @DisplayName("Slot 值读取")
    class SlotValueReadTests {

        @Test
        @DisplayName("读取 slot 0 值（Supremum 偏移）")
        void testReadSlot0Value() {
            buffer.putShort(IndexPageLayout.slotOffset(0), (short) IndexPageLayout.SUPREMUM_OFFSET);
            assertEquals(IndexPageLayout.SUPREMUM_OFFSET, IndexPageLayout.readSlotValue(buffer, 0));
        }

        @Test
        @DisplayName("读取 slot 1 值（Infimum 偏移）")
        void testReadSlot1Value() {
            buffer.putShort(IndexPageLayout.slotOffset(1), (short) IndexPageLayout.INFIMUM_OFFSET);
            assertEquals(IndexPageLayout.INFIMUM_OFFSET, IndexPageLayout.readSlotValue(buffer, 1));
        }

        @Test
        @DisplayName("读取多个 slot 值")
        void testReadMultipleSlotValues() {
            // 模拟有 5 个 slot 的情况
            int[] offsets = {107, 94, 150, 200, 250};
            for (int i = 0; i < offsets.length; i++) {
                buffer.putShort(IndexPageLayout.slotOffset(i), (short) offsets[i]);
            }

            for (int i = 0; i < offsets.length; i++) {
                assertEquals(offsets[i], IndexPageLayout.readSlotValue(buffer, i),
                        "slot " + i + " 值应为 " + offsets[i]);
            }
        }
    }

    // ==================== 空间计算测试 ====================

    @Nested
    @DisplayName("空间计算")
    class SpaceCalculationTests {

        @Test
        @DisplayName("计算 Page Directory 底部位置")
        void testPageDirectoryEnd() {
            // 2 个 slot 的情况
            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 2);
            int expected = StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE - 4;
            assertEquals(expected, IndexPageLayout.pageDirectoryEnd(buffer));
            assertEquals(16372, IndexPageLayout.pageDirectoryEnd(buffer));
        }

        @Test
        @DisplayName("计算剩余空间 - 初始状态")
        void testFreeSpaceInitial() {
            // 初始状态：2 个 slot，heap top = USER_RECORDS_START
            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 2);
            buffer.putShort(IndexPageLayout.PAGE_HEAP_TOP, (short) IndexPageLayout.USER_RECORDS_START);

            int dirEnd = IndexPageLayout.pageDirectoryEnd(buffer);  // 16372
            int heapTop = IndexPageLayout.USER_RECORDS_START;       // 120
            int expected = dirEnd - heapTop;                        // 16252

            assertEquals(expected, IndexPageLayout.freeSpace(buffer));
        }

        @Test
        @DisplayName("计算剩余空间 - 随 slot 增加而减少")
        void testFreeSpaceDecreases() {
            buffer.putShort(IndexPageLayout.PAGE_HEAP_TOP, (short) IndexPageLayout.USER_RECORDS_START);

            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 2);
            int freeSpace2 = IndexPageLayout.freeSpace(buffer);

            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 10);
            int freeSpace10 = IndexPageLayout.freeSpace(buffer);

            assertTrue(freeSpace2 > freeSpace10, "更多 slot 应该减少可用空间");
            assertEquals(16, freeSpace2 - freeSpace10, "8 个额外 slot 应减少 16 字节");
        }

        @Test
        @DisplayName("计算剩余空间 - 随 heap top 增加而减少")
        void testFreeSpaceDecreasesWithHeapTop() {
            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 2);

            buffer.putShort(IndexPageLayout.PAGE_HEAP_TOP, (short) 120);
            int freeSpace120 = IndexPageLayout.freeSpace(buffer);

            buffer.putShort(IndexPageLayout.PAGE_HEAP_TOP, (short) 220);
            int freeSpace220 = IndexPageLayout.freeSpace(buffer);

            assertEquals(100, freeSpace120 - freeSpace220, "heap top 增加 100 应减少 100 字节空间");
        }
    }

    // ==================== 记录链表读取测试 ====================

    @Nested
    @DisplayName("记录链表读取")
    class RecordListReadTests {

        @Test
        @DisplayName("读取记录的下一条记录偏移 - 链表结束")
        void testReadRecordNextEnd() {
            // next = 0 表示链表结束
            buffer.putShort(IndexPageLayout.SUPREMUM_OFFSET + IndexPageLayout.REC_OFF_NEXT, (short) 0);
            assertEquals(0, IndexPageLayout.readRecordNext(buffer, IndexPageLayout.SUPREMUM_OFFSET));
        }

        @Test
        @DisplayName("读取记录的下一条记录偏移 - 正常链接")
        void testReadRecordNextNormal() {
            // Infimum 的 next 指向 Supremum
            int relOffset = IndexPageLayout.SUPREMUM_OFFSET - IndexPageLayout.INFIMUM_OFFSET;
            buffer.putShort(IndexPageLayout.INFIMUM_OFFSET + IndexPageLayout.REC_OFF_NEXT, (short) relOffset);

            int next = IndexPageLayout.readRecordNext(buffer, IndexPageLayout.INFIMUM_OFFSET);
            assertEquals(IndexPageLayout.SUPREMUM_OFFSET, next);
        }

        @Test
        @DisplayName("读取第一条用户记录偏移 - 空页")
        void testReadFirstUserRecordOffsetEmpty() {
            // 空页面：Infimum 直接指向 Supremum
            int relOffset = IndexPageLayout.SUPREMUM_OFFSET - IndexPageLayout.INFIMUM_OFFSET;
            buffer.putShort(IndexPageLayout.INFIMUM_OFFSET + IndexPageLayout.REC_OFF_NEXT, (short) relOffset);

            int first = IndexPageLayout.readFirstUserRecordOffset(buffer);
            assertEquals(IndexPageLayout.SUPREMUM_OFFSET, first);
        }

        @Test
        @DisplayName("读取记录的 n_owned 值")
        void testReadRecordOwned() {
            // 设置 n_owned = 5 (高 4 位)
            buffer.put(IndexPageLayout.INFIMUM_OFFSET + IndexPageLayout.REC_OFF_N_OWNED, (byte) 0x50);
            assertEquals(5, IndexPageLayout.readRecordOwned(buffer, IndexPageLayout.INFIMUM_OFFSET));
        }

        @Test
        @DisplayName("读取记录的 n_owned 值 - 边界值")
        void testReadRecordOwnedBoundary() {
            // n_owned = 0
            buffer.put(IndexPageLayout.INFIMUM_OFFSET + IndexPageLayout.REC_OFF_N_OWNED, (byte) 0x00);
            assertEquals(0, IndexPageLayout.readRecordOwned(buffer, IndexPageLayout.INFIMUM_OFFSET));

            // n_owned = 15 (最大值)
            buffer.put(IndexPageLayout.INFIMUM_OFFSET + IndexPageLayout.REC_OFF_N_OWNED, (byte) 0xF0);
            assertEquals(15, IndexPageLayout.readRecordOwned(buffer, IndexPageLayout.INFIMUM_OFFSET));
        }
    }

    // ==================== 常量验证测试 ====================

    @Nested
    @DisplayName("常量验证")
    class ConstantVerificationTests {

        @Test
        @DisplayName("页面布局常量正确性")
        void testLayoutConstants() {
            assertEquals(38, IndexPageLayout.FIL_HEADER_SIZE);
            assertEquals(8, IndexPageLayout.FIL_TRAILER_SIZE);
            assertEquals(16384, IndexPageLayout.PAGE_SIZE);
            assertEquals(56, IndexPageLayout.PAGE_HEADER_SIZE);

            assertEquals(38, IndexPageLayout.PAGE_HEADER_START);
            assertEquals(94, IndexPageLayout.INFIMUM_OFFSET);
            assertEquals(107, IndexPageLayout.SUPREMUM_OFFSET);
            assertEquals(120, IndexPageLayout.USER_RECORDS_START);
        }

        @Test
        @DisplayName("Page Header 偏移正确性")
        void testPageHeaderOffsets() {
            int base = 38;  // PAGE_HEADER_START
            assertEquals(base, IndexPageLayout.PAGE_N_DIR_SLOTS);
            assertEquals(base + 2, IndexPageLayout.PAGE_HEAP_TOP);
            assertEquals(base + 4, IndexPageLayout.PAGE_N_HEAP);
            assertEquals(base + 6, IndexPageLayout.PAGE_FREE);
            assertEquals(base + 8, IndexPageLayout.PAGE_GARBAGE);
            assertEquals(base + 10, IndexPageLayout.PAGE_LAST_INSERT);
            assertEquals(base + 12, IndexPageLayout.PAGE_DIRECTION);
            assertEquals(base + 14, IndexPageLayout.PAGE_N_DIRECTION);
            assertEquals(base + 16, IndexPageLayout.PAGE_N_RECS);
            assertEquals(base + 18, IndexPageLayout.PAGE_MAX_TRX_ID);
            assertEquals(base + 26, IndexPageLayout.PAGE_LEVEL);
            assertEquals(base + 28, IndexPageLayout.PAGE_INDEX_ID);
        }

        @Test
        @DisplayName("Infimum/Supremum 偏移计算一致性")
        void testInfimumSupremumOffsets() {
            // Infimum = PAGE_HEADER_START + PAGE_HEADER_SIZE = 38 + 56 = 94
            assertEquals(IndexPageLayout.PAGE_HEADER_START + IndexPageLayout.PAGE_HEADER_SIZE,
                    IndexPageLayout.INFIMUM_OFFSET);

            // Supremum = INFIMUM_OFFSET + 13 = 94 + 13 = 107
            assertEquals(IndexPageLayout.INFIMUM_OFFSET + 13, IndexPageLayout.SUPREMUM_OFFSET);

            // USER_RECORDS_START = SUPREMUM_OFFSET + 13 = 107 + 13 = 120
            assertEquals(IndexPageLayout.SUPREMUM_OFFSET + 13, IndexPageLayout.USER_RECORDS_START);
        }
    }

    // ==================== 纯函数验证测试 ====================

    @Nested
    @DisplayName("纯函数验证")
    class PureFunctionTests {

        @Test
        @DisplayName("读取方法不修改 buffer")
        void testReadMethodsDoNotModifyBuffer() {
            // 设置初始状态
            buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 5);
            buffer.putShort(IndexPageLayout.PAGE_HEAP_TOP, (short) 200);
            buffer.putShort(IndexPageLayout.PAGE_N_RECS, (short) 10);

            // 保存原始 buffer 内容
            byte[] original = new byte[StorageConstants.PAGE_SIZE];
            buffer.position(0);
            buffer.get(original);
            buffer.rewind();

            // 调用所有读取方法
            IndexPageLayout.readSlotCount(buffer);
            IndexPageLayout.readHeapTop(buffer);
            IndexPageLayout.readRecordCount(buffer);
            IndexPageLayout.readLevel(buffer);
            IndexPageLayout.readIndexId(buffer);
            IndexPageLayout.freeSpace(buffer);
            IndexPageLayout.pageDirectoryEnd(buffer);

            // 验证 buffer 未被修改
            byte[] after = new byte[StorageConstants.PAGE_SIZE];
            buffer.position(0);
            buffer.get(after);

            assertArrayEquals(original, after, "读取方法不应修改 buffer");
        }

        @Test
        @DisplayName("slotOffset 是纯函数")
        void testSlotOffsetIsPure() {
            // 多次调用应返回相同结果
            for (int i = 0; i < 100; i++) {
                int first = IndexPageLayout.slotOffset(i);
                int second = IndexPageLayout.slotOffset(i);
                assertEquals(first, second, "slotOffset(" + i + ") 应始终返回相同值");
            }
        }
    }
}
