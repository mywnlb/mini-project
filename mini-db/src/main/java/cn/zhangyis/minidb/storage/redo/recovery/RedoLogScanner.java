package cn.zhangyis.minidb.storage.redo.recovery;

import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.redo.record.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Redo Log Scanner - 扫描 redo log 文件
 *
 * <p>从指定的 LSN 开始扫描 redo log 文件，解析并返回 redo records。
 * 支持跨 log block 和跨文件的连续扫描。</p>
 *
 * <h2>扫描流程</h2>
 * <pre>
 * 1. 从 startLsn 定位到对应的 log block
 * 2. 读取 log block，验证 checksum
 * 3. 从 block 中提取 redo records
 * 4. 处理跨 block 的 record (record 可能跨越多个 blocks)
 * 5. 继续读取下一个 block 直到文件结束或遇到空 block
 * </pre>
 *
 * <h2>使用方式</h2>
 * <pre>
 * RedoLogScanner scanner = new RedoLogScanner(fileSet, checkpointLsn);
 * while (scanner.hasNext()) {
 *     RedoRecord record = scanner.next();
 *     // 处理 record
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoLogScanner implements Iterator<RedoRecord> {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogScanner.class);

    // ==================== 依赖 ====================

    /** Redo Log 文件集 */
    private final RedoLogFileSet fileSet;

    // ==================== 状态 ====================

    /** 当前扫描的 LSN */
    private long currentLsn;

    /** 当前扫描的 SN */
    private long currentSn;

    /** 结束 LSN (用于限制扫描范围) */
    private final long endLsn;

    /** 已解析但未返回的 records 缓冲区 */
    private final List<RedoRecord> pendingRecords = new ArrayList<>();

    /** 当前 pending records 的索引 */
    private int pendingIndex = 0;

    /** 跨 block 的 payload 缓冲区 */
    private ByteBuffer partialPayload = null;

    /** 是否已扫描完成 */
    private boolean exhausted = false;

    /** 扫描统计 */
    private long blocksRead = 0;
    private long recordsParsed = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建 RedoLogScanner
     *
     * @param fileSet  Redo Log 文件集
     * @param startLsn 起始 LSN (通常是 checkpoint LSN)
     */
    public RedoLogScanner(RedoLogFileSet fileSet, long startLsn) {
        this(fileSet, startLsn, Long.MAX_VALUE);
    }

    /**
     * 创建 RedoLogScanner (带结束 LSN)
     *
     * @param fileSet  Redo Log 文件集
     * @param startLsn 起始 LSN
     * @param endLsn   结束 LSN (exclusive)
     */
    public RedoLogScanner(RedoLogFileSet fileSet, long startLsn, long endLsn) {
        this.fileSet = fileSet;
        this.currentLsn = alignToBlockStart(startLsn);
        this.currentSn = LsnMapper.lsnToSn(this.currentLsn);
        this.endLsn = endLsn;

        logger.info("RedoLogScanner created: startLsn={}, endLsn={}", startLsn, endLsn);
    }

    // ==================== Iterator 实现 ====================

    @Override
    public boolean hasNext() {
        if (exhausted) {
            return false;
        }

        // 检查 pending records
        if (pendingIndex < pendingRecords.size()) {
            return true;
        }

        // 尝试读取更多 records
        try {
            readNextBatch();
            return pendingIndex < pendingRecords.size();
        } catch (Exception e) {
            logger.warn("Error reading redo log: {}", e.getMessage());
            exhausted = true;
            return false;
        }
    }

    @Override
    public RedoRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more redo records");
        }

        RedoRecord record = pendingRecords.get(pendingIndex++);

        // 清理已消费的 records
        if (pendingIndex >= pendingRecords.size()) {
            pendingRecords.clear();
            pendingIndex = 0;
        }

        return record;
    }

    // ==================== 核心扫描逻辑 ====================

    /**
     * 读取下一批 records
     */
    private void readNextBatch() throws IOException {
        pendingRecords.clear();
        pendingIndex = 0;

        while (pendingRecords.isEmpty() && !exhausted) {
            if (currentLsn >= endLsn) {
                exhausted = true;
                break;
            }

            // 读取一个 log block
            ByteBuffer blockData = readLogBlock(currentLsn);
            if (blockData == null) {
                exhausted = true;
                break;
            }

            blocksRead++;

            // 解析 block 中的 records
            parseLogBlock(blockData);

            // 移动到下一个 block
            currentLsn += LsnMapper.OS_FILE_LOG_BLOCK_SIZE;
            currentSn += LsnMapper.LOG_BLOCK_DATA_SIZE;
        }
    }

    /**
     * 读取 log block
     *
     * @param lsn block 起始 LSN
     * @return block 数据，或 null 如果读取失败/block 为空
     */
    private ByteBuffer readLogBlock(long lsn) throws IOException {
        try {
            byte[] block = fileSet.readBlock(lsn);
            if (block == null) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.wrap(block);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);

            // 验证 block (检查是否为空或无效)
            if (!isValidBlock(buffer)) {
                logger.debug("Invalid or empty block at LSN {}", lsn);
                return null;
            }

            return buffer;
        } catch (Exception e) {
            logger.warn("Failed to read block at LSN {}: {}", lsn, e.getMessage());
            return null;
        }
    }

    /**
     * 验证 log block
     */
    private boolean isValidBlock(ByteBuffer block) {
        if (block.remaining() < LsnMapper.OS_FILE_LOG_BLOCK_SIZE) {
            return false;
        }

        block.mark();

        // 读取 header
        int blockNo = block.getInt();
        short dataLen = block.getShort();

        block.reset();

        // 检查是否为空 block (dataLen == 0 且 blockNo == 0)
        if (blockNo == 0 && dataLen == 0) {
            return false;
        }

        // 检查 dataLen 是否合理
        return dataLen >= 0 && dataLen <= LsnMapper.LOG_BLOCK_DATA_SIZE;
    }

    /**
     * 解析 log block 中的 records
     */
    private void parseLogBlock(ByteBuffer block) {
        // 跳过 header
        block.position(LsnMapper.LOG_BLOCK_HDR_SIZE);

        // 读取 data length
        block.position(4);  // block_no 后面
        short dataLen = block.getShort();
        block.position(LsnMapper.LOG_BLOCK_HDR_SIZE);

        if (dataLen <= 0) {
            return;
        }

        // 提取 payload
        byte[] payload = new byte[dataLen];
        block.get(payload);

        // 如果有 partial payload，先合并
        ByteBuffer dataBuffer;
        if (partialPayload != null && partialPayload.hasRemaining()) {
            int partialLen = partialPayload.remaining();
            byte[] combined = new byte[partialLen + payload.length];
            partialPayload.get(combined, 0, partialLen);
            System.arraycopy(payload, 0, combined, partialLen, payload.length);
            dataBuffer = ByteBuffer.wrap(combined);
            partialPayload = null;
        } else {
            dataBuffer = ByteBuffer.wrap(payload);
        }

        // 解析 records
        parseRecords(dataBuffer);
    }

    /**
     * 解析 records 从 payload
     */
    private void parseRecords(ByteBuffer data) {
        while (data.hasRemaining()) {
            int startPos = data.position();

            try {
                RedoRecord record = parseOneRecord(data);
                if (record != null) {
                    pendingRecords.add(record);
                    recordsParsed++;
                }
            } catch (Exception e) {
                // 解析失败，可能是 record 跨 block
                // 保存剩余数据到 partialPayload
                int remaining = data.remaining();
                if (remaining > 0) {
                    data.position(startPos);
                    byte[] partial = new byte[data.remaining()];
                    data.get(partial);
                    partialPayload = ByteBuffer.wrap(partial);
                }
                break;
            }
        }
    }

    /**
     * 解析单个 record
     */
    private RedoRecord parseOneRecord(ByteBuffer data) {
        if (!data.hasRemaining()) {
            return null;
        }

        int startPos = data.position();
        byte typeByte = data.get();
        int type = typeByte & 0xFF;

        // 根据类型解析
        if (type == RedoRecordType.MLOG_MULTI_REC_END.getValue()) {
            return new MultiRecEndRecord();
        } else if (type == RedoRecordType.MLOG_WRITE_BYTES.getValue()) {
            data.position(startPos);
            return parseWriteBytesRecord(data);
        } else if (type == RedoRecordType.MLOG_FULL_PAGE.getValue()) {
            data.position(startPos);
            return parseFullPageRecord(data);
        } else {
            // 未知类型，跳过
            logger.warn("Unknown redo record type: {}", type);
            return null;
        }
    }

    /**
     * 解析 WriteBytesRecord
     */
    private WriteBytesRecord parseWriteBytesRecord(ByteBuffer data) {
        // 检查最小长度: type(1) + spaceId(4) + pageNo(4) + dataLen(2) + offset(2) + length(2) = 15
        if (data.remaining() < 15) {
            throw new IllegalStateException("Insufficient data for WriteBytesRecord");
        }

        return WriteBytesRecord.deserialize(data);
    }

    /**
     * 解析 FullPageRecord
     */
    private FullPageRecord parseFullPageRecord(ByteBuffer data) {
        // 检查最小长度: type(1) + spaceId(4) + pageNo(4) + dataLen(2) + pageData(16384) = 16395
        if (data.remaining() < 11) {
            throw new IllegalStateException("Insufficient data for FullPageRecord");
        }

        return FullPageRecord.deserialize(data);
    }

    // ==================== 辅助方法 ====================

    /**
     * 对齐 LSN 到 block 边界
     */
    private long alignToBlockStart(long lsn) {
        return (lsn / LsnMapper.OS_FILE_LOG_BLOCK_SIZE) * LsnMapper.OS_FILE_LOG_BLOCK_SIZE;
    }

    /**
     * 获取当前扫描的 LSN
     */
    public long getCurrentLsn() {
        return currentLsn;
    }

    /**
     * 获取扫描统计
     */
    public String getStats() {
        return String.format("blocksRead=%d, recordsParsed=%d", blocksRead, recordsParsed);
    }

    /**
     * 重置扫描器到指定 LSN
     */
    public void reset(long newStartLsn) {
        this.currentLsn = alignToBlockStart(newStartLsn);
        this.currentSn = LsnMapper.lsnToSn(this.currentLsn);
        this.pendingRecords.clear();
        this.pendingIndex = 0;
        this.partialPayload = null;
        this.exhausted = false;
        this.blocksRead = 0;
        this.recordsParsed = 0;

        logger.info("Scanner reset to LSN {}", newStartLsn);
    }
}
