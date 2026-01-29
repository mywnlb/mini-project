package cn.zhangyis.minidb.storage.redo.wait;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WaitSlots 单元测试
 */
@DisplayName("WaitSlots 测试")
class WaitSlotsTest {

    private WaitSlots waitSlots;
    private AtomicLong progress;

    @BeforeEach
    void setUp() {
        // 8 个槽位，粒度 1024
        waitSlots = new WaitSlots(8, 1024);
        progress = new AtomicLong(0);
    }

    @Test
    @DisplayName("基本属性测试")
    void testBasicProperties() {
        assertEquals(8, waitSlots.getSlotCount());
        assertEquals(1024, waitSlots.getGranularity());
        assertEquals(0, waitSlots.getTotalWaiters());
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testConstructorValidation() {
        // slotCount 必须是 2 的幂
        assertThrows(IllegalArgumentException.class, () -> new WaitSlots(3, 1024));
        assertThrows(IllegalArgumentException.class, () -> new WaitSlots(0, 1024));

        // granularity 必须为正
        assertThrows(IllegalArgumentException.class, () -> new WaitSlots(8, 0));
        assertThrows(IllegalArgumentException.class, () -> new WaitSlots(8, -1));

        // 正确的参数
        assertDoesNotThrow(() -> new WaitSlots(16, 4096));
        assertDoesNotThrow(() -> new WaitSlots()); // 默认参数
    }

    @Test
    @DisplayName("已完成时直接返回")
    @Timeout(1)
    void testAlreadyComplete() throws InterruptedException {
        progress.set(100);

        // targetSn <= progress，应该直接返回
        boolean result = waitSlots.waitFor(50, progress::get, 1_000_000_000L);
        assertTrue(result);
        assertEquals(0, waitSlots.getTotalWaiters());
    }

    @Test
    @DisplayName("等待后被唤醒")
    @Timeout(5)
    void testWaitAndWakeup() throws InterruptedException {
        CountDownLatch waiterReady = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        // 等待线程
        Thread waiter = Thread.startVirtualThread(() -> {
            try {
                waiterReady.countDown();
                result.set(waitSlots.waitFor(1000, progress::get, 5_000_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                waiterDone.countDown();
            }
        });

        // 等待 waiter 进入等待状态
        waiterReady.await();
        Thread.sleep(50); // 确保进入 await

        assertEquals(1, waitSlots.getTotalWaiters());

        // 推进进度并唤醒
        progress.set(1500);
        waitSlots.wakeupRange(0, 1500);

        // 等待 waiter 完成
        assertTrue(waiterDone.await(3, TimeUnit.SECONDS));
        assertTrue(result.get());
        assertEquals(0, waitSlots.getTotalWaiters());
    }

    @Test
    @DisplayName("超时返回 false")
    @Timeout(2)
    void testTimeout() throws InterruptedException {
        progress.set(0);

        long start = System.nanoTime();
        boolean result = waitSlots.waitFor(1000, progress::get, 100_000_000L); // 100ms
        long elapsed = System.nanoTime() - start;

        assertFalse(result);
        assertTrue(elapsed >= 100_000_000L);
        assertTrue(elapsed < 500_000_000L); // 不应该太长
    }

    @Test
    @DisplayName("精准唤醒 - 只唤醒相关槽位")
    @Timeout(5)
    void testPreciseWakeup() throws InterruptedException {
        AtomicInteger slot0Wakeups = new AtomicInteger(0);
        AtomicInteger slot1Wakeups = new AtomicInteger(0);
        CountDownLatch allReady = new CountDownLatch(2);
        CountDownLatch slot0Done = new CountDownLatch(1);

        // 等待 sn=500 (slot 0)
        Thread waiter0 = Thread.startVirtualThread(() -> {
            try {
                allReady.countDown();
                waitSlots.waitFor(500, progress::get, 5_000_000_000L);
                slot0Wakeups.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                slot0Done.countDown();
            }
        });

        // 等待 sn=2000 (slot 1，因为 2000/1024 = 1)
        Thread waiter1 = Thread.startVirtualThread(() -> {
            try {
                allReady.countDown();
                // 使用较短超时，因为这个不应该被唤醒
                boolean result = waitSlots.waitFor(2000, progress::get, 500_000_000L);
                if (result) {
                    slot1Wakeups.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 等待两个 waiter 都进入等待状态
        allReady.await();
        Thread.sleep(50);

        // 只推进到 600 (只影响 slot 0)
        progress.set(600);
        waitSlots.wakeupRange(0, 600);

        // slot 0 的 waiter 应该被唤醒
        assertTrue(slot0Done.await(2, TimeUnit.SECONDS));
        assertEquals(1, slot0Wakeups.get());

        // slot 1 的 waiter 应该超时 (不被唤醒)
        Thread.sleep(600);
        assertEquals(0, slot1Wakeups.get());
    }

    @Test
    @DisplayName("wakeupAll 唤醒所有")
    @Timeout(5)
    void testWakeupAll() throws InterruptedException {
        int waiterCount = 4;
        CountDownLatch allReady = new CountDownLatch(waiterCount);
        CountDownLatch allDone = new CountDownLatch(waiterCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // 创建多个等待不同 SN 的线程
        for (int i = 0; i < waiterCount; i++) {
            long targetSn = (i + 1) * 1000;
            Thread.startVirtualThread(() -> {
                try {
                    allReady.countDown();
                    waitSlots.waitFor(targetSn, progress::get, 5_000_000_000L);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        // 等待所有 waiter 就绪
        allReady.await();
        Thread.sleep(50);

        assertEquals(waiterCount, waitSlots.getTotalWaiters());

        // 推进进度并唤醒所有
        progress.set(5000);
        waitSlots.wakeupAll();

        // 所有 waiter 都应该完成
        assertTrue(allDone.await(3, TimeUnit.SECONDS));
        assertEquals(waiterCount, successCount.get());
    }

    @Test
    @DisplayName("并发等待和唤醒")
    @Timeout(10)
    void testConcurrentWaitAndWakeup() throws InterruptedException {
        int waiterCount = 20;
        WaitSlots concurrentSlots = new WaitSlots(16, 100);
        AtomicLong concurrentProgress = new AtomicLong(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch allDone = new CountDownLatch(waiterCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // 启动多个等待线程
        for (int i = 0; i < waiterCount; i++) {
            long targetSn = (i + 1) * 50;
            executor.submit(() -> {
                try {
                    boolean result = concurrentSlots.waitFor(
                        targetSn, concurrentProgress::get, 5_000_000_000L);
                    if (result) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        // 逐步推进进度并唤醒
        for (int p = 100; p <= waiterCount * 50; p += 100) {
            Thread.sleep(10);
            long oldProgress = concurrentProgress.get();
            concurrentProgress.set(p);
            concurrentSlots.wakeupRange(oldProgress, p);
        }

        // 确保最终唤醒所有
        concurrentProgress.set(waiterCount * 50 + 100);
        concurrentSlots.wakeupAll();

        // 所有 waiter 都应该完成
        assertTrue(allDone.await(5, TimeUnit.SECONDS));
        assertEquals(waiterCount, successCount.get());

        executor.shutdown();
    }

    @Test
    @DisplayName("无等待者时跳过唤醒")
    void testSkipWakeupWhenNoWaiters() {
        assertEquals(0, waitSlots.getTotalWaiters());

        // 应该不会抛出异常
        assertDoesNotThrow(() -> waitSlots.wakeupRange(0, 10000));
        assertDoesNotThrow(() -> waitSlots.wakeupAll());
    }

    @Test
    @DisplayName("getWaiterCount 测试")
    @Timeout(2)
    void testGetWaiterCount() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);

        Thread waiter = Thread.startVirtualThread(() -> {
            try {
                ready.countDown();
                waitSlots.waitFor(500, progress::get, 2_000_000_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ready.await();
        Thread.sleep(50);

        // slot index for sn=500 with granularity=1024 is 0
        int slot = (int) ((500 / 1024) % 8);
        assertEquals(1, waitSlots.getWaiterCount(slot));

        // 唤醒
        progress.set(1000);
        waitSlots.wakeupAll();

        Thread.sleep(100);
        assertEquals(0, waitSlots.getWaiterCount(slot));
    }

    @Test
    @DisplayName("toString 测试")
    void testToString() {
        String str = waitSlots.toString();
        assertTrue(str.contains("WaitSlots"));
        assertTrue(str.contains("slotCount=8"));
        assertTrue(str.contains("granularity=1024"));
    }
}
