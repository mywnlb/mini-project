package cn.zhangyis.minidb.storage.redo.buffer;

import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Log Block 格式化器 (SN → LSN 转换的唯一入口)
 *
 * <p>将 RedoLogBuffer 中的纯 payload 数据 (SN) 转换为带有 log block 结构的数据 (LSN)。
 * 这是 SN/LSN 分离的关键组件。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>SN → LSN 转换</b>: 唯一执行此转换的地方</li>
 *   <li><b>添加 Block Header</b>: block_no, data_len, first_rec_offset, checksum</li>
 *   <li><b>添加 Block Trailer</b>: checksum copy</li>
 *   <li><b>处理跨 Block</b>: payload 可能跨越多个 log block</li>
 * </ul>
 *
 * <h2>Log Block 结构 (512 bytes)</h2>
 * <pre>
 * ┌────────────────────────────────────────────┐
 * │ Header (12 bytes)                          │
 * │  - block_no (4B): 全局 block 序号         │
 * │  - data_len (2B): 本 block 中的数据长度   │
 * │  - first_rec_offset (2B): 第一条完整 record│
 * │  - checksum (4B): header + data 的 CRC32  │
 * ├────────────────────────────────────────────┤
 * │ Data (496 bytes)                           │  ← payload 填充到这里
 * │  - redo records...                         │
 * │  - 未用部分填 0                            │
 * ├────────────────────────────────────────────┤
 * │ Trailer (4 bytes)                          │
 * │  - checksum (4B): 与 header 中相同        │
 * └────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>转换示例</h2>
 * <pre>
 * 输入 (SN 数据):
 *   startSn = 1000
 *   payload = 600 bytes
 *
 * 输出 (LSN blocks):
 *   Block 2 (sn 992-1487):
 *     - Header: block_no=2, data_len=496
 *     - Data: sn 992-1487 (payload 的 992-1487 部分)
 *     - Trailer: checksum
 *
 *   Block 3 (sn 1488-1983):
 *     - Header: block_no=3, data_len=112 (1000+600-1488)
 *     - Data: sn 1488-1599 (payload 的剩余部分) + padding
 *     - Trailer: checksum
 *
 * LSN 计算:
 *   block 2 起始 LSN = 2 * 512 = 1024
 *   block 3 起始 LSN = 3 * 512 = 1536
 * </pre>
 *
 * <h2>Phase 1-2 简化</h2>
 * <ul>
 *   <li>first_rec_offset: 暂时设为 0 (不解析 record 边界)</li>
 *   <li>顺序格式化: 不处理并发</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LogBlockFormatter {

    private static final Logger logger = LoggerFactory.getLogger(LogBlockFormatter.class);

    // ==================== Log Block 结构常量 ====================

    /** Block 总大小 (512 bytes) */
    private static final int BLOCK_SIZE = LsnMapper.OS_FILE_LOG_BLOCK_SIZE;

    /** Header 大小 (12 bytes) */
    private static final int HEADER_SIZE = LsnMapper.LOG_BLOCK_HDR_SIZE;

    /** Data 大小 (496 bytes) */
    private static final int DATA_SIZE = LsnMapper.LOG_BLOCK_DATA_SIZE;

    /** Trailer 大小 (4 bytes) */
    private static final int TRAILER_SIZE = LsnMapper.LOG_BLOCK_TRL_SIZE;

    // ==================== Header 偏移 ====================

    private static final int OFFSET_BLOCK_NO = 0;           // block 序号 (4B)
    private static final int OFFSET_DATA_LEN = 4;           // data 长度 (2B)
    private static final int OFFSET_FIRST_REC_OFFSET = 6;   // 首条 record 偏移 (2B)
    private static final int OFFSET_CHECKSUM = 8;           // checksum (4B)

    // ==================== 核心方法: SN → LSN 转换 ====================

    /**
     * 格式化 payload 为 log blocks (SN → LSN 转换)
     *
     * <p>这是系统中唯一执行 SN → LSN 转换的地方。</p>
     *
     * <h3>算法</h3>
     * <pre>
     * 1. 确定 startSn 对应的起始 block
     * 2. 填充第一个 block 的剩余空间
     * 3. 后续 payload 每 496 bytes 填充一个完整 block
     * 4. 最后一个 block 可能部分填充
     * 5. 为每个 block 添加 header + trailer
     * </pre>
     *
     * @param startSn 数据的起始 SN
     * @param payload 纯 redo payload (不含 block 结构)
     * @return 格式化后的 log blocks (含 header/trailer)
     */
    public static ByteBuffer format(long startSn, ByteBuffer payload) {
        if (payload == null || payload.remaining() == 0) {
            return ByteBuffer.allocate(0);
        }

        int payloadSize = payload.remaining();
        logger.trace("Formatting payload: startSn={}, payloadSize={}", startSn, payloadSize);

        // 计算需要的 block 数量
        long startBlockNo = startSn / DATA_SIZE;
        int offsetInFirstBlock = (int) (startSn % DATA_SIZE);

        int firstBlockSpace = DATA_SIZE - offsetInFirstBlock;
        int remainingAfterFirstBlock = Math.max(0, payloadSize - firstBlockSpace);
        int additionalBlocks = (remainingAfterFirstBlock + DATA_SIZE - 1) / DATA_SIZE;
        int totalBlocks = 1 + additionalBlocks;

        // 分配输出缓冲区
        ByteBuffer output = ByteBuffer.allocate(totalBlocks * BLOCK_SIZE);

        // 逐 block 格式化
        long currentBlockNo = startBlockNo;
        int payloadPos = 0;

        for (int i = 0; i < totalBlocks; i++) {
            int dataStartOffset = (i == 0) ? offsetInFirstBlock : 0;
            int dataLen = Math.min(DATA_SIZE - dataStartOffset, payloadSize - payloadPos);

            // 创建 block
            byte[] block = new byte[BLOCK_SIZE];

            // 1. 填充 data 区域
            payload.get(block, HEADER_SIZE + dataStartOffset, dataLen);
            payloadPos += dataLen;

            // 2. 构建 header
            writeHeader(block, currentBlockNo, dataStartOffset + dataLen);

            // 3. 构建 trailer
            writeTrailer(block);

            // 4. 写入输出
            output.put(block);

            currentBlockNo++;
        }

        output.flip();
        logger.trace("Formatted {} blocks: startBlockNo={}, endBlockNo={}",
                totalBlocks, startBlockNo, startBlockNo + totalBlocks - 1);

        return output;
    }

    // ==================== 辅助方法: Header/Trailer ====================

    /**
     * 写入 log block header (12 bytes)
     *
     * <pre>
     * Offset  Size  Field
     * ------  ----  -----
     *   0      4    block_no (big endian，方便调试)
     *   4      2    data_len (本 block 中的有效数据长度)
     *   6      2    first_rec_offset (Phase 1-2: 暂设为 0)
     *   8      4    checksum (CRC32 of header[0-7] + data + trailer[0-3])
     * </pre>
     *
     * @param block 目标 block (512 bytes)
     * @param blockNo block 序号
     * @param dataLen 本 block 的有效数据长度 (0-496)
     */
    private static void writeHeader(byte[] block, long blockNo, int dataLen) {
        ByteBuffer header = ByteBuffer.wrap(block, 0, HEADER_SIZE);

        // 1. block_no (4 bytes, big endian for readability)
        header.putInt((int) blockNo);

        // 2. data_len (2 bytes, little endian)
        header.putShort(Short.reverseBytes((short) dataLen));

        // 3. first_rec_offset (2 bytes, Phase 1-2: 0)
        header.putShort(Short.reverseBytes((short) 0));

        // 4. checksum (4 bytes, 稍后计算)
        header.putInt(0);  // placeholder

        // 5. 计算 checksum (header[0-7] + data)
        CRC32 crc = new CRC32();
        crc.update(block, 0, 8);  // header 前 8 字节
        crc.update(block, HEADER_SIZE, DATA_SIZE);  // data 区域

        long checksumValue = crc.getValue();

        // 6. 写入 checksum
        header.putInt(OFFSET_CHECKSUM, Integer.reverseBytes((int) checksumValue));
    }

    /**
     * 写入 log block trailer (4 bytes)
     *
     * <pre>
     * Offset  Size  Field
     * ------  ----  -----
     *   0      4    checksum (与 header 中相同)
     * </pre>
     *
     * @param block 目标 block (512 bytes)
     */
    private static void writeTrailer(byte[] block) {
        // 读取 header 中的 checksum
        ByteBuffer header = ByteBuffer.wrap(block, 0, HEADER_SIZE);
        int checksum = header.getInt(OFFSET_CHECKSUM);

        // 写入 trailer
        ByteBuffer trailer = ByteBuffer.wrap(block, HEADER_SIZE + DATA_SIZE, TRAILER_SIZE);
        trailer.putInt(checksum);
    }

    // ==================== 辅助方法: 解析 (用于恢复) ====================

    /**
     * 验证 log block 的完整性
     *
     * <p>检查 header 和 trailer 的 checksum 是否一致。</p>
     *
     * @param block log block (512 bytes)
     * @return true 如果 checksum 正确
     */
    public static boolean verifyChecksum(byte[] block) {
        if (block.length != BLOCK_SIZE) {
            return false;
        }

        // 读取 header checksum
        ByteBuffer header = ByteBuffer.wrap(block, 0, HEADER_SIZE);
        int headerChecksum = header.getInt(OFFSET_CHECKSUM);

        // 读取 trailer checksum
        ByteBuffer trailer = ByteBuffer.wrap(block, HEADER_SIZE + DATA_SIZE, TRAILER_SIZE);
        int trailerChecksum = trailer.getInt();

        // 验证 header 和 trailer 一致
        if (headerChecksum != trailerChecksum) {
            logger.warn("Checksum mismatch: header={}, trailer={}", headerChecksum, trailerChecksum);
            return false;
        }

        // 重新计算 checksum
        CRC32 crc = new CRC32();
        crc.update(block, 0, 8);  // header 前 8 字节
        crc.update(block, HEADER_SIZE, DATA_SIZE);  // data 区域

        long expectedChecksum = crc.getValue();

        if ((int) expectedChecksum != Integer.reverseBytes(headerChecksum)) {
            logger.warn("Checksum verification failed: expected={}, actual={}",
                    expectedChecksum, Integer.reverseBytes(headerChecksum));
            return false;
        }

        return true;
    }

    /**
     * 从 log block 提取 payload 数据
     *
     * <p>用于恢复时从 log block 中提取纯 redo records。</p>
     *
     * @param block log block (512 bytes)
     * @return payload 数据 (去除 header/trailer)
     */
    public static ByteBuffer extractPayload(byte[] block) {
        if (block.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Invalid block size: " + block.length);
        }

        // 读取 data_len
        ByteBuffer header = ByteBuffer.wrap(block, 0, HEADER_SIZE);
        int dataLen = Short.reverseBytes(header.getShort(OFFSET_DATA_LEN)) & 0xFFFF;

        if (dataLen < 0 || dataLen > DATA_SIZE) {
            throw new IllegalArgumentException("Invalid data_len: " + dataLen);
        }

        // 提取 data 区域
        byte[] payload = new byte[dataLen];
        System.arraycopy(block, HEADER_SIZE, payload, 0, dataLen);

        return ByteBuffer.wrap(payload);
    }

    /**
     * 读取 block 序号
     *
     * @param block log block (512 bytes)
     * @return block_no
     */
    public static long getBlockNo(byte[] block) {
        ByteBuffer header = ByteBuffer.wrap(block, 0, HEADER_SIZE);
        return header.getInt(OFFSET_BLOCK_NO) & 0xFFFFFFFFL;
    }

    /**
     * 读取 data 长度
     *
     * @param block log block (512 bytes)
     * @return data_len (0-496)
     */
    public static int getDataLen(byte[] block) {
        ByteBuffer header = ByteBuffer.wrap(block, 0, HEADER_SIZE);
        return Short.reverseBytes(header.getShort(OFFSET_DATA_LEN)) & 0xFFFF;
    }
}
