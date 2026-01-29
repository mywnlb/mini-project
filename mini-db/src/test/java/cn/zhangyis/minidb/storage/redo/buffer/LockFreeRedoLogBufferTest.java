package cn.zhangyis.minidb.storage.redo.buffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LockFreeRedoLogBuffer 单元测试
 */
@DisplayName("LockFreeRedoLogBuffer 测试")
class LockFreeRedoLogBufferTest {

    private LockFreeRedoLogBuffer buffer;

    @BeforeEach
    void setUp() {
        // 1MB buffer, 适合测试
        // linkBufCapacity=16384, granularity=8 => coverage=131072 bytes
        // 足够覆盖并发测试中 10*100*64=64000 bytes (reserveSpace)
        // 和 10*50*64=32000 bytes (concurrent write)
        buffer = new LockFreeRedoLogBuffer(
                1024 * 1024,  // 1MB capacity
                16384,        // linkBufCapacity (coverage = 16384*8 = 128KB)
                8,            // linkBufGranularity
                16,           // waitSlotCount
                4096          // waitSlotGranularity
        );
    }

    @Test
    @DisplayName("基本属性测试")
    void testBasicProperties() {
        assertEquals(1024 * 1024, buffer.getCapacity());
        assertEquals(0, buffer.getCurrentSn());
        assertEquals(0, buffer.getBufReadyForWriteSn());
        assertEquals(0, buffer.getWriteSn());
        assertEquals(0, buffer.getFlushedSn());
    }

    @Test
    @DisplayName("顺序写入测试")
    void testSequentialWrite() throws InterruptedException {
        byte[] data1 = new byte[100];
        byte[] data2 = new byte[200];

        // 写入第一条记录
        long sn1 = buffer.reserveSpace(data1.length);
        assertEquals(0, sn1);
        buffer.writeRecord(sn1, data1);
        buffer.markWriteComplete(sn1, data1.length);

        // 写入第二条记录
        long sn2 = buffer.reserveSpace(data2.length);
        assertEquals(100, sn2);
        buffer.writeRecord(sn2, data2);
        buffer.markWriteComplete(sn2, data2.length);

        // 推进 recentWritten.tail
        buffer.getRecentWritten().advanceTail();

        assertEquals(300, buffer.getCurrentSn());
        assertEquals(300, buffer.getBufReadyForWriteSn());
    }

