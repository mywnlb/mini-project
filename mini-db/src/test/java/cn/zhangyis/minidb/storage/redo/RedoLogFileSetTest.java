package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedoLogFileSet 单元测试
 *
 * <p>验证文件管理的正确性，包括：</p>
 * <ul>
 *   <li>文件创建和初始化</li>
 *   <li>Log block 写入和读取</li>
 *   <li>fsync 操作</li>
 *   <li>Checkpoint header 读写</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("RedoLogFileSet Tests")
class RedoLogFileSetTest {

    private static final int BLOCK_SIZE = LsnMapper.OS_FILE_LOG_BLOCK_SIZE;  // 512
    private static final long TEST_FILE_SIZE = 4 * 1024 * 1024;  // 4MB for faster tests

    private Path testDir;
    private RedoLogConfig config;
    private RedoLogFileSet fileSet;

    @BeforeEach
    void setup() throws IOException {
        testDir = Files.createTempDirectory("redo_test_");
        config = new RedoLogConfig.Builder()
                .dataDir(testDir.toString())
                .logFileSize(TEST_FILE_SIZE)
                .logBufferSize(1024 * 1024)
                .flushLogAtTrxCommit(RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC)
                .build();
        fileSet = new RedoLogFileSet(config);
    }

    @AfterEach
    void cleanup() throws IOException {
        if (fileSet != null) {
            fileSet.close();
        }

        if (testDir != null) {
            Files.walk(testDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        }
    }

    // ==================== 初始化测试 ====================

    @Nested
    @DisplayName("Initialization Tests")
    class InitializationTests {

        @Test
        @DisplayName("Should create log files on initialization")
        void createsLogFiles() {
            assertTrue(Files.exists(fileSet.getFilePath(0)));
            assertTrue(Files.exists(fileSet.getFilePath(1)));
        }

        @Test
        @DisplayName("Log files should have correct size")
        void logFilesHaveCorrectSize() throws IOException {
            assertEquals(TEST_FILE_SIZE, Files.size(fileSet.getFilePath(0)));
            assertEquals(TEST_FILE_SIZE, Files.size(fileSet.getFilePath(1)));
        }

        @Test
        @DisplayName("Should calculate usable space correctly")
        void calculatesUsableSpace() {
            long expectedUsable = TEST_FILE_SIZE - LsnMapper.CHECKPOINT_HEADER_SIZE;
            assertEquals(expectedUsable, fileSet.getUsableSpacePerFile());
            assertEquals(expectedUsable * 2, fileSet.getTotalUsableSpace());
        }
    }

    // ==================== 写入测试 ====================

    @Nested
    @DisplayName("Write Tests")
    class WriteTests {

        @Test
        @DisplayName("Write single block")
        void writeSingleBlock() throws IOException {
            byte[] block = createTestBlock(0, 100);
            fileSet.writeBlock(0, block);

            byte[] readBack = fileSet.readBlock(0);
            assertArrayEquals(block, readBack);
        }

        @Test
        @DisplayName("Write multiple blocks")
        void writeMultipleBlocks() throws IOException {
            // 写入 10 个 blocks
            for (int i = 0; i < 10; i++) {
                long lsn = i * BLOCK_SIZE;
                byte[] block = createTestBlock(i, 100 + i);
                fileSet.writeBlock(lsn, block);
            }

            // 读回验证
            for (int i = 0; i < 10; i++) {
                long lsn = i * BLOCK_SIZE;
                byte[] readBack = fileSet.readBlock(lsn);
                byte[] expected = createTestBlock(i, 100 + i);
                assertArrayEquals(expected, readBack, "Block " + i + " mismatch");
            }
        }

        @Test
        @DisplayName("Write blocks to second file")
        void writeToSecondFile() throws IOException {
            // 计算第二个文件的起始 LSN
            long usablePerFile = fileSet.getUsableSpacePerFile();
            long lsn = usablePerFile;

            byte[] block = createTestBlock(999, 200);
            fileSet.writeBlock(lsn, block);

            byte[] readBack = fileSet.readBlock(lsn);
            assertArrayEquals(block, readBack);
        }

        @Test
        @DisplayName("Reject invalid block size")
        void rejectInvalidBlockSize() {
            byte[] wrongSize = new byte[100];
            assertThrows(IllegalArgumentException.class,
                    () -> fileSet.writeBlock(0, wrongSize));
        }

        @Test
        @DisplayName("Write blocks via batch API")
        void writeBatchBlocks() throws IOException {
            int numBlocks = 5;
            ByteBuffer blocks = ByteBuffer.allocate(numBlocks * BLOCK_SIZE);

            for (int i = 0; i < numBlocks; i++) {
                blocks.put(createTestBlock(i, 50 + i));
            }
            blocks.flip();

            fileSet.writeBlocks(0, blocks);

            // 验证
            for (int i = 0; i < numBlocks; i++) {
                byte[] readBack = fileSet.readBlock(i * BLOCK_SIZE);
                byte[] expected = createTestBlock(i, 50 + i);
                assertArrayEquals(expected, readBack, "Block " + i + " mismatch");
            }
        }
    }

    // ==================== 读取测试 ====================

    @Nested
    @DisplayName("Read Tests")
    class ReadTests {

        @Test
        @DisplayName("Read single block")
        void readSingleBlock() throws IOException {
            byte[] block = createTestBlock(0, 123);
            fileSet.writeBlock(0, block);

            byte[] readBack = fileSet.readBlock(0);
            assertArrayEquals(block, readBack);
        }

        @Test
        @DisplayName("Read multiple blocks via batch API")
        void readBatchBlocks() throws IOException {
            // 写入
            for (int i = 0; i < 5; i++) {
                fileSet.writeBlock(i * BLOCK_SIZE, createTestBlock(i, 100 + i));
            }

            // 批量读取
            ByteBuffer result = fileSet.readBlocks(0, 5);
            assertEquals(5 * BLOCK_SIZE, result.remaining());

            // 验证每个 block
            for (int i = 0; i < 5; i++) {
                byte[] expected = createTestBlock(i, 100 + i);
                byte[] actual = new byte[BLOCK_SIZE];
                result.get(actual);
                assertArrayEquals(expected, actual, "Block " + i + " mismatch");
            }
        }
    }

    // ==================== fsync 测试 ====================

    @Nested
    @DisplayName("fsync Tests")
    class FsyncTests {

        @Test
        @DisplayName("fsync should not throw")
        void fsyncDoesNotThrow() throws IOException {
            fileSet.writeBlock(0, createTestBlock(0, 100));
            assertDoesNotThrow(() -> fileSet.fsync());
        }

        @Test
        @DisplayName("fsync single file should not throw")
        void fsyncSingleFileDoesNotThrow() throws IOException {
            fileSet.writeBlock(0, createTestBlock(0, 100));
            assertDoesNotThrow(() -> fileSet.fsync(0));
            assertDoesNotThrow(() -> fileSet.fsync(1));
        }
    }

    // ==================== Checkpoint Header 测试 ====================

    @Nested
    @DisplayName("Checkpoint Header Tests")
    class CheckpointHeaderTests {

        @Test
        @DisplayName("Write and read checkpoint header")
        void writeAndReadCheckpointHeader() throws IOException {
            // 创建测试 header
            ByteBuffer header = ByteBuffer.allocate(LsnMapper.CHECKPOINT_HEADER_SIZE);
            header.putLong(0x1234567890ABCDEFL);  // magic
            header.putLong(12345L);  // checkpoint LSN
            header.putLong(1L);  // checkpoint no
            // 填充剩余空间
            while (header.hasRemaining()) {
                header.put((byte) 0);
            }
            header.flip();

            // 写入 file 0
            fileSet.writeCheckpointHeader(0, header);

            // 读回
            ByteBuffer readBack = fileSet.readCheckpointHeader(0);
            assertEquals(LsnMapper.CHECKPOINT_HEADER_SIZE, readBack.remaining());

            // 验证内容
            assertEquals(0x1234567890ABCDEFL, readBack.getLong());
            assertEquals(12345L, readBack.getLong());
            assertEquals(1L, readBack.getLong());
        }

        @Test
        @DisplayName("Checkpoint headers are independent between files")
        void checkpointHeadersAreIndependent() throws IOException {
            // 写入不同的 header 到两个文件
            ByteBuffer header0 = createCheckpointHeader(100L);
            ByteBuffer header1 = createCheckpointHeader(200L);

            fileSet.writeCheckpointHeader(0, header0);
            fileSet.writeCheckpointHeader(1, header1);

            // 读回验证
            ByteBuffer read0 = fileSet.readCheckpointHeader(0);
            ByteBuffer read1 = fileSet.readCheckpointHeader(1);

            read0.getLong();  // skip magic
            assertEquals(100L, read0.getLong());

            read1.getLong();  // skip magic
            assertEquals(200L, read1.getLong());
        }

        @Test
        @DisplayName("Reject wrong size checkpoint header")
        void rejectWrongSizeHeader() {
            ByteBuffer wrongSize = ByteBuffer.allocate(100);
            assertThrows(IllegalArgumentException.class,
                    () -> fileSet.writeCheckpointHeader(0, wrongSize));
        }
    }

    // ==================== 生命周期测试 ====================

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Close should be idempotent")
        void closeIsIdempotent() throws IOException {
            fileSet.close();
            assertDoesNotThrow(() -> fileSet.close());
        }

        @Test
        @DisplayName("Operations after close should throw")
        void operationsAfterCloseThrow() throws IOException {
            fileSet.close();

            assertThrows(IllegalStateException.class,
                    () -> fileSet.writeBlock(0, new byte[BLOCK_SIZE]));
            assertThrows(IllegalStateException.class,
                    () -> fileSet.readBlock(0));
            assertThrows(IllegalStateException.class,
                    () -> fileSet.fsync());
        }

        @Test
        @DisplayName("Reopen existing files")
        void reopenExistingFiles() throws IOException {
            // 写入数据
            byte[] block = createTestBlock(0, 42);
            fileSet.writeBlock(0, block);
            fileSet.fsync();
            fileSet.close();

            // 重新打开
            RedoLogFileSet reopened = new RedoLogFileSet(config);
            try {
                byte[] readBack = reopened.readBlock(0);
                assertArrayEquals(block, readBack);
            } finally {
                reopened.close();
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试用 log block (512 bytes)
     */
    private byte[] createTestBlock(int blockNo, int fillByte) {
        byte[] block = new byte[BLOCK_SIZE];
        // Header
        ByteBuffer.wrap(block, 0, 12)
                .putInt(blockNo)
                .putShort((short) 100)  // data_len
                .putShort((short) 0)     // first_rec_offset
                .putInt(0);              // checksum placeholder

        // Data (用 fillByte 填充)
        for (int i = 12; i < 508; i++) {
            block[i] = (byte) fillByte;
        }

        // Trailer
        ByteBuffer.wrap(block, 508, 4).putInt(0);

        return block;
    }

    /**
     * 创建 checkpoint header
     */
    private ByteBuffer createCheckpointHeader(long checkpointLsn) {
        ByteBuffer header = ByteBuffer.allocate(LsnMapper.CHECKPOINT_HEADER_SIZE);
        header.putLong(0x1B581E51);  // magic
        header.putLong(checkpointLsn);
        while (header.hasRemaining()) {
            header.put((byte) 0);
        }
        header.flip();
        return header;
    }
}
