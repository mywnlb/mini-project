package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.buffer.LockFreeRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.buffer.LogBlockFormatter;
import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.redo.lifecycle.BackgroundService;
import cn.zhangyis.minidb.storage.redo.lifecycle.RedoLogOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 无锁模式 Log Writer 后台服务
 *
 * <p>负责将 LockFreeRedoLogBuffer 中的数据写入到 redo log 文件。
 * 与有锁模式不同，无锁模式下需要先推进 recentWritten.tail 获取连续边界。</p>
 *
 * <h2>工作流程</h2>
 * <pre>
 * while (running) {
 *     1. 推进 recentWritten.tail 获取连续边界
 *     2. 检查是否有待写入数据 (bufReadyForWriteSn > writeSn)
 *     3. 如果没有数据，短暂休眠
 *     4. 读取 buffer 中的数据
 *     5. 格式化为 log blocks
 *     6. 写入文件
 *     7. 推进 writeSn
 *     8. 通知 LogWriteNotifier
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LockFreeLogWriter extends BackgroundService {

    private static final Logger logger = LoggerFactory.getLogger(LockFreeLogWriter.class);

    /** 默认检查间隔 (微秒) */
    private static final long DEFAULT_INTERVAL_US = 100;

    /** Buffer */
    private final LockFreeRedoLogBuffer buffer;

    /** 文件集 */
    private final RedoLogFileSet fileSet;

    /** Write Notifier (可选) */
    private final LogWriteNotifier writeNotifier;

    /** 检查间隔 (纳秒) */
    private final long intervalNanos;

    /** 总写入字节数 */
    private long totalBytesWritten = 0;

    /** 总写入次数 */
    private long totalWriteCount = 0;

    /**
     * 创建 LockFreeLogWriter
     *
     * @param buffer        LockFreeRedoLogBuffer
     * @param fileSet       RedoLogFileSet
     * @param writeNotifier LogWriteNotifier (可为 null)
     */
    public LockFreeLogWriter(LockFreeRedoLogBuffer buffer, RedoLogFileSet fileSet,
                             LogWriteNotifier writeNotifier) {
        this(buffer, fileSet, writeNotifier, DEFAULT_INTERVAL_US);
    }

    /**
     * 创建 LockFreeLogWriter
     *
     * @param buffer        LockFreeRedoLogBuffer
     * @param fileSet       RedoLogFileSet
     * @param writeNotifier LogWriteNotifier (可为 null)
     * @param intervalUs    检查间隔 (微秒)
     */
    public LockFreeLogWriter(LockFreeRedoLogBuffer buffer, RedoLogFileSet fileSet,
                             LogWriteNotifier writeNotifier, long intervalUs) {
        super("redo-log-writer", RedoLogOrders.LOG_WRITER);
        this.buffer = buffer;
        this.fileSet = fileSet;
        this.writeNotifier = writeNotifier;
        this.intervalNanos = intervalUs * 1000;
    }

    @Override
    protected void doWork() throws Exception {
        // ===== Step 1: 推进 recentWritten.tail 并通知等待者 =====
        long oldTail = buffer.getRecentWritten().getTail();
        long newTail = buffer.getRecentWritten().advanceTail();

        // 通知等待 recentWritten.tail 推进的线程
        if (newTail > oldTail) {
            buffer.notifyRecentWrittenProgress(oldTail, newTail);
        }

        // ===== Step 2: 获取待写入数据的 SN 范围 =====
        long bufReadyForWriteSn = buffer.getBufReadyForWriteSn();
        long currentWriteSn = buffer.getWriteSn();
        long toWrite = bufReadyForWriteSn - currentWriteSn;

        if (toWrite <= 0) {
            // 没有数据，短暂休眠
            parkNanos(intervalNanos);
            return;
        }

        // ===== Step 3: 执行写入 =====
        writeBatch(currentWriteSn, bufReadyForWriteSn);
    }

    /**
     * 写入一批数据
     */
    private void writeBatch(long startSn, long endSn) throws IOException {
        long writeStart = System.nanoTime();

        // 1. 从 buffer 读取 payload
        ByteBuffer payload = buffer.getWriteReadyData();
        if (payload.remaining() == 0) {
            return;
        }

        int payloadSize = payload.remaining();

        // 2. 检查是否需要处理 partial block
        int offsetInFirstBlock = (int) (startSn % LsnMapper.LOG_BLOCK_DATA_SIZE);
        byte[] existingBlockData = null;

        if (offsetInFirstBlock > 0) {
            long blockStartSn = (startSn / LsnMapper.LOG_BLOCK_DATA_SIZE) * LsnMapper.LOG_BLOCK_DATA_SIZE;
            byte[] prefixData = buffer.getData(blockStartSn, offsetInFirstBlock);

            if (prefixData == null) {
                throw new IOException(String.format(
                        "Cannot read prefix data from buffer. blockStartSn=%d, offsetInFirstBlock=%d",
                        blockStartSn, offsetInFirstBlock));
            }

            existingBlockData = new byte[LsnMapper.OS_FILE_LOG_BLOCK_SIZE];
            System.arraycopy(prefixData, 0, existingBlockData, LsnMapper.LOG_BLOCK_HDR_SIZE, prefixData.length);

            logger.trace("Partial block merge: blockStartSn={}, prefixLen={}", blockStartSn, prefixData.length);
        }

        // 3. 格式化为 log blocks
        ByteBuffer blocks = LogBlockFormatter.format(startSn, payload, existingBlockData);

        // 4. 计算起始 LSN
        long startLsn = LsnMapper.snToBlockLsn(startSn);

        // 5. 写入文件
        fileSet.writeBlocks(startLsn, blocks);

        // 6. 推进 writeSn
        buffer.advanceWriteSn(endSn);

        // 7. 通知 WriteNotifier
        if (writeNotifier != null) {
            writeNotifier.notifyProgress(endSn);
        }

        // 8. 更新统计
        totalBytesWritten += payloadSize;
        totalWriteCount++;

        long elapsed = (System.nanoTime() - writeStart) / 1000;
        logger.debug("LockFreeLogWriter: wrote {} bytes in {}μs, sn: {} -> {}",
                payloadSize, elapsed, startSn, endSn);
    }

    @Override
    protected void doStop() throws Exception {
        // 刷新剩余数据
        buffer.getRecentWritten().advanceTail();
        long bufReadyForWriteSn = buffer.getBufReadyForWriteSn();
        long currentWriteSn = buffer.getWriteSn();

        if (bufReadyForWriteSn > currentWriteSn) {
            logger.info("Flushing remaining {} bytes before shutdown", bufReadyForWriteSn - currentWriteSn);
            writeBatch(currentWriteSn, bufReadyForWriteSn);
        }

        logger.info("LockFreeLogWriter stopped: totalBytesWritten={}, totalWriteCount={}",
                totalBytesWritten, totalWriteCount);
    }

    /**
     * 获取总写入字节数
     */
    public long getTotalBytesWritten() {
        return totalBytesWritten;
    }

    /**
     * 获取总写入次数
     */
    public long getTotalWriteCount() {
        return totalWriteCount;
    }
}