    @Test
    @DisplayName("并发空间预留测试")
    @Timeout(10)
    void testConcurrentReserve() throws InterruptedException {
        int threadCount = 10;
        int reservesPerThread = 100;
        int recordSize = 64;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicLong totalReserved = new AtomicLong(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < reservesPerThread; i++) {
                        long sn = buffer.reserveSpace(recordSize);
                        totalReserved.addAndGet(recordSize);
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
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        // 验证: 所有预留的空间应该是连续的，无重叠
        long expectedTotal = (long) threadCount * reservesPerThread * recordSize;
        assertEquals(expectedTotal, buffer.getCurrentSn());
        assertEquals(expectedTotal, totalReserved.get());

        executor.shutdown();
    }

    @Test
    @DisplayName("并发写入测试")
    @Timeout(10)
    void testConcurrentWrite() throws InterruptedException {
        int threadCount = 10;
        int writesPerThread = 50;
        int recordSize = 64;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        byte[] data = new byte[recordSize];
                        // 填充数据用于验证
                        data[0] = (byte) threadId;
                        data[1] = (byte) i;

                        long sn = buffer.reserveSpace(recordSize);
                        buffer.writeRecord(sn, data);
                        buffer.markWriteComplete(sn, recordSize);
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
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        // 推进 tail
        buffer.getRecentWritten().advanceTail();

        // 验证
        long expectedSn = (long) threadCount * writesPerThread * recordSize;
        assertEquals(expectedSn, buffer.getCurrentSn());
        assertEquals(expectedSn, buffer.getBufReadyForWriteSn());

        executor.shutdown();
    }

    @Test
    @DisplayName("乱序完成测试 - 有空洞")
    void testOutOfOrderCompletion() throws InterruptedException {
        // 预留 3 个空间
        long sn1 = buffer.reserveSpace(100);
        long sn2 = buffer.reserveSpace(200);
        long sn3 = buffer.reserveSpace(150);

        assertEquals(0, sn1);
        assertEquals(100, sn2);
        assertEquals(300, sn3);

        // 乱序完成: sn2 先完成
        buffer.writeRecord(sn2, new byte[200]);
        buffer.markWriteComplete(sn2, 200);

        buffer.getRecentWritten().advanceTail();
        assertEquals(0, buffer.getBufReadyForWriteSn()); // 有空洞

        // sn3 完成
        buffer.writeRecord(sn3, new byte[150]);
        buffer.markWriteComplete(sn3, 150);

        buffer.getRecentWritten().advanceTail();
        assertEquals(0, buffer.getBufReadyForWriteSn()); // 仍有空洞

        // sn1 完成 (填补空洞)
        buffer.writeRecord(sn1, new byte[100]);
        buffer.markWriteComplete(sn1, 100);

        buffer.getRecentWritten().advanceTail();
        assertEquals(450, buffer.getBufReadyForWriteSn()); // 连续了
    }

    @Test
    @DisplayName("水位推进测试")
    void testAdvanceWatermarks() throws InterruptedException {
        // 写入数据
        long sn = buffer.reserveSpace(100);
        buffer.writeRecord(sn, new byte[100]);
        buffer.markWriteComplete(sn, 100);
        buffer.getRecentWritten().advanceTail();

        // 模拟 LogWriter
        buffer.advanceWriteSn(100);
        assertEquals(100, buffer.getWriteSn());

        // 模拟 LogFlusher
        buffer.advanceFlushedSn(100);
        assertEquals(100, buffer.getFlushedSn());
    }

    @Test
    @DisplayName("getWriteReadyData 测试")
    void testGetWriteReadyData() throws InterruptedException {
        byte[] data = {1, 2, 3, 4, 5};

        long sn = buffer.reserveSpace(data.length);
        buffer.writeRecord(sn, data);
        buffer.markWriteComplete(sn, data.length);
        buffer.getRecentWritten().advanceTail();

        ByteBuffer result = buffer.getWriteReadyData();
        assertEquals(5, result.remaining());

        byte[] readData = new byte[5];
        result.get(readData);
        assertArrayEquals(data, readData);
    }

    @Test
    @DisplayName("getData 测试")
    void testGetData() throws InterruptedException {
        byte[] data = {10, 20, 30, 40, 50};

        long sn = buffer.reserveSpace(data.length);
        buffer.writeRecord(sn, data);
        buffer.markWriteComplete(sn, data.length);

        byte[] result = buffer.getData(sn, data.length);
        assertNotNull(result);
        assertArrayEquals(data, result);
    }

    @Test
    @DisplayName("markPageDirtyComplete 测试")
    void testMarkPageDirtyComplete() throws InterruptedException {
        long sn = buffer.reserveSpace(100);
        buffer.writeRecord(sn, new byte[100]);
        buffer.markWriteComplete(sn, 100);

        // 模拟脏页注册完成 (与 markWriteComplete 使用相同的参数)
        buffer.markPageDirtyComplete(sn, 100);

        // 推进 recentClosed.tail
        buffer.getRecentClosed().advanceTail();

        assertEquals(100, buffer.getDirtyPageLwm());
    }

    @Test
    @DisplayName("waitForFlush 测试 - 已完成")
    @Timeout(1)
    void testWaitForFlushAlreadyDone() throws InterruptedException {
        // flushedSn = 0, targetSn = 0
        assertTrue(buffer.waitForFlush(0, 1_000_000_000L));
    }

    @Test
    @DisplayName("waitForFlush 测试 - 超时")
    @Timeout(2)
    void testWaitForFlushTimeout() throws InterruptedException {
        // 写入但不推进 flushedSn
        long sn = buffer.reserveSpace(100);
        buffer.writeRecord(sn, new byte[100]);
        buffer.markWriteComplete(sn, 100);

        // 等待应该超时
        assertFalse(buffer.waitForFlush(100, 100_000_000L)); // 100ms
    }

    @Test
    @DisplayName("toString 测试")
    void testToString() {
        String str = buffer.toString();
        assertTrue(str.contains("LockFreeRedoLogBuffer"));
        assertTrue(str.contains("capacity=1MB"));
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testConstructorValidation() {
        // capacity 必须是 2 的幂
        assertThrows(IllegalArgumentException.class, () ->
                new LockFreeRedoLogBuffer(1000, 128, 8, 16, 4096));
    }

    @Test
    @DisplayName("reserveSpace 参数验证")
    void testReserveSpaceValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                buffer.reserveSpace(0));

        assertThrows(IllegalArgumentException.class, () ->
                buffer.reserveSpace(-1));

        // 太大
        assertThrows(IllegalArgumentException.class, () ->
                buffer.reserveSpace(buffer.getCapacity()));
    }
}
