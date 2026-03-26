package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.redo.buffer.LockFreeRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RedoLogBufferInvariantTest {

    @Test
    void lsnMapper_examplesAndBoundariesStayAligned() {
        assertEquals(507L, LsnMapper.snToLsn(495));
        assertEquals(524L, LsnMapper.snToLsn(496));

        assertEquals(0L, LsnMapper.lsnToSn(0));
        assertEquals(0L, LsnMapper.lsnToSn(11));
        assertEquals(0L, LsnMapper.lsnToSn(12));
        assertEquals(496L, LsnMapper.lsnToSn(508));

        assertTrue(LsnMapper.isLsnInDataArea(507));
        assertFalse(LsnMapper.isLsnInDataArea(508));
    }

    @Test
    void lockFreeRedoBuffer_closesWriteHolesAndReadsWrappedData() throws Exception {
        // 放大 LinkBuf coverage，避免 recentWritten/recentClosed 背压掩盖本用例要验证的跨环写入语义。
        LockFreeRedoLogBuffer buffer = new LockFreeRedoLogBuffer(1024, 256, 8, 8, 64);

        long first = buffer.reserveSpace(256);
        long second = buffer.reserveSpace(256);
        long third = buffer.reserveSpace(384);

        assertEquals(0L, first);
        assertEquals(256L, second);
        assertEquals(512L, third);

        byte[] secondData = filledBytes(256, 0x22);
        byte[] thirdData = filledBytes(384, 0x33);
        buffer.writeRecord(second, secondData);
        buffer.writeRecord(third, thirdData);
        buffer.markWriteComplete(second, secondData.length);
        buffer.markWriteComplete(third, thirdData.length);

        assertEquals(0L, buffer.getRecentWritten().advanceTail(),
                "前序空洞未闭合前 recentWritten.tail 不能前进");

        byte[] firstData = filledBytes(256, 0x11);
        buffer.writeRecord(first, firstData);
        buffer.markWriteComplete(first, firstData.length);

        assertEquals(896L, buffer.getRecentWritten().advanceTail());

        buffer.advanceWriteSn(896);
        buffer.advanceFlushedSn(512);
        buffer.notifyFlushProgress(0, 512);

        long wrapped = buffer.reserveSpace(256);
        assertEquals(896L, wrapped, "flush 后的新记录应从 ring 尾部开始并跨边界回绕");

        byte[] wrappedData = filledBytes(256, 0x44);
        buffer.writeRecord(wrapped, wrappedData);
        buffer.markWriteComplete(wrapped, wrappedData.length);

        assertEquals(1152L, buffer.getRecentWritten().advanceTail());
        assertArrayEquals(wrappedData, buffer.getData(wrapped, wrappedData.length),
                "跨环边界写入后的数据必须连续可读");
    }

    @Test
    void reserveSpace_waitsBeforeReusingUnflushedRingBytes() throws Exception {
        // 放大 LinkBuf coverage，只保留“未 flush 数据不可复用”这一条背压路径。
        LockFreeRedoLogBuffer buffer = new LockFreeRedoLogBuffer(1024, 256, 8, 8, 64);

        long first = buffer.reserveSpace(512);
        buffer.writeRecord(first, filledBytes(512, 0x41));
        buffer.markWriteComplete(first, 512);
        buffer.getRecentWritten().advanceTail();
        buffer.advanceWriteSn(512);

        long second = buffer.reserveSpace(512);
        buffer.writeRecord(second, filledBytes(512, 0x42));
        buffer.markWriteComplete(second, 512);
        buffer.getRecentWritten().advanceTail();
        buffer.advanceWriteSn(1024);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Long> waiter = executor.submit(() -> buffer.reserveSpace(8));

            Thread.sleep(50);
            assertFalse(waiter.isDone(), "未 flush 前不允许覆盖环形缓冲区中的旧数据");

            buffer.advanceFlushedSn(1024);
            buffer.notifyFlushProgress(0, 1024);

            assertEquals(1024L, waiter.get(1, TimeUnit.SECONDS));
        }

        assertTrue(buffer.getBufferFullWaitCount() >= 1,
                "发生 ring reuse 背压时应记录等待次数");
    }

    private byte[] filledBytes(int length, int value) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) value);
        return data;
    }
}
