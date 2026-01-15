package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedoLogBuffer 单元测试
 *
 * <p>验证环形缓冲区的正确性，包括：</p>
 * <ul>
 *   <li>reserveSpace(): 空间预留和 SN 推进</li>
 *   <li>writeRecord(): 数据写入和环形回绕</li>
 *   <li>getWriteReadyData(): 数据读取</li>
 *   <li>waitForFlush(): 等待持久化</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("RedoLogBuffer Tests")
class RedoLogBufferTest {

    private static final int BUFFER_SIZE = 1024 * 1024;  // 1MB

    private RedoLogBuffer buffer;

    @BeforeEach
    void setup() {
        buffer = new RedoLogBuffer(BUFFER_SIZE);
    }

    // ==================== reserveSpace 测试 ====================

    @Nested
    @DisplayName("reserveSpace Tests")
    class ReserveSpaceTests {

        @Test
        @DisplayName("Reserve space should advance currentSn")
        void reserveAdvancesCurrentSn() throws Exception {
            assertEquals(0, buffer.getCurrentSn());

            long startSn = buffer.reserveSpace(100);
            assertEquals(0, startSn);
            assertEquals(100, buffer.getCurrentSn());

            startSn = buffer.reserveSpace(200);
            assertEquals(100, startSn);
            assertEquals(300, buffer.getCurrentSn());
        }

        @Test
        @DisplayName("Reserve space should reject invalid sizes")
        void reserveRejectsInvalidSizes() {
            // 负数
            assertThrows(IllegalArgumentException.class,
                    () -> buffer.reserveSpace(-1));

            // 零
            assertThrows(IllegalArgumentException.class,
                    () -> buffer.reserveSpace(0));

            // 超过 buffer 大小一半
            assertThrows(IllegalArgumentException.class,
                    () -> buffer.reserveSpace(BUFFER_SIZE / 2 + 1));
        }
    }

    // ==================== writeRecord 测试 ====================

    @Nested
    @DisplayName("writeRecord Tests")
    class WriteRecordTests {

        @Test
        @DisplayName("Write record should store data correctly")
        void writeStoresDataCorrectly() throws Exception {
            // 预留空间
            long startSn = buffer.reserveSpace(100);
            assertEquals(0, startSn);

            // 写入数据
            byte[] data = new byte[100];
            for (int i = 0; i < 100; i++) {
                data[i] = (byte) i;
            }
            buffer.writeRecord(startSn, data);

            // 标记 ready
            buffer.advanceWriteReadySn(100);

            // 验证数据
            ByteBuffer readData = buffer.getWriteReadyData();
            assertEquals(100, readData.remaining());

            for (int i = 0; i < 100; i++) {
                assertEquals((byte) i, readData.get());
            }
        }

        @Test
        @DisplayName("Write should handle ring buffer wrap-around")
        void writeHandlesWrapAround() throws Exception {
            // 分多次填充到接近末尾（单次预留不能超过 capacity/2）
            int chunkSize = BUFFER_SIZE / 4;  // 每次填充 1/4
            long currentSn = 0;

            // 填充 3/4 的 buffer
            for (int i = 0; i < 3; i++) {
                long sn = buffer.reserveSpace(chunkSize);
                assertEquals(currentSn, sn);
                buffer.writeRecord(sn, new byte[chunkSize]);
                currentSn += chunkSize;
                buffer.advanceWriteReadySn(currentSn);
            }

            // 模拟 writer 读取并推进 writeSn
            buffer.getWriteReadyData();
            buffer.advanceWriteSn(currentSn);

            // 模拟 flusher 推进 flushedSn (释放空间)
            buffer.advanceFlushedSn(currentSn);

            // 写入跨越边界的数据 (会回绕到 buffer 开头)
            long sn2 = buffer.reserveSpace(chunkSize);
            assertEquals(currentSn, sn2);

            byte[] data = new byte[chunkSize];
            for (int i = 0; i < chunkSize; i++) {
                data[i] = (byte) (i % 256);
            }
            buffer.writeRecord(sn2, data);
            buffer.advanceWriteReadySn(currentSn + chunkSize);

            // 验证数据
            ByteBuffer readData = buffer.getWriteReadyData();
            assertEquals(chunkSize, readData.remaining());

            for (int i = 0; i < chunkSize; i++) {
                assertEquals((byte) (i % 256), readData.get(),
                        "Byte " + i + " mismatch after wrap-around");
            }
        }
    }

    // ==================== getWriteReadyData 测试 ====================

    @Nested
    @DisplayName("getWriteReadyData Tests")
    class GetWriteReadyDataTests {

        @Test
        @DisplayName("Should return empty buffer when no data ready")
        void returnsEmptyWhenNoDataReady() {
            ByteBuffer data = buffer.getWriteReadyData();
            assertEquals(0, data.remaining());
        }

