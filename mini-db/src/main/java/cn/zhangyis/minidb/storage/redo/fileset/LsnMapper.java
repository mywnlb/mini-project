package cn.zhangyis.minidb.storage.redo.fileset;

/**
 * LSN ↔ SN ↔ File Offset 映射器
 *
 * <p>实现 InnoDB Redo Log 的三层模型：
 * <ul>
 *   <li><b>SN (Sequence Number)</b>: 纯 redo payload 字节偏移，不包含 log block 开销</li>
 *   <li><b>LSN (Log Sequence Number)</b>: 物理字节偏移，包含 log block header/trailer</li>
 *   <li><b>File Offset</b>: 实际文件中的字节位置</li>
 * </ul>
 *
 * <h2>核心设计原则</h2>
 * <p><b>关键</b>: SN 是逻辑增量，LSN 是物理定位。只有 LSN 用于文件 I/O。</p>
 *
 * <h2>Log Block 结构 (512 bytes)</h2>
 * <pre>
 * ┌─────────────────────────────┐
 * │ Header (12 bytes)           │
 * ├─────────────────────────────┤
 * │ Data (496 bytes)            │  ← SN 指向这里的 payload
 * ├─────────────────────────────┤
 * │ Trailer (4 bytes)           │
 * └─────────────────────────────┘
 * </pre>
 *
 * <h2>转换公式</h2>
 * <pre>
 * SN → LSN:
 *   lsn = (sn / 496) * 512 + (sn % 496) + 12
 *
 * LSN → SN (需要吸附 header/trailer):
 *   block_no = lsn / 512
 *   offset_in_block = lsn % 512
 *   if offset_in_block < 12:           // 落在 header
 *     sn = block_no * 496
 *   else if offset_in_block >= 508:    // 落在 trailer
 *     sn = (block_no + 1) * 496
 *   else:                              // 落在 data
 *     sn = block_no * 496 + (offset_in_block - 12)
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LsnMapper {

    // ==================== Log Block 常量 ====================

    /** Log block 大小 (512 bytes) */
    public static final int OS_FILE_LOG_BLOCK_SIZE = 512;

    /** Log block header 大小 (12 bytes) */
    public static final int LOG_BLOCK_HDR_SIZE = 12;

    /** Log block trailer 大小 (4 bytes) */
    public static final int LOG_BLOCK_TRL_SIZE = 4;

    /** Log block data 区域大小 (496 bytes) */
    public static final int LOG_BLOCK_DATA_SIZE = 496;  // 512 - 12 - 4

    // ==================== 文件布局常量 ====================

    /** Checkpoint header 大小 (2KB = 4 blocks，必须 block 对齐) */
    public static final int CHECKPOINT_HEADER_SIZE = 2048;

    /** 日志文件数量 */
    public static final int LOG_FILE_COUNT = 2;

    // ==================== SN ↔ LSN 转换 ====================

    /**
     * SN → LSN 转换
     *
     * <p>将纯 payload 偏移转换为包含 block 开销的物理偏移。</p>
     *
     * <h3>算法</h3>
     * <pre>
     * 1. 计算 SN 属于第几个 block: block_no = sn / 496
     * 2. 计算 SN 在 data 区的偏移: data_offset = sn % 496
     * 3. LSN = block_no * 512 + 12 + data_offset
     * </pre>
     *
     * <h3>保证</h3>
     * <p>返回的 LSN 总是在某个 block 的 data 区域内 (offset 12-507)。</p>
     *
     * @param sn Sequence Number (纯 payload 偏移)
     * @return LSN (物理偏移，包含 block 开销)
     */
    public static long snToLsn(long sn) {
        long blockNo = sn / LOG_BLOCK_DATA_SIZE;
        long dataOffset = sn % LOG_BLOCK_DATA_SIZE;

        long lsn = blockNo * OS_FILE_LOG_BLOCK_SIZE + LOG_BLOCK_HDR_SIZE + dataOffset;

        // 验证 LSN 在 data 区域内
        assert isLsnInDataArea(lsn) : "snToLsn result must be in data area: lsn=" + lsn;

        return lsn;
    }

    /**
     * LSN → SN 转换 (带吸附)
     *
     * <p>将物理偏移转换为 payload 偏移。如果 LSN 落在 header/trailer，
     * 吸附到最近的 data 区域边界。</p>
     *
     * <h3>吸附规则</h3>
     * <ul>
     *   <li>LSN 在 header (0-11) → 吸附到本 block data 起点</li>
     *   <li>LSN 在 data (12-507) → 正常转换</li>
     *   <li>LSN 在 trailer (508-511) → 吸附到下一 block data 起点</li>
     * </ul>
     *
     * @param lsn Log Sequence Number (物理偏移)
     * @return SN (纯 payload 偏移)
     */
    public static long lsnToSn(long lsn) {
        long blockNo = lsn / OS_FILE_LOG_BLOCK_SIZE;
        long offsetInBlock = lsn % OS_FILE_LOG_BLOCK_SIZE;

        long snBase = blockNo * LOG_BLOCK_DATA_SIZE;

        if (offsetInBlock < LOG_BLOCK_HDR_SIZE) {
            // 落在 header，吸附到本 block data 起点
            return snBase;
        } else if (offsetInBlock >= OS_FILE_LOG_BLOCK_SIZE - LOG_BLOCK_TRL_SIZE) {
            // 落在 trailer，吸附到下一 block data 起点
            return snBase + LOG_BLOCK_DATA_SIZE;
        } else {
            // 落在 data 区域，正常转换
            return snBase + (offsetInBlock - LOG_BLOCK_HDR_SIZE);
        }
    }

    /**
     * 检查 LSN 是否在 data 区域内
     *
     * @param lsn Log Sequence Number
     * @return true 如果 LSN 在某个 block 的 data 区域 (offset 12-507)
     */
    public static boolean isLsnInDataArea(long lsn) {
        long offsetInBlock = lsn % OS_FILE_LOG_BLOCK_SIZE;
        return offsetInBlock >= LOG_BLOCK_HDR_SIZE
                && offsetInBlock < OS_FILE_LOG_BLOCK_SIZE - LOG_BLOCK_TRL_SIZE;
    }

    /**
     * 检查 LSN 是否 block 对齐 (LSN % 512 == 0)
     *
     * @param lsn Log Sequence Number
     * @return true 如果 LSN 是 block 边界
     */
    public static boolean isBlockAligned(long lsn) {
        return lsn % OS_FILE_LOG_BLOCK_SIZE == 0;
    }

    /**
     * SN → Block-aligned LSN 转换
     *
     * <p>将 SN 转换为对应 block 的起始 LSN (block 对齐)。
     * 这用于写入 log blocks 到文件时定位起始位置。</p>
     *
     * <h3>算法</h3>
     * <pre>
     * blockNo = sn / 496
     * blockLsn = blockNo * 512
     * </pre>
     *
     * @param sn Sequence Number
     * @return Block 对齐的 LSN (总是 512 的倍数)
     */
    public static long snToBlockLsn(long sn) {
        long blockNo = sn / LOG_BLOCK_DATA_SIZE;
        return blockNo * OS_FILE_LOG_BLOCK_SIZE;
    }

    /**
     * 将 LSN 向上取整到下一个 block 边界
     *
     * @param lsn Log Sequence Number
     * @return 大于等于 lsn 的最小 block 对齐 LSN
     */
    public static long alignToNextBlock(long lsn) {
        long remainder = lsn % OS_FILE_LOG_BLOCK_SIZE;
        if (remainder == 0) {
            return lsn;
        }
        return lsn + (OS_FILE_LOG_BLOCK_SIZE - remainder);
    }

    // ==================== LSN ↔ File Position 转换 ====================

    /**
     * LSN → File Position 转换
     *
     * <p>将全局 LSN 映射到具体的 (fileIndex, offsetInFile)。</p>
     *
     * <h3>文件布局</h3>
     * <pre>
     * ib_logfile0: [Checkpoint Header 2KB] [Data Area ...]
     * ib_logfile1: [Checkpoint Header 2KB] [Data Area ...]
     * </pre>
     *
     * <h3>约束</h3>
     * <ul>
     *   <li>fileSize 必须是 512 的倍数</li>
     *   <li>CHECKPOINT_HEADER_SIZE 必须是 512 的倍数</li>
     *   <li>Log block 不能跨文件边界</li>
     * </ul>
     *
     * @param lsn Log Sequence Number
     * @param fileSize 单个文件大小 (bytes)
     * @return FilePosition (fileIndex, offsetInFile)
     */
    public static FilePosition lsnToFilePosition(long lsn, long fileSize) {
        // 验证对齐约束
        assert fileSize % OS_FILE_LOG_BLOCK_SIZE == 0
                : "fileSize must be block-aligned: " + fileSize;
        assert CHECKPOINT_HEADER_SIZE % OS_FILE_LOG_BLOCK_SIZE == 0
                : "CHECKPOINT_HEADER_SIZE must be block-aligned";

        // 计算每个文件的可用空间
        long usablePerFile = fileSize - CHECKPOINT_HEADER_SIZE;

        // 计算总容量 (2 个文件循环)
        long totalCapacity = 2 * usablePerFile;

        // 对 capacity 取模 (环形)
        long logicalOffset = lsn % totalCapacity;

        // 确定文件索引 (0 或 1)
        int fileIndex = (int) (logicalOffset / usablePerFile);

        // 计算文件内偏移 (跳过 header)
        long offsetInUsable = logicalOffset % usablePerFile;
        long offsetInFile = CHECKPOINT_HEADER_SIZE + offsetInUsable;

        // 验证结果在合法范围内
        assert fileIndex >= 0 && fileIndex < LOG_FILE_COUNT
                : "fileIndex out of range: " + fileIndex;
        assert offsetInFile >= CHECKPOINT_HEADER_SIZE && offsetInFile < fileSize
                : "offsetInFile out of range: " + offsetInFile;

        return new FilePosition(fileIndex, offsetInFile);
    }

    /**
     * 检查写入是否需要切换文件
     *
     * <p>如果当前位置写入 size 字节后会超出文件边界，需要切换到下一个文件。</p>
     *
     * @param currentLsn 当前 LSN
     * @param size 要写入的字节数
     * @param fileSize 单个文件大小
     * @return true 如果需要切换文件
     */
    public static boolean needSwitchFile(long currentLsn, int size, long fileSize) {
        FilePosition pos = lsnToFilePosition(currentLsn, fileSize);
        long remaining = fileSize - pos.offsetInFile();
        return remaining < size;
    }

    /**
     * 获取下一个文件的起始 LSN
     *
     * <p>用于在文件尾填充 padding，跳到下一个文件的起始位置。</p>
     *
     * @param currentLsn 当前 LSN
     * @param fileSize 单个文件大小
     * @return 下一个文件起始的 LSN
     */
    public static long getNextFileLsn(long currentLsn, long fileSize) {
        FilePosition pos = lsnToFilePosition(currentLsn, fileSize);
        long remaining = fileSize - pos.offsetInFile();
        return currentLsn + remaining;
    }

    // ==================== File Position 记录 ====================

    /**
     * 文件位置
     *
     * @param fileIndex 文件索引 (0 或 1)
     * @param offsetInFile 文件内偏移 (bytes)
     */
    public record FilePosition(int fileIndex, long offsetInFile) {
        public FilePosition {
            assert fileIndex >= 0 && fileIndex < LOG_FILE_COUNT
                    : "fileIndex must be 0 or 1: " + fileIndex;
            assert offsetInFile >= 0
                    : "offsetInFile must be non-negative: " + offsetInFile;
        }
    }
}
