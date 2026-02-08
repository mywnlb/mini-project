package cn.zhangyis.minidb.storage.transaction.purge;

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
 * Undo 空间监控器测试
 *
 * <p>测试 UndoSpaceMonitor 正确收集和计算各项指标。</p>
 */
@DisplayName("UndoSpaceMonitor Tests")
class UndoSpaceMonitorTest {

    private UndoLogManager undoLogManager;
    private PurgeCoordinator purgeCoordinator;
    private UndoSpaceMonitor monitor;

    private static final long MAX_UNDO_SPACE = 128L * 1024 * 1024 * 1024; // 128GB

    @BeforeEach
    void setUp() {
        undoLogManager = mock(UndoLogManager.class);
        purgeCoordinator = mock(PurgeCoordinator.class);
        monitor = new UndoSpaceMonitor(undoLogManager, purgeCoordinator, MAX_UNDO_SPACE);
    }

    @Test
    @DisplayName("初始指标应为空")
    void testInitialMetrics() {
        UndoSpaceMonitor.Metrics metrics = monitor.getMetrics();
        assertNotNull(metrics);
        assertEquals(0.0, metrics.undoSpaceRatio);
        assertEquals(0, metrics.historyListLength);
        assertEquals(0.0, metrics.purgeLag);
        assertEquals(0, metrics.oldestReadViewAgeMs);
        assertFalse(metrics.needsThrottle);
        assertFalse(metrics.isCritical);
        assertFalse(metrics.isWarning);
    }

