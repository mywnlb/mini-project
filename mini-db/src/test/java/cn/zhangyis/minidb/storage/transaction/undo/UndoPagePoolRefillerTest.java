package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.space.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UndoPagePoolRefiller 单元测试
 *
 * <p>测试后台补充线程的功能。</p>
 */
class UndoPagePoolRefillerTest {

    @Mock
    private UndoLogManager undoLogManager;

    @Mock
    private BufferPool bufferPool;

    @Mock
    private Segment[] rollbackSegmentPhysical;

    private UndoPagePoolRefiller refiller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        refiller = new UndoPagePoolRefiller(undoLogManager, bufferPool, rollbackSegmentPhysical, 100);
    }

    // ==================== 基础状态测试 ====================

    @Test
    void testInitialState() {
        assertFalse(refiller.isStarted(), "Should not be started initially");
        assertFalse(refiller.isStopped(), "Should not be stopped initially");
        assertEquals(0, refiller.getTotalRefillCount(), "Total refill count should be 0");
        assertEquals(0, refiller.getTotalRefillPages(), "Total refill pages should be 0");
    }

    @Test
    void testGetStats() {
        String stats = refiller.getStats();
        assertNotNull(stats, "Stats should not be null");
        assertTrue(stats.contains("started=false"), "Stats should contain started status");
        assertTrue(stats.contains("stopped=false"), "Stats should contain stopped status");
    }

    // ==================== 线程生命周期测试 ====================

    @Test
    void testThreadStartAndStop() throws InterruptedException {
        // 启动线程
        Thread thread = new Thread(refiller);
        thread.start();

        // 等待线程启动
        Thread.sleep(50);
        assertTrue(refiller.isStarted(), "Should be started after thread starts");

        // 请求停止
        refiller.requestStop();

        // 等待线程停止
        boolean stopped = refiller.waitForStop(2000);
        assertTrue(stopped, "Should stop within timeout");
        assertTrue(refiller.isStopped(), "Should be stopped");

        // 等待线程结束
        thread.join(1000);
        assertFalse(thread.isAlive(), "Thread should be terminated");
    }

    @Test
    void testWaitForStopTimeout() throws InterruptedException {
        // 启动线程
        Thread thread = new Thread(refiller);
        thread.start();

        // 等待线程启动
        Thread.sleep(50);

        // 不请求停止，直接等待（应该超时）
        boolean stopped = refiller.waitForStop(100);
        assertFalse(stopped, "Should timeout when not requesting stop");

        // 清理
        refiller.requestStop();
        thread.join(2000);
    }

    // ==================== 统计信息测试 ====================

    @Test
    void testStatisticsTracking() throws InterruptedException {
        // 初始统计
        assertEquals(0, refiller.getTotalRefillCount(), "Initial refill count should be 0");
        assertEquals(0, refiller.getTotalRefillPages(), "Initial refill pages should be 0");

        // 启动线程
        Thread thread = new Thread(refiller);
        thread.start();

        // 等待线程启动
        Thread.sleep(50);

        // 请求停止
        refiller.requestStop();

        // 等待线程停止
        boolean stopped = refiller.waitForStop(2000);
        assertTrue(stopped, "Should stop within timeout");

        // 清理
        thread.join(1000);
    }

    // ==================== 异常处理测试 ====================

    @Test
    void testGracefulShutdownOnException() throws InterruptedException {
        // 配置 mock 抛出异常
        when(undoLogManager.getAllUndoPagePools()).thenThrow(new RuntimeException("Test exception"));

        // 启动线程
        Thread thread = new Thread(refiller);
        thread.start();

        // 等待线程启动
        Thread.sleep(50);

        // 请求停止
        refiller.requestStop();

        // 等待线程停止
        boolean stopped = refiller.waitForStop(2000);
        assertTrue(stopped, "Should stop gracefully even with exceptions");

        // 清理
        thread.join(1000);
    }

    // ==================== 并发测试 ====================

    @Test
    void testConcurrentStopRequest() throws InterruptedException {
        // 启动线程
        Thread thread = new Thread(refiller);
        thread.start();

        // 等待线程启动
        Thread.sleep(50);

        // 多个线程同时请求停止
        Thread stopThread1 = new Thread(refiller::requestStop);
        Thread stopThread2 = new Thread(refiller::requestStop);

        stopThread1.start();
        stopThread2.start();

        stopThread1.join();
        stopThread2.join();

        // 等待线程停止
        boolean stopped = refiller.waitForStop(2000);
        assertTrue(stopped, "Should stop gracefully with concurrent stop requests");

        // 清理
        thread.join(1000);
    }
}
