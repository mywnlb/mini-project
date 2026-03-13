package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 自适应 Purge 调度器测试
 *
 * <p>测试 AdaptivePurgeScheduler 根据不同指标做出正确的调度决策。</p>
 */
@DisplayName("AdaptivePurgeScheduler Tests")
class AdaptivePurgeSchedulerTest {

    private UndoSpaceMonitor monitor;
    private AdaptivePurgeScheduler scheduler;
    private UndoLogManager undoLogManager;
    private PurgeCoordinator purgeCoordinator;

    @BeforeEach
    void setUp() {
        // Mock 依赖
        undoLogManager = mock(UndoLogManager.class);
        purgeCoordinator = mock(PurgeCoordinator.class);

        // 创建监控器和调度器
        monitor = new UndoSpaceMonitor(undoLogManager, purgeCoordinator);
        scheduler = new AdaptivePurgeScheduler(monitor);
    }

    @Test
    @DisplayName("初始状态应为 NORMAL 模式")
    void testInitialPolicyIsNormal() {
        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.NORMAL, scheduler.getCurrentPolicy());
        assertEquals(AdaptivePurgeScheduler.DEFAULT_PURGE_INTERVAL_MS, scheduler.getPurgeIntervalMs());
        assertEquals(AdaptivePurgeScheduler.DEFAULT_BATCH_SIZE, scheduler.getPurgeBatchSize());
        assertFalse(scheduler.shouldThrottleNewTransactions());
    }

    @Test
    @DisplayName("空闲状态：无 History List 条目")
    void testIdlePolicy() {
        // 模拟空闲状态
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(0L);
        when(undoLogManager.getHistoryList()).thenReturn(null);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.IDLE, scheduler.getCurrentPolicy());
        assertEquals(AdaptivePurgeScheduler.DEFAULT_PURGE_INTERVAL_MS, scheduler.getPurgeIntervalMs());
    }

    @Test
    @DisplayName("警告模式：History List 长度超过阈值")
    void testWarningPolicy() {
        // 模拟警告状态
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(50L * 1024 * 1024 * 1024);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn((int) (UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD + 1000));
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.WARNING, scheduler.getCurrentPolicy());
        assertTrue(scheduler.getPurgeIntervalMs() < AdaptivePurgeScheduler.DEFAULT_PURGE_INTERVAL_MS);
        assertTrue(scheduler.getPurgeBatchSize() > AdaptivePurgeScheduler.DEFAULT_BATCH_SIZE);
    }

    @Test
    @DisplayName("激进模式：空间占比超过警告阈值")
    void testAggressivePolicy() {
        // 模拟激进状态
        long maxSpace = 128L * 1024 * 1024 * 1024;
        long usedSpace = (long) (maxSpace * 0.70); // 70% 使用率
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(50000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.AGGRESSIVE, scheduler.getCurrentPolicy());
        assertEquals(AdaptivePurgeScheduler.MIN_PURGE_INTERVAL_MS, scheduler.getPurgeIntervalMs());
        assertEquals(AdaptivePurgeScheduler.AGGRESSIVE_BATCH_SIZE, scheduler.getPurgeBatchSize());
    }

    @Test
    @DisplayName("危急模式：空间占比超过危急阈值")
    void testCriticalPolicyBySpace() {
        // 模拟危急状态（空间）
        long maxSpace = 128L * 1024 * 1024 * 1024;
        long usedSpace = (long) (maxSpace * 0.85); // 85% 使用率
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(100000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.CRITICAL, scheduler.getCurrentPolicy());
        assertEquals(AdaptivePurgeScheduler.MIN_PURGE_INTERVAL_MS, scheduler.getPurgeIntervalMs());
        assertEquals(AdaptivePurgeScheduler.MAX_BATCH_SIZE, scheduler.getPurgeBatchSize());
        assertTrue(scheduler.shouldThrottleNewTransactions());
    }

    @Test
    @DisplayName("危急模式：长事务拖死 Purge")
    void testCriticalPolicyByLongTransaction() {
        // 模拟危急状态（长事务）
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(50L * 1024 * 1024 * 1024);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(50000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        // 创建一个很老的 ReadView（60+ 秒前创建）
        ReadView oldReadView = mock(ReadView.class);
        when(oldReadView.getCreateTime()).thenReturn(System.currentTimeMillis() - 65000);

        List<ReadView> readViews = new ArrayList<>();
        readViews.add(oldReadView);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(readViews);

        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.CRITICAL, scheduler.getCurrentPolicy());
        assertTrue(scheduler.shouldThrottleNewTransactions());
    }

    @Test
    @DisplayName("危急模式：Purge 滞后过高")
    void testCriticalPolicyByPurgeLag() {
        // 模拟危急状态（Purge 滞后）
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(50L * 1024 * 1024 * 1024);

        HistoryList historyList = mock(HistoryList.class);
        // 设置 History List 长度远超阈值，导致滞后比率 > 2
        when(historyList.size()).thenReturn((int) (UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD * 2.5));
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.CRITICAL, scheduler.getCurrentPolicy());
    }

    @Test
    @DisplayName("策略变化时应记录日志")
    void testPolicyChangeLogging() {
        // 初始状态
        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.NORMAL, scheduler.getCurrentPolicy());

        // 转换到激进模式
        long maxSpace = 128L * 1024 * 1024 * 1024;
        long usedSpace = (long) (maxSpace * 0.70);
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(50000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.AGGRESSIVE, scheduler.getCurrentPolicy());

        // 再次调用应该保持相同策略
        monitor.updateMetrics();
        scheduler.updateSchedule();

        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.AGGRESSIVE, scheduler.getCurrentPolicy());
    }

    @Test
    @DisplayName("获取统计信息")
    void testGetStats() {
        String stats = scheduler.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("AdaptivePurgeScheduler"));
        assertTrue(stats.contains("policy="));
        assertTrue(stats.contains("interval="));
        assertTrue(stats.contains("batchSize="));
    }

    @Test
    @DisplayName("多次更新调度应该正确反映指标变化")
    void testMultipleScheduleUpdates() {
        // 第一次：空闲状态
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);
        when(undoLogManager.getHistoryList()).thenReturn(null);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();
        scheduler.updateSchedule();
        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.IDLE, scheduler.getCurrentPolicy());

        // 第二次：转换到警告状态
        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn((int) (UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD + 1000));
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        monitor.updateMetrics();
        scheduler.updateSchedule();
        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.WARNING, scheduler.getCurrentPolicy());

        // 第三次：转换到危急状态
        long maxSpace = 128L * 1024 * 1024 * 1024;
        long usedSpace = (long) (maxSpace * 0.85);
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);

        monitor.updateMetrics();
        scheduler.updateSchedule();
        assertEquals(AdaptivePurgeScheduler.SchedulePolicy.CRITICAL, scheduler.getCurrentPolicy());
    }
}
