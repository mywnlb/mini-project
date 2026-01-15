package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;

/**
 * Redo Log 配置
 *
 * <p>定义 Redo Log 子系统的所有配置参数。</p>
 *
 * <h2>配置分类</h2>
 * <ul>
 *   <li><b>文件配置</b>: 文件数量、大小、路径</li>
 *   <li><b>Buffer 配置</b>: Log Buffer 大小</li>
 *   <li><b>后台线程配置</b>: Writer/Flusher 间隔</li>
 *   <li><b>Flush 策略</b>: innodb_flush_log_at_trx_commit</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoLogConfig {

    // ==================== 文件配置 ====================

    /** 日志文件数量 (固定 2 个: ib_logfile0, ib_logfile1) */
    public static final int LOG_FILE_COUNT = 2;

    /** 单个日志文件大小 (默认 512MB) */
    public static final long DEFAULT_LOG_FILE_SIZE = 512L * 1024 * 1024;

    /** 最小日志文件大小 (4MB) */
    public static final long MIN_LOG_FILE_SIZE = 4L * 1024 * 1024;

    /** 最大日志文件大小 (2GB) */
    public static final long MAX_LOG_FILE_SIZE = 2L * 1024 * 1024 * 1024;

    /** 日志文件名前缀 */
    public static final String LOG_FILE_PREFIX = "ib_logfile";

    // ==================== Log Block 配置 (引用 LsnMapper) ====================

    /** Log block 大小 (512 bytes) */
    public static final int OS_FILE_LOG_BLOCK_SIZE = LsnMapper.OS_FILE_LOG_BLOCK_SIZE;

    /** Log block header 大小 (12 bytes) */
    public static final int LOG_BLOCK_HDR_SIZE = LsnMapper.LOG_BLOCK_HDR_SIZE;

    /** Log block trailer 大小 (4 bytes) */
    public static final int LOG_BLOCK_TRL_SIZE = LsnMapper.LOG_BLOCK_TRL_SIZE;

    /** Log block data 大小 (496 bytes) */
    public static final int LOG_BLOCK_DATA_SIZE = LsnMapper.LOG_BLOCK_DATA_SIZE;

    /** Checkpoint header 大小 (2KB = 4 blocks) */
    public static final int CHECKPOINT_HEADER_SIZE = LsnMapper.CHECKPOINT_HEADER_SIZE;

    // ==================== Buffer 配置 ====================

    /** 默认 Log Buffer 大小 (16MB) */
    public static final int DEFAULT_LOG_BUFFER_SIZE = 16 * 1024 * 1024;

    /** 最小 Log Buffer 大小 (1MB) */
    public static final int MIN_LOG_BUFFER_SIZE = 1024 * 1024;

    /** 最大 Log Buffer 大小 (256MB) */
    public static final int MAX_LOG_BUFFER_SIZE = 256 * 1024 * 1024;

    // ==================== 后台线程配置 ====================

    /** LogWriter 检查间隔 (毫秒) */
    public static final long LOG_WRITER_INTERVAL_MS = 1;

    /** LogFlusher 检查间隔 (毫秒) */
    public static final long LOG_FLUSHER_INTERVAL_MS = 10;

    /** Checkpoint 间隔 (秒) */
    public static final int CHECKPOINT_INTERVAL_SEC = 10;

    // ==================== Flush 策略 ====================

    /**
     * 不等待 flush
     * <p>事务提交后不等待 redo log fsync。性能最高，但可能丢失最近 1 秒的事务。</p>
     */
    public static final int FLUSH_AT_TRX_COMMIT_NONE = 0;

    /**
     * 等待 fsync (默认，最安全)
     * <p>事务提交后等待 redo log fsync 到磁盘。保证 ACID 持久性。</p>
     */
    public static final int FLUSH_AT_TRX_COMMIT_SYNC = 1;

    /**
     * 等待 write 到 OS cache
     * <p>事务提交后等待 redo log write 到 OS cache。OS 崩溃可能丢数据，但进程崩溃不会。</p>
     */
    public static final int FLUSH_AT_TRX_COMMIT_WRITE = 2;

    // ==================== 实例字段 ====================

    private final long logFileSize;
    private final int logBufferSize;
    private final int flushLogAtTrxCommit;
    private final String dataDir;

    // ==================== 构造函数 ====================

    /**
     * 创建配置实例
     *
     * @param dataDir 数据目录
     * @param logFileSize 单个日志文件大小
     * @param logBufferSize Log Buffer 大小
     * @param flushLogAtTrxCommit Flush 策略
     */
    public RedoLogConfig(String dataDir, long logFileSize, int logBufferSize, int flushLogAtTrxCommit) {
        // 验证参数
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalArgumentException("dataDir cannot be null or empty");
        }
        if (logFileSize < MIN_LOG_FILE_SIZE || logFileSize > MAX_LOG_FILE_SIZE) {
            throw new IllegalArgumentException(
                    String.format("logFileSize must be between %d and %d, got %d",
                            MIN_LOG_FILE_SIZE, MAX_LOG_FILE_SIZE, logFileSize));
        }
        if (logFileSize % OS_FILE_LOG_BLOCK_SIZE != 0) {
            throw new IllegalArgumentException(
                    "logFileSize must be a multiple of " + OS_FILE_LOG_BLOCK_SIZE);
        }
        if (logBufferSize < MIN_LOG_BUFFER_SIZE || logBufferSize > MAX_LOG_BUFFER_SIZE) {
            throw new IllegalArgumentException(
                    String.format("logBufferSize must be between %d and %d, got %d",
                            MIN_LOG_BUFFER_SIZE, MAX_LOG_BUFFER_SIZE, logBufferSize));
        }
        if ((logBufferSize & (logBufferSize - 1)) != 0) {
            throw new IllegalArgumentException("logBufferSize must be a power of 2");
        }
        if (flushLogAtTrxCommit < 0 || flushLogAtTrxCommit > 2) {
            throw new IllegalArgumentException(
                    "flushLogAtTrxCommit must be 0, 1, or 2, got " + flushLogAtTrxCommit);
        }

        this.dataDir = dataDir;
        this.logFileSize = logFileSize;
        this.logBufferSize = logBufferSize;
        this.flushLogAtTrxCommit = flushLogAtTrxCommit;
    }

    /**
     * 创建默认配置
     *
     * @param dataDir 数据目录
     * @return 默认配置实例
     */
    public static RedoLogConfig defaultConfig(String dataDir) {
        return new RedoLogConfig(
                dataDir,
                DEFAULT_LOG_FILE_SIZE,
                DEFAULT_LOG_BUFFER_SIZE,
                FLUSH_AT_TRX_COMMIT_SYNC
        );
    }

    // ==================== Getter ====================

    public String getDataDir() {
        return dataDir;
    }

    public long getLogFileSize() {
        return logFileSize;
    }

    public int getLogBufferSize() {
        return logBufferSize;
    }

    public int getFlushLogAtTrxCommit() {
        return flushLogAtTrxCommit;
    }

    /**
     * 获取日志文件路径
     *
     * @param fileIndex 文件索引 (0 或 1)
     * @return 文件路径
     */
    public String getLogFilePath(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= LOG_FILE_COUNT) {
            throw new IllegalArgumentException("fileIndex must be 0 or 1, got " + fileIndex);
        }
        return dataDir + "/" + LOG_FILE_PREFIX + fileIndex;
    }

    /**
     * 获取每个文件的可用空间 (排除 checkpoint header)
     *
     * @return 可用字节数
     */
    public long getUsableSpacePerFile() {
        return logFileSize - CHECKPOINT_HEADER_SIZE;
    }

    /**
     * 获取总可用空间 (2 个文件)
     *
     * @return 总可用字节数
     */
    public long getTotalUsableSpace() {
        return 2 * getUsableSpacePerFile();
    }

    @Override
    public String toString() {
        return String.format(
                "RedoLogConfig{dataDir='%s', logFileSize=%dMB, logBufferSize=%dMB, flushLogAtTrxCommit=%d}",
                dataDir, logFileSize / (1024 * 1024), logBufferSize / (1024 * 1024), flushLogAtTrxCommit);
    }

    // ==================== Builder ====================

    /**
     * 配置构建器
     */
    public static class Builder {
        private String dataDir;
        private long logFileSize = DEFAULT_LOG_FILE_SIZE;
        private int logBufferSize = DEFAULT_LOG_BUFFER_SIZE;
        private int flushLogAtTrxCommit = FLUSH_AT_TRX_COMMIT_SYNC;

        public Builder dataDir(String dataDir) {
            this.dataDir = dataDir;
            return this;
        }

        public Builder logFileSize(long logFileSize) {
            this.logFileSize = logFileSize;
            return this;
        }

        public Builder logBufferSize(int logBufferSize) {
            this.logBufferSize = logBufferSize;
            return this;
        }

        public Builder flushLogAtTrxCommit(int flushLogAtTrxCommit) {
            this.flushLogAtTrxCommit = flushLogAtTrxCommit;
            return this;
        }

        public RedoLogConfig build() {
            return new RedoLogConfig(dataDir, logFileSize, logBufferSize, flushLogAtTrxCommit);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
