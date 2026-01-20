package cn.zhangyis.minidb.storage.redo.fileset;

import cn.zhangyis.minidb.storage.redo.RedoLogConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Redo Log 文件集管理器
 *
 * <p>管理 ib_logfile0 和 ib_logfile1 两个循环日志文件的读写操作。
 * 这是 Redo Log 子系统的物理存储层。</p>
 *
 * <h2>文件布局</h2>
 * <pre>
 * ib_logfile0: [Checkpoint Header 2KB] [Data Area ...]
 * ib_logfile1: [Checkpoint Header 2KB] [Data Area ...]
 *
 * 两个文件逻辑上拼成一个环形大文件，循环写入。
 * </pre>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>文件初始化</b>: 创建/打开日志文件，预分配空间</li>
 *   <li><b>写入 Log Block</b>: 根据 LSN 定位并写入 512B blocks</li>
 *   <li><b>读取 Log Block</b>: 用于崩溃恢复时读取日志</li>
 *   <li><b>fsync</b>: 确保数据持久化到磁盘</li>
 *   <li><b>Checkpoint Header</b>: 读写文件头的 checkpoint 信息</li>
 * </ul>
 *
 * <h2>LSN 到 File Position 的映射</h2>
 * <p>使用 {@link LsnMapper} 进行 LSN 到 (fileIndex, offsetInFile) 的转换。</p>
 *
 * <h2>对齐约束</h2>
 * <ul>
 *   <li>所有写入必须 512B (log block) 对齐</li>
 *   <li>文件大小必须是 512 的倍数</li>
 *   <li>Log block 不能跨文件边界 (接近尾部时填充 padding)</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>本类的方法不是线程安全的。由调用方 (LogWriter, LogFlusher) 保证串行访问。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoLogFileSet implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogFileSet.class);

    // ==================== 常量 ====================

    /** Log block 大小 (512 bytes) */
    private static final int BLOCK_SIZE = LsnMapper.OS_FILE_LOG_BLOCK_SIZE;

    // ==================== 配置 ====================

    /** Redo Log 配置 */
    private final RedoLogConfig config;

    /** 单个文件大小 (bytes) */
    private final long fileSize;

    // ==================== 文件通道 ====================

    /** 文件通道数组 (0: ib_logfile0, 1: ib_logfile1) */
    private final FileChannel[] channels;

    /** 文件路径数组 */
    private final Path[] filePaths;

    // ==================== 状态 ====================

    /** 是否已关闭 */
    private volatile boolean closed = false;

    // ==================== 构造函数 ====================

    /**
     * 创建 RedoLogFileSet
     *
     * <p>打开或创建 ib_logfile0 和 ib_logfile1 文件。
     * 如果文件不存在，会预分配指定大小的空间。</p>
     *
     * @param config Redo Log 配置
     * @throws IOException 如果文件操作失败
     */
    public RedoLogFileSet(RedoLogConfig config) throws IOException {
        this.config = config;
        this.fileSize = config.getLogFileSize();
        this.channels = new FileChannel[2];
        this.filePaths = new Path[2];

        // 验证配置
        if (fileSize % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException(
                    "fileSize must be a multiple of " + BLOCK_SIZE + ": " + fileSize);
        }

        // 确保数据目录存在
        Path dataDir = Paths.get(config.getDataDir());
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
            logger.info("Created data directory: {}", dataDir);
        }

        // 打开/创建日志文件
        for (int i = 0; i < 2; i++) {
            filePaths[i] = Paths.get(config.getLogFilePath(i));
            boolean exists = Files.exists(filePaths[i]);

            channels[i] = FileChannel.open(
                    filePaths[i],
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE
            );

            if (!exists) {
                // 新文件，预分配空间
                preallocateFile(i);
                logger.info("Created redo log file: {} ({}MB)",
                        filePaths[i], fileSize / (1024 * 1024));
            } else {
                // 验证现有文件大小
                long actualSize = channels[i].size();
                if (actualSize != fileSize) {
                    logger.warn("Redo log file {} size mismatch: expected {}, actual {}",
                            filePaths[i], fileSize, actualSize);
                }
                logger.info("Opened existing redo log file: {}", filePaths[i]);
            }
        }

        logger.info("RedoLogFileSet initialized: dataDir={}, fileSize={}MB",
                config.getDataDir(), fileSize / (1024 * 1024));
    }

    // ==================== 写入方法 ====================

    /**
     * 写入 log block 到指定 LSN 位置
     *
     * <p>将 512B 的 log block 写入到 LSN 对应的文件位置。
     * 调用方需确保 block 已经被正确格式化 (header + data + trailer)。</p>
     *
     * @param lsn Log block 的起始 LSN (必须 block 对齐)
     * @param block Log block 数据 (512 bytes)
     * @throws IOException 如果写入失败
     * @throws IllegalArgumentException 如果 LSN 不是 block 对齐或 block 大小不正确
     */
    public void writeBlock(long lsn, byte[] block) throws IOException {
        checkNotClosed();

        if (block.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Block size must be " + BLOCK_SIZE + ": " + block.length);
        }

        // 计算文件位置
        LsnMapper.FilePosition pos = LsnMapper.lsnToFilePosition(lsn, fileSize);

        // 写入
        ByteBuffer buf = ByteBuffer.wrap(block);
        int written = channels[pos.fileIndex()].write(buf, pos.offsetInFile());

        if (written != BLOCK_SIZE) {
            throw new IOException("Incomplete write: expected " + BLOCK_SIZE + ", wrote " + written);
        }

        logger.trace("Wrote block at lsn={}, file={}, offset={}",
                lsn, pos.fileIndex(), pos.offsetInFile());
    }

    /**
     * 批量写入 log blocks
     *
     * <p>将多个连续的 log blocks 写入文件。blocks 必须从 startLsn 开始连续排列。</p>
     *
     * @param startLsn 起始 LSN (必须 block 对齐)
     * @param blocks 格式化后的 log blocks (ByteBuffer，大小必须是 512 的倍数)
     * @throws IOException 如果写入失败
     */
    public void writeBlocks(long startLsn, ByteBuffer blocks) throws IOException {
        checkNotClosed();

        int totalSize = blocks.remaining();
        if (totalSize == 0) {
            return;
        }

        if (totalSize % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException(
                    "Blocks size must be a multiple of " + BLOCK_SIZE + ": " + totalSize);
        }

        long currentLsn = startLsn;
        int numBlocks = totalSize / BLOCK_SIZE;

        for (int i = 0; i < numBlocks; i++) {
            // 检查是否需要切换文件
            if (LsnMapper.needSwitchFile(currentLsn, BLOCK_SIZE, fileSize)) {
                // 当前位置无法写入完整 block，需要填充并切换文件
                long nextFileLsn = LsnMapper.getNextFileLsn(currentLsn, fileSize);
                long paddingSize = nextFileLsn - currentLsn;

                if (paddingSize > 0) {
                    writePadding(currentLsn, (int) paddingSize);
                    logger.debug("Wrote {} bytes padding at lsn={}", paddingSize, currentLsn);
                }

                currentLsn = nextFileLsn;
            }

            // 计算文件位置
            LsnMapper.FilePosition pos = LsnMapper.lsnToFilePosition(currentLsn, fileSize);

            // 读取一个 block
            byte[] blockData = new byte[BLOCK_SIZE];
            blocks.get(blockData);

            // 写入
            ByteBuffer buf = ByteBuffer.wrap(blockData);
            int written = channels[pos.fileIndex()].write(buf, pos.offsetInFile());

            if (written != BLOCK_SIZE) {
                throw new IOException("Incomplete write: expected " + BLOCK_SIZE + ", wrote " + written);
            }

            currentLsn += BLOCK_SIZE;
        }

        logger.trace("Wrote {} blocks starting at lsn={}", numBlocks, startLsn);
    }

    /**
     * 写入填充数据 (用于文件边界对齐)
     *
     * @param lsn 起始 LSN
     * @param size 填充大小 (bytes)
     * @throws IOException 如果写入失败
     */
    private void writePadding(long lsn, int size) throws IOException {
        if (size <= 0) {
            return;
        }

        LsnMapper.FilePosition pos = LsnMapper.lsnToFilePosition(lsn, fileSize);

        byte[] padding = new byte[size];
        ByteBuffer buf = ByteBuffer.wrap(padding);

        channels[pos.fileIndex()].write(buf, pos.offsetInFile());
    }

    // ==================== 读取方法 ====================

    /**
     * 读取指定 LSN 位置的 log block
     *
     * <p>用于读取已存在的 block 数据（例如 partial block 合并场景）。
     * 如果 block 尚未写入，返回 null 而不是抛出异常。</p>
     *
     * @param lsn Log block 的起始 LSN (必须 block 对齐)
     * @return 读取的 log block (512 bytes)，如果 block 不存在则返回 null
     * @throws IOException 如果发生 I/O 错误（不包括 block 不存在的情况）
     */
    public byte[] readBlock(long lsn) throws IOException {
        checkNotClosed();

        LsnMapper.FilePosition pos = LsnMapper.lsnToFilePosition(lsn, fileSize);

        byte[] block = new byte[BLOCK_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(block);

        int read = channels[pos.fileIndex()].read(buf, pos.offsetInFile());

        if (read == -1 || read == 0) {
            // Block 尚未写入（文件位置超出当前大小或为空）
            logger.trace("Block not found at lsn={}, file={}, offset={} (read={})",
                    lsn, pos.fileIndex(), pos.offsetInFile(), read);
            return null;
        }

        if (read != BLOCK_SIZE) {
            // 部分读取可能表示文件损坏或正在写入中
            logger.warn("Partial block read at lsn={}: expected {}, got {}",
                    lsn, BLOCK_SIZE, read);
            return null;
        }

        logger.trace("Read block at lsn={}, file={}, offset={}",
                lsn, pos.fileIndex(), pos.offsetInFile());

        return block;
    }

    /**
     * 批量读取 log blocks
     *
     * <p>用于恢复时批量读取 redo log。如果某个 block 不存在，
     * 则停止读取并返回已读取的部分。</p>
     *
     * @param startLsn 起始 LSN
     * @param numBlocks 要读取的 block 数量
     * @return 读取的数据，可能少于请求的数量（如果遇到不存在的 block）
     * @throws IOException 如果发生 I/O 错误
     */
    public ByteBuffer readBlocks(long startLsn, int numBlocks) throws IOException {
        checkNotClosed();

        if (numBlocks <= 0) {
            return ByteBuffer.allocate(0);
        }

        ByteBuffer result = ByteBuffer.allocate(numBlocks * BLOCK_SIZE);
        long currentLsn = startLsn;

        for (int i = 0; i < numBlocks; i++) {
            byte[] block = readBlock(currentLsn);
            if (block == null) {
                // 遇到不存在的 block，停止读取
                logger.debug("Stopping batch read at block {}: block not found at lsn={}",
                        i, currentLsn);
                break;
            }
            result.put(block);
            currentLsn += BLOCK_SIZE;
        }

        result.flip();
        return result;
    }

    // ==================== fsync 方法 ====================

    /**
     * 将两个日志文件 fsync 到磁盘
     *
     * <p>调用 FileChannel.force(true) 确保数据和元数据都持久化。</p>
     *
     * @throws IOException 如果 fsync 失败
     */
    public void fsync() throws IOException {
        checkNotClosed();

        long startTime = System.nanoTime();

        // force(true) = fsync data + metadata
        channels[0].force(true);
        channels[1].force(true);

        long elapsed = (System.nanoTime() - startTime) / 1000;
        logger.trace("fsync completed in {}μs", elapsed);
    }

    /**
     * 只 fsync 指定文件
     *
     * @param fileIndex 文件索引 (0 或 1)
     * @throws IOException 如果 fsync 失败
     */
    public void fsync(int fileIndex) throws IOException {
        checkNotClosed();

        if (fileIndex < 0 || fileIndex >= 2) {
            throw new IllegalArgumentException("fileIndex must be 0 or 1: " + fileIndex);
        }

        channels[fileIndex].force(true);
    }

    // ==================== Checkpoint Header 操作 ====================

    /**
     * 读取 checkpoint header (文件头 2KB)
     *
     * @param fileIndex 文件索引 (0 或 1)
     * @return Checkpoint header 数据 (2048 bytes)
     * @throws IOException 如果读取失败
     */
    public ByteBuffer readCheckpointHeader(int fileIndex) throws IOException {
        checkNotClosed();

        if (fileIndex < 0 || fileIndex >= 2) {
            throw new IllegalArgumentException("fileIndex must be 0 or 1: " + fileIndex);
        }

        ByteBuffer buf = ByteBuffer.allocate(LsnMapper.CHECKPOINT_HEADER_SIZE);
        channels[fileIndex].read(buf, 0);
        buf.flip();

        return buf;
    }

    /**
     * 写入 checkpoint header (文件头 2KB)
     *
     * <p>Checkpoint 信息交替写入 ib_logfile0 和 ib_logfile1 的头部。</p>
     *
     * @param fileIndex 文件索引 (0 或 1)
     * @param header Checkpoint header 数据 (2048 bytes)
     * @throws IOException 如果写入失败
     */
    public void writeCheckpointHeader(int fileIndex, ByteBuffer header) throws IOException {
        checkNotClosed();

        if (fileIndex < 0 || fileIndex >= 2) {
            throw new IllegalArgumentException("fileIndex must be 0 or 1: " + fileIndex);
        }

        if (header.remaining() != LsnMapper.CHECKPOINT_HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Header size must be " + LsnMapper.CHECKPOINT_HEADER_SIZE +
                            ": " + header.remaining());
        }

        channels[fileIndex].write(header, 0);
        channels[fileIndex].force(true);

        logger.debug("Wrote checkpoint header to file {}", fileIndex);
    }

    // ==================== 空间管理 ====================

    /**
     * 获取每个文件的可用空间 (排除 checkpoint header)
     *
     * @return 可用字节数
     */
    public long getUsableSpacePerFile() {
        return config.getUsableSpacePerFile();
    }

    /**
     * 获取总可用空间 (2 个文件)
     *
     * @return 总可用字节数
     */
    public long getTotalUsableSpace() {
        return config.getTotalUsableSpace();
    }

    /**
     * 获取文件通道 (供 Checkpoint 等高级操作使用)
     *
     * @param fileIndex 文件索引 (0 或 1)
     * @return FileChannel
     */
    public FileChannel getChannel(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= 2) {
            throw new IllegalArgumentException("fileIndex must be 0 or 1: " + fileIndex);
        }
        return channels[fileIndex];
    }

    // ==================== 生命周期管理 ====================

    /**
     * 关闭文件集
     *
     * <p>关闭所有文件通道。调用后不能再进行任何 I/O 操作。</p>
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;

        // 先 fsync 再关闭
        for (int i = 0; i < 2; i++) {
            if (channels[i] != null && channels[i].isOpen()) {
                try {
                    channels[i].force(true);
                } catch (IOException e) {
                    logger.warn("Failed to fsync file {} before close: {}", i, e.getMessage());
                }

                try {
                    channels[i].close();
                } catch (IOException e) {
                    logger.warn("Failed to close file {}: {}", i, e.getMessage());
                }
            }
        }

        logger.info("RedoLogFileSet closed");
    }

    /**
     * 检查是否已关闭
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("RedoLogFileSet is closed");
        }
    }

    // ==================== 初始化辅助方法 ====================

    /**
     * 预分配文件空间
     *
     * <p>创建新文件时，预分配全部空间并填充 0。
     * 这样可以避免后续写入时的文件系统碎片。</p>
     *
     * @param fileIndex 文件索引
     * @throws IOException 如果分配失败
     */
    private void preallocateFile(int fileIndex) throws IOException {
        // 扩展文件到目标大小: 在文件末尾位置写入一个字节
        // 注意: truncate() 只能截断，不能扩展文件
        ByteBuffer oneByte = ByteBuffer.allocate(1);
        oneByte.put((byte) 0);
        oneByte.flip();
        channels[fileIndex].write(oneByte, fileSize - 1);

        // 初始化 checkpoint header 区域为 0
        ByteBuffer zeros = ByteBuffer.allocate(LsnMapper.CHECKPOINT_HEADER_SIZE);
        channels[fileIndex].write(zeros, 0);

        logger.debug("Preallocated file {}: {}MB", fileIndex, fileSize / (1024 * 1024));
    }

    // ==================== 调试方法 ====================

    @Override
    public String toString() {
        return String.format("RedoLogFileSet{dataDir='%s', fileSize=%dMB, closed=%s}",
                config.getDataDir(), fileSize / (1024 * 1024), closed);
    }

    /**
     * 获取文件路径 (用于调试)
     *
     * @param fileIndex 文件索引
     * @return 文件路径
     */
    public Path getFilePath(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= 2) {
            throw new IllegalArgumentException("fileIndex must be 0 or 1: " + fileIndex);
        }
        return filePaths[fileIndex];
    }

    /**
     * 获取单个日志文件大小
     *
     * @return 文件大小 (bytes)
     */
    public long getFileSize() {
        return fileSize;
    }
}