    @Test
    @DisplayName("计算 Undo 空间占比")
    void testUndoSpaceRatio() {
        // 50% 使用率
        long usedSpace = MAX_UNDO_SPACE / 2;
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);
        when(undoLogManager.getHistoryList()).thenReturn(null);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        assertEquals(0.5, monitor.getUndoSpaceRatio(), 0.01);
        assertFalse(monitor.isCritical());
        assertFalse(monitor.isWarning());
    }

    @Test
    @DisplayName("空间占比超过警告阈值")
    void testWarningSpaceRatio() {
        // 65% 使用率
        long usedSpace = (long) (MAX_UNDO_SPACE * 0.65);
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);
        when(undoLogManager.getHistoryList()).thenReturn(null);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        assertTrue(monitor.getUndoSpaceRatio() > UndoSpaceMonitor.WARNING_SPACE_RATIO);
        assertTrue(monitor.isWarning());
        assertFalse(monitor.isCritical());
    }

    @Test
    @DisplayName("空间占比超过危急阈值")
    void testCriticalSpaceRatio() {
        // 85% 使用率
        long usedSpace = (long) (MAX_UNDO_SPACE * 0.85);
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);
        when(undoLogManager.getHistoryList()).thenReturn(null);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        assertTrue(monitor.getUndoSpaceRatio() > UndoSpaceMonitor.CRITICAL_SPACE_RATIO);
        assertTrue(monitor.isCritical());
        assertTrue(monitor.needsThrottle());
    }

    @Test
    @DisplayName("计算 History List 长度")
    void testHistoryListLength() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(50000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        assertEquals(50000, monitor.getHistoryListLength());
    }

    @Test
    @DisplayName("History List 长度超过警告阈值")
    void testHistoryListWarning() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD + 10000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        assertTrue(monitor.getHistoryListLength() > UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD);
        assertTrue(monitor.isWarning());
    }

    @Test
    @DisplayName("计算 Purge 滞后比率")
    void testPurgeLag() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);

        HistoryList historyList = mock(HistoryList.class);
        // 设置 History List 长度为阈值的 2.5 倍
        long historyLen = (long) (UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD * 2.5);
        when(historyList.size()).thenReturn(historyLen);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        assertTrue(monitor.getPurgeLag() > UndoSpaceMonitor.PURGE_LAG_WARNING_THRESHOLD);
    }

    @Test
    @DisplayName("计算最老 ReadView 年龄")
    void testOldestReadViewAge() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);
        when(undoLogManager.getHistoryList()).thenReturn(null);

        // 创建一个 30 秒前创建的 ReadView
        ReadView oldReadView = mock(ReadView.class);
        when(oldReadView.getCreateTime()).thenReturn(System.currentTimeMillis() - 30000);

        List<ReadView> readViews = new ArrayList<>();
        readViews.add(oldReadView);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(readViews);

        monitor.updateMetrics();

        long age = monitor.getOldestReadViewAgeMs();
        assertTrue(age >= 30000 && age < 31000); // 允许 1 秒误差
    }

    @Test
    @DisplayName("长事务导致危急状态")
    void testLongTransactionCritical() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);
        when(undoLogManager.getHistoryList()).thenReturn(null);

        // 创建一个 65 秒前创建的 ReadView（超过 60 秒阈值）
        ReadView longTrx = mock(ReadView.class);
        when(longTrx.getCreateTime()).thenReturn(System.currentTimeMillis() - 65000);

        List<ReadView> readViews = new ArrayList<>();
        readViews.add(longTrx);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(readViews);

        monitor.updateMetrics();

        assertTrue(monitor.getOldestReadViewAgeMs() > UndoSpaceMonitor.LONG_TRX_THRESHOLD_MS);
        assertTrue(monitor.isCritical());
    }

    @Test
    @DisplayName("多个 ReadView 时应返回最老的")
    void testMultipleReadViews() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);
        when(undoLogManager.getHistoryList()).thenReturn(null);

        // 创建多个 ReadView
        ReadView rv1 = mock(ReadView.class);
        when(rv1.getCreateTime()).thenReturn(System.currentTimeMillis() - 10000);

        ReadView rv2 = mock(ReadView.class);
        when(rv2.getCreateTime()).thenReturn(System.currentTimeMillis() - 50000); // 最老

        ReadView rv3 = mock(ReadView.class);
        when(rv3.getCreateTime()).thenReturn(System.currentTimeMillis() - 20000);

        List<ReadView> readViews = new ArrayList<>();
        readViews.add(rv1);
        readViews.add(rv2);
        readViews.add(rv3);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(readViews);

        monitor.updateMetrics();

        long age = monitor.getOldestReadViewAgeMs();
        assertTrue(age >= 50000 && age < 51000); // 应该是最老的
    }

    @Test
    @DisplayName("获取指标快照")
    void testGetMetrics() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(50L * 1024 * 1024 * 1024);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(100000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        UndoSpaceMonitor.Metrics metrics = monitor.getMetrics();
        assertNotNull(metrics);
        assertEquals(0.5, metrics.undoSpaceRatio, 0.01);
        assertEquals(100000, metrics.historyListLength);
        assertNotNull(metrics.timestamp);
    }

    @Test
    @DisplayName("指标应该反映综合状态")
    void testComprehensiveMetrics() {
        // 设置多个危急指标
        long usedSpace = (long) (MAX_UNDO_SPACE * 0.75); // 75% 空间
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(usedSpace);

        HistoryList historyList = mock(HistoryList.class);
        when(historyList.size()).thenReturn(UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD + 50000);
        when(undoLogManager.getHistoryList()).thenReturn(historyList);

        ReadView oldReadView = mock(ReadView.class);
        when(oldReadView.getCreateTime()).thenReturn(System.currentTimeMillis() - 45000);

        List<ReadView> readViews = new ArrayList<>();
        readViews.add(oldReadView);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(readViews);

        monitor.updateMetrics();

        UndoSpaceMonitor.Metrics metrics = monitor.getMetrics();
        assertTrue(metrics.undoSpaceRatio > UndoSpaceMonitor.WARNING_SPACE_RATIO);
        assertTrue(metrics.historyListLength > UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD);
        assertTrue(metrics.isWarning());
    }

    @Test
    @DisplayName("toString 应该返回有效的字符串")
    void testToString() {
        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);
        when(undoLogManager.getHistoryList()).thenReturn(null);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        String str = monitor.toString();
        assertNotNull(str);
        assertTrue(str.contains("Metrics"));
    }

    @Test
    @DisplayName("更新时间应该被记录")
    void testUpdateTime() {
        long beforeUpdate = System.currentTimeMillis();

        when(undoLogManager.getTotalUndoSpaceUsed()).thenReturn(10L * 1024 * 1024 * 1024);
        when(undoLogManager.getHistoryList()).thenReturn(null);
        when(purgeCoordinator.getActiveReadViews()).thenReturn(new ArrayList<>());

        monitor.updateMetrics();

        long afterUpdate = System.currentTimeMillis();
        long updateTime = monitor.getLastUpdateTime();

        assertTrue(updateTime >= beforeUpdate && updateTime <= afterUpdate);
    }
}
