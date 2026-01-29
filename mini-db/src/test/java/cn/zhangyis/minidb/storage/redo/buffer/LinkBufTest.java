package cn.zhangyis.minidb.storage.redo.buffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LinkBuf 单元测试
 *
 * <p>测试 LinkBuf 的核心功能：
 * <ul>
 *   <li>slot 存储 endSn 而非 length</li>
 *   <li>对齐约束检查</li>
 *   <li>ABA 问题防护</li>
 *   <li>单线程推进约束</li>
 * </ul>
 */
@DisplayName("LinkBuf 测试")
class LinkBufTest {

    private LinkBuf linkBuf;

    // 使用的粒度
    private static final int GRANULARITY = 8;

    @BeforeEach
    void setUp() {
        // 16 个槽位，粒度 8 字节
        linkBuf = new LinkBuf(16, GRANULARITY);
    }

    @Test
    @DisplayName("基本属性测试")
    void testBasicProperties() {
        assertEquals(16, linkBuf.getCapacity());
        assertEquals(8, linkBuf.getGranularity());
        assertEquals(128, linkBuf.getCoverageRange()); // 16 * 8
        assertEquals(0, linkBuf.getTail());
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testConstructorValidation() {
        // capacity 必须是 2 的幂
        assertThrows(IllegalArgumentException.class, () -> new LinkBuf(3, 8));
        assertThrows(IllegalArgumentException.class, () -> new LinkBuf(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new LinkBuf(-1, 8));

        // granularity 必须为正
        assertThrows(IllegalArgumentException.class, () -> new LinkBuf(16, 0));
        assertThrows(IllegalArgumentException.class, () -> new LinkBuf(16, -1));

        // 正确的参数
        assertDoesNotThrow(() -> new LinkBuf(8, 4));
        assertDoesNotThrow(() -> new LinkBuf(1024, 64));
    }

    @Test
    @DisplayName("顺序写入测试 - slot 存储 endSn")
    void testSequentialWrite() {
        // 顺序写入 3 个记录 (所有 startSn 对齐到 granularity)
        linkBuf.addLink(0, 8);    // slot[0] = 8
        linkBuf.addLink(8, 16);   // slot[1] = 24
        linkBuf.addLink(24, 8);   // slot[3] = 32

        // 验证 slot 存储的是 endSn
        assertEquals(8, linkBuf.getSlotValue(0));   // endSn = 0 + 8
        assertEquals(24, linkBuf.getSlotValue(1));  // endSn = 8 + 16
        assertEquals(32, linkBuf.getSlotValue(3));  // endSn = 24 + 8

        // 推进 tail
        long newTail = linkBuf.advanceTail();

        // 应该推进到 32
        assertEquals(32, newTail);
        assertEquals(32, linkBuf.getTail());

        // 推进后 slot 应该被清零
        assertEquals(0, linkBuf.getSlotValue(0));
        assertEquals(0, linkBuf.getSlotValue(1));
        assertEquals(0, linkBuf.getSlotValue(3));
    }

    @Test
    @DisplayName("乱序写入测试 - 有空洞")
    void testOutOfOrderWriteWithHole() {
        // MTR-2 先完成 (startSn=8)
        linkBuf.addLink(8, 16);  // slot[1] = 24

        // 推进 tail - 应该停在空洞处
        long tail1 = linkBuf.advanceTail();
        assertEquals(0, tail1); // slot[0] 是空的

        // MTR-3 完成 (startSn=24)
        linkBuf.addLink(24, 8);  // slot[3] = 32

        // 清除 owner 以便同一线程再次调用
        linkBuf.clearAdvanceOwner();

        // 推进 tail - 仍然停在空洞处
        long tail2 = linkBuf.advanceTail();
        assertEquals(0, tail2);

        // MTR-1 完成 (填补空洞)
        linkBuf.addLink(0, 8);   // slot[0] = 8

        linkBuf.clearAdvanceOwner();

        // 推进 tail - 现在应该连续了
        long tail3 = linkBuf.advanceTail();
        assertEquals(32, tail3);
    }

    @Test
    @DisplayName("环形复用测试 - ABA 防护")
    void testCircularReuse() {
        // 容量 16，粒度 8，覆盖范围 128
        // 写入超过覆盖范围的数据

        // 第一轮: 0-96 (12 个 slot，每个 8 字节)
        for (int sn = 0; sn < 96; sn += 8) {
            linkBuf.addLink(sn, 8);
        }
        assertEquals(96, linkBuf.advanceTail());

        linkBuf.clearAdvanceOwner();

        // 第二轮: 96-192 (会复用槽位)
        for (int sn = 96; sn < 192; sn += 8) {
            linkBuf.addLink(sn, 8);
        }
        assertEquals(192, linkBuf.advanceTail());
    }

    @Test
    @DisplayName("advanceTailTo 测试")
    void testAdvanceTailTo() {
        linkBuf.addLink(0, 8);    // endSn = 8
        linkBuf.addLink(8, 16);   // endSn = 24
        linkBuf.addLink(24, 8);   // endSn = 32

        // 只推进到 20 - 只有第一条记录完整落在范围内
        long tail1 = linkBuf.advanceTailTo(20);
        assertEquals(8, tail1);

        linkBuf.clearAdvanceOwner();

        // 推进到 30 - 两条记录完整落在范围内
        long tail2 = linkBuf.advanceTailTo(30);
        assertEquals(24, tail2);

        linkBuf.clearAdvanceOwner();

        // 推进到 100 (超过实际完成的)
        long tail3 = linkBuf.advanceTailTo(100);
        assertEquals(32, tail3);
    }

    @Test
    @DisplayName("isComplete 测试")
    void testIsComplete() {
        linkBuf.addLink(0, 8);
        linkBuf.advanceTail();

        assertTrue(linkBuf.isComplete(0));
        assertTrue(linkBuf.isComplete(5));
        assertTrue(linkBuf.isComplete(8));
        assertFalse(linkBuf.isComplete(9));
        assertFalse(linkBuf.isComplete(100));
    }

    @Test
    @DisplayName("并发写入测试")
    void testConcurrentWrite() throws InterruptedException {
        int threadCount = 10;
        int writesPerThread = 100;
        int recordSize = 8; // 每条记录大小 (对齐到 granularity)

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicLong currentSn = new AtomicLong(0);

        // 使用更大的 LinkBuf
        LinkBuf concurrentBuf = new LinkBuf(1024, 8);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        long sn = currentSn.getAndAdd(recordSize);
                        // 模拟随机延迟
                        if (i % 10 == 0) {
                            Thread.yield();
                        }
                        concurrentBuf.addLink(sn, recordSize);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有写入完成
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

        // 推进 tail
        long finalTail = concurrentBuf.advanceTail();

        // 验证: 所有写入都应该被追踪
        long expectedTail = (long) threadCount * writesPerThread * recordSize;
        assertEquals(expectedTail, finalTail);

        executor.shutdown();
    }

    @Test
    @DisplayName("零长度写入被忽略")
    void testZeroLengthWrite() {
        linkBuf.addLink(0, 8);
        linkBuf.addLink(8, 0);   // 零长度，应该被忽略
        linkBuf.addLink(8, 16);  // 覆盖同一 slot

        long tail = linkBuf.advanceTail();
        assertEquals(24, tail);
    }

    @Test
    @DisplayName("对齐约束测试 - 未对齐应抛出异常")
    void testAlignmentConstraint() {
        // startSn 未对齐到 granularity (8)
        assertThrows(IllegalArgumentException.class,
            () -> linkBuf.addLink(3, 8),
            "startSn=3 未对齐到 granularity=8");

        assertThrows(IllegalArgumentException.class,
            () -> linkBuf.addLink(10, 8),
            "startSn=10 未对齐到 granularity=8");

        // 对齐的 startSn 应该正常工作
        assertDoesNotThrow(() -> linkBuf.addLink(0, 8));
        assertDoesNotThrow(() -> linkBuf.addLink(16, 8));
        assertDoesNotThrow(() -> linkBuf.addLink(24, 8));
    }

    @Test
    @DisplayName("ABA 防护 - endSn 验证")
    void testAbaProtection() {
        // 模拟 ABA 场景：tail 落后，新写入复用了 slot
        // 使用较小的 LinkBuf 使复用更容易发生

        LinkBuf smallBuf = new LinkBuf(4, 8); // 4 slots, 覆盖范围 32

        // 第一轮写入 slot[0]
        smallBuf.addLink(0, 8);   // endSn = 8
        smallBuf.advanceTail();   // tail = 8
        assertEquals(8, smallBuf.getTail());

        smallBuf.clearAdvanceOwner();

        // 继续推进
        smallBuf.addLink(8, 8);   // slot[1], endSn = 16
        smallBuf.addLink(16, 8);  // slot[2], endSn = 24
        smallBuf.addLink(24, 8);  // slot[3], endSn = 32
        smallBuf.advanceTail();   // tail = 32

        smallBuf.clearAdvanceOwner();

        // 第二轮：slot 会被复用
        smallBuf.addLink(32, 8);  // slot[0], endSn = 40 (复用第一轮的 slot)
        smallBuf.addLink(40, 8);  // slot[1], endSn = 48
        assertEquals(48, smallBuf.advanceTail());
    }

    @Test
    @DisplayName("reset 测试")
    void testReset() {
        linkBuf.addLink(0, 8);
        linkBuf.advanceTail();
        assertEquals(8, linkBuf.getTail());

        linkBuf.reset();

        assertEquals(0, linkBuf.getTail());
        assertEquals(0, linkBuf.getSlotValue(0));
    }

    @Test
    @DisplayName("resetTail 测试")
    void testResetTail() {
        linkBuf.resetTail(1000);
        assertEquals(1000, linkBuf.getTail());
    }

    @Test
    @DisplayName("toString 测试")
    void testToString() {
        String str = linkBuf.toString();
        assertTrue(str.contains("LinkBuf"));
        assertTrue(str.contains("capacity=16"));
        assertTrue(str.contains("granularity=8"));
        assertTrue(str.contains("tail=0"));
        assertTrue(str.contains("slotEncoding=endSn"));
    }

    @Test
    @DisplayName("跨越多个 slot 的大记录")
    void testLargeRecord() {
        // 一条记录可以跨越多个 granularity 单位
        // 但是 startSn 仍必须对齐
        linkBuf.addLink(0, 32);  // 跨越 4 个 granularity 单位

        // 只在 slot[0] 标记，endSn = 32
        assertEquals(32, linkBuf.getSlotValue(0));

        long tail = linkBuf.advanceTail();
        assertEquals(32, tail);
    }
}