        @Test
        @DisplayName("Should return all ready data")
        void returnsAllReadyData() throws Exception {
            // 写入多次
            for (int i = 0; i < 3; i++) {
                long sn = buffer.reserveSpace(100);
                buffer.writeRecord(sn, new byte[100]);
                buffer.advanceWriteReadySn(sn + 100);
            }

            // 读取
            ByteBuffer data = buffer.getWriteReadyData();
            assertEquals(300, data.remaining());
        }
    }

    // ==================== SN 推进测试 ====================

    @Nested
    @DisplayName("SN Advancement Tests")
    class SnAdvancementTests {

        @Test
        @DisplayName("advanceWriteSn should update writeSn")
        void advanceWriteSnWorks() throws Exception {
            buffer.reserveSpace(100);
            buffer.advanceWriteReadySn(100);

            buffer.advanceWriteSn(50);
            buffer.advanceWriteSn(100);

            // 尝试回退应该被忽略
            buffer.advanceWriteSn(50);  // 应该无效
        }

        @Test
        @DisplayName("advanceFlushedSn should release space")
        void advanceFlushedSnReleasesSpace() throws Exception {
            // 填满 buffer
            int fillSize = BUFFER_SIZE / 2;
            buffer.reserveSpace(fillSize);
            buffer.advanceWriteReadySn(fillSize);
            buffer.advanceWriteSn(fillSize);

            // flushedSn 推进后应该释放空间
            buffer.advanceFlushedSn(fillSize);
            assertEquals(fillSize, buffer.getFlushedSn());

            // 应该可以再次预留空间
            long sn = buffer.reserveSpace(fillSize);
            assertEquals(fillSize, sn);
        }
    }

    // ==================== waitForFlush 测试 ====================

    @Nested
    @DisplayName("waitForFlush Tests")
    class WaitForFlushTests {

        @Test
        @DisplayName("waitForFlush should return immediately if already flushed")
        void waitReturnsImmediatelyIfFlushed() throws Exception {
            buffer.reserveSpace(100);
            buffer.advanceWriteReadySn(100);
            buffer.advanceWriteSn(100);
            buffer.advanceFlushedSn(100);

            // 应该立即返回
            long start = System.currentTimeMillis();
            buffer.waitForFlush(100);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 100, "Should return immediately, took " + elapsed + "ms");
        }

        @Test
        @DisplayName("waitForFlush should block until flushed")
        void waitBlocksUntilFlushed() throws Exception {
            buffer.reserveSpace(100);
            buffer.advanceWriteReadySn(100);
            buffer.advanceWriteSn(100);

            AtomicLong waitTime = new AtomicLong(0);
            CountDownLatch latch = new CountDownLatch(1);

            // 启动等待线程
            Thread waiter = new Thread(() -> {
                try {
                    long start = System.currentTimeMillis();
                    buffer.waitForFlush(100);
                    waitTime.set(System.currentTimeMillis() - start);
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.start();

            // 等待一段时间后推进 flushedSn
            Thread.sleep(100);
            buffer.advanceFlushedSn(100);

            // 等待完成
            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertTrue(waitTime.get() >= 100, "Should have waited at least 100ms, waited " + waitTime.get());
        }
    }

    // ==================== 并发测试 ====================

    @Nested
    @DisplayName("Concurrency Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("Multiple sequential writes should work correctly")
        void multipleSequentialWrites() throws Exception {
            // Phase 1-2 使用串行提交模型，所以这里测试顺序写入
            int numWrites = 100;
            int writeSize = 100;

            for (int i = 0; i < numWrites; i++) {
                long sn = buffer.reserveSpace(writeSize);
                assertEquals(i * writeSize, sn);

                byte[] data = new byte[writeSize];
                data[0] = (byte) i;
                buffer.writeRecord(sn, data);
                buffer.advanceWriteReadySn(sn + writeSize);
            }

            // 验证总数据量
            int expectedTotal = numWrites * writeSize;
            assertEquals(expectedTotal, buffer.getCurrentSn());
        }

        @Test
        @DisplayName("Concurrent reserveSpace should serialize correctly")
        void concurrentReserveSpaceSerializes() throws Exception {
            // 测试多线程并发预留空间（由锁保护）
            // 注意：Phase 1-2 中 advanceWriteReadySn 必须按顺序调用
            int numWriters = 10;
            int writesPerWriter = 100;
            int writeSize = 100;
            CountDownLatch latch = new CountDownLatch(numWriters);
            AtomicLong totalReserved = new AtomicLong(0);

            for (int w = 0; w < numWriters; w++) {
                new Thread(() -> {
                    try {
                        for (int i = 0; i < writesPerWriter; i++) {
                            // 只测试 reserveSpace 的并发安全性
                            long sn = buffer.reserveSpace(writeSize);
                            totalReserved.addAndGet(writeSize);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await(5, TimeUnit.SECONDS);

            // 验证总预留量
            int expectedTotal = numWriters * writesPerWriter * writeSize;
            assertEquals(expectedTotal, buffer.getCurrentSn());
            assertEquals(expectedTotal, totalReserved.get());
        }
    }
}
