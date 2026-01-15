package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.redo.buffer.LogBlockFormatter;
import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LogBlockFormatter 单元测试
 *
 * <p>验证 Log Block 格式化的正确性，包括：</p>
 * <ul>
 *   <li>format(): SN → LSN 转换和 block 结构生成</li>
 *   <li>verifyChecksum(): checksum 验证</li>
 *   <li>extractPayload(): payload 提取</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("LogBlockFormatter Tests")
class LogBlockFormatterTest {

    private static final int BLOCK_SIZE = LsnMapper.OS_FILE_LOG_BLOCK_SIZE;  // 512
    private static final int DATA_SIZE = LsnMapper.LOG_BLOCK_DATA_SIZE;      // 496

    // ==================== format 测试 ====================

    @Nested
    @DisplayName("format Tests")
    class FormatTests {

        @Test
        @DisplayName("Format empty payload should return empty buffer")
        void formatEmptyPayload() {
            ByteBuffer empty = ByteBuffer.allocate(0);
            ByteBuffer result = LogBlockFormatter.format(0, empty);
            assertEquals(0, result.remaining());
        }

        @Test
        @DisplayName("Format small payload should produce one block")
        void formatSmallPayload() {
            // 100 bytes payload 应该生成 1 个 block
            byte[] payload = new byte[100];
            for (int i = 0; i < 100; i++) {
                payload[i] = (byte) i;
            }

            ByteBuffer result = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            assertEquals(BLOCK_SIZE, result.remaining());
        }

        @Test
        @DisplayName("Format payload exactly 496 bytes should produce one block")
        void formatExactlyOneBlockPayload() {
            byte[] payload = new byte[DATA_SIZE];
            ByteBuffer result = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            assertEquals(BLOCK_SIZE, result.remaining());
        }

        @Test
        @DisplayName("Format payload 497 bytes should produce two blocks")
        void formatTwoBlocksPayload() {
            byte[] payload = new byte[DATA_SIZE + 1];
            ByteBuffer result = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            assertEquals(BLOCK_SIZE * 2, result.remaining());
        }

        @Test
        @DisplayName("Format large payload should produce multiple blocks")
        void formatLargePayload() {
            // 1000 bytes = 3 blocks (496 + 496 + 8)
            byte[] payload = new byte[1000];
            ByteBuffer result = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            assertEquals(BLOCK_SIZE * 3, result.remaining());
        }

        @Test
        @DisplayName("Format should handle non-zero startSn")
        void formatWithNonZeroStartSn() {
            // startSn = 100 应该在 block 0 的 offset 100 处开始
            byte[] payload = new byte[100];
            ByteBuffer result = LogBlockFormatter.format(100, ByteBuffer.wrap(payload));
            assertEquals(BLOCK_SIZE, result.remaining());
        }

        @Test
        @DisplayName("Format with startSn crossing block boundary")
        void formatCrossingBlockBoundary() {
            // startSn = 490 意味着 block 0 只剩 6 bytes，需要第二个 block
            byte[] payload = new byte[100];
            ByteBuffer result = LogBlockFormatter.format(490, ByteBuffer.wrap(payload));
            assertEquals(BLOCK_SIZE * 2, result.remaining());
        }
    }

    // ==================== verifyChecksum 测试 ====================

    @Nested
    @DisplayName("verifyChecksum Tests")
    class VerifyChecksumTests {

        @Test
        @DisplayName("Valid block should pass checksum verification")
        void validBlockPassesVerification() {
            byte[] payload = new byte[100];
            for (int i = 0; i < 100; i++) {
                payload[i] = (byte) i;
            }

            ByteBuffer formatted = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            byte[] block = new byte[BLOCK_SIZE];
            formatted.get(block);

            assertTrue(LogBlockFormatter.verifyChecksum(block));
        }

        @Test
        @DisplayName("Corrupted block should fail checksum verification")
        void corruptedBlockFailsVerification() {
            byte[] payload = new byte[100];
            ByteBuffer formatted = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            byte[] block = new byte[BLOCK_SIZE];
            formatted.get(block);

            // 破坏数据
            block[50] ^= 0xFF;

            assertFalse(LogBlockFormatter.verifyChecksum(block));
        }

        @Test
        @DisplayName("Wrong size block should fail verification")
        void wrongSizeBlockFailsVerification() {
            byte[] wrongSize = new byte[100];
            assertFalse(LogBlockFormatter.verifyChecksum(wrongSize));
        }
    }

    // ==================== extractPayload 测试 ====================

    @Nested
    @DisplayName("extractPayload Tests")
    class ExtractPayloadTests {

        @Test
        @DisplayName("Extract payload should return original data")
        void extractReturnsOriginalData() {
            // 写入数据
            byte[] payload = new byte[100];
            for (int i = 0; i < 100; i++) {
                payload[i] = (byte) (i + 10);
            }

            // 格式化
            ByteBuffer formatted = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            byte[] block = new byte[BLOCK_SIZE];
            formatted.get(block);

            // 提取
            ByteBuffer extracted = LogBlockFormatter.extractPayload(block);
            assertEquals(100, extracted.remaining());

            for (int i = 0; i < 100; i++) {
                assertEquals((byte) (i + 10), extracted.get());
            }
        }

        @Test
        @DisplayName("Extract should handle full block payload")
        void extractFullBlockPayload() {
            byte[] payload = new byte[DATA_SIZE];
            for (int i = 0; i < DATA_SIZE; i++) {
                payload[i] = (byte) i;
            }

            ByteBuffer formatted = LogBlockFormatter.format(0, ByteBuffer.wrap(payload));
            byte[] block = new byte[BLOCK_SIZE];
            formatted.get(block);

            ByteBuffer extracted = LogBlockFormatter.extractPayload(block);
            assertEquals(DATA_SIZE, extracted.remaining());
        }

        @Test
        @DisplayName("Extract should reject wrong size block")
        void extractRejectsWrongSizeBlock() {
            byte[] wrongSize = new byte[100];
            assertThrows(IllegalArgumentException.class,
                    () -> LogBlockFormatter.extractPayload(wrongSize));
        }
    }

    // ==================== getBlockNo 测试 ====================

    @Nested
    @DisplayName("getBlockNo Tests")
    class GetBlockNoTests {

        @Test
        @DisplayName("Block number should match startSn / DATA_SIZE")
        void blockNoMatchesFormula() {
            // startSn = 0 → block 0
            ByteBuffer formatted0 = LogBlockFormatter.format(0, ByteBuffer.wrap(new byte[100]));
            byte[] block0 = new byte[BLOCK_SIZE];
            formatted0.get(block0);
            assertEquals(0, LogBlockFormatter.getBlockNo(block0));

            // startSn = 496 → block 1
            ByteBuffer formatted1 = LogBlockFormatter.format(DATA_SIZE, ByteBuffer.wrap(new byte[100]));
            byte[] block1 = new byte[BLOCK_SIZE];
            formatted1.get(block1);
            assertEquals(1, LogBlockFormatter.getBlockNo(block1));

            // startSn = 992 → block 2
            ByteBuffer formatted2 = LogBlockFormatter.format(DATA_SIZE * 2, ByteBuffer.wrap(new byte[100]));
            byte[] block2 = new byte[BLOCK_SIZE];
            formatted2.get(block2);
            assertEquals(2, LogBlockFormatter.getBlockNo(block2));
        }
    }

    // ==================== getDataLen 测试 ====================

    @Nested
    @DisplayName("getDataLen Tests")
    class GetDataLenTests {

        @Test
        @DisplayName("DataLen should reflect actual payload size")
        void dataLenReflectsPayloadSize() {
            // 100 bytes payload
            ByteBuffer formatted = LogBlockFormatter.format(0, ByteBuffer.wrap(new byte[100]));
            byte[] block = new byte[BLOCK_SIZE];
            formatted.get(block);
            assertEquals(100, LogBlockFormatter.getDataLen(block));

            // Full block (496 bytes)
            ByteBuffer formattedFull = LogBlockFormatter.format(0, ByteBuffer.wrap(new byte[DATA_SIZE]));
            byte[] blockFull = new byte[BLOCK_SIZE];
            formattedFull.get(blockFull);
            assertEquals(DATA_SIZE, LogBlockFormatter.getDataLen(blockFull));
        }

        @Test
        @DisplayName("DataLen in partial block should be correct")
        void dataLenInPartialBlock() {
            // startSn = 450，第一个 block 只有 46 bytes 空间
            // payload = 100 bytes
            // block 0: 46 bytes
            // block 1: 54 bytes
            byte[] payload = new byte[100];
            ByteBuffer formatted = LogBlockFormatter.format(450, ByteBuffer.wrap(payload));

            // 第一个 block
            byte[] block0 = new byte[BLOCK_SIZE];
            formatted.get(block0);
            // dataLen 应该是 450 + 46 = 496 (满)
            assertEquals(DATA_SIZE, LogBlockFormatter.getDataLen(block0));

            // 第二个 block
            byte[] block1 = new byte[BLOCK_SIZE];
            formatted.get(block1);
            // dataLen 应该是剩余的 54 bytes
            assertEquals(54, LogBlockFormatter.getDataLen(block1));
        }
    }

    // ==================== Round-trip 测试 ====================

    @Nested
    @DisplayName("Round-trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Format and extract should preserve data")
        void formatAndExtractPreservesData() {
            for (int size = 1; size <= 1000; size += 100) {
                byte[] original = new byte[size];
                for (int i = 0; i < size; i++) {
                    original[i] = (byte) (i * 3);
                }

                ByteBuffer formatted = LogBlockFormatter.format(0, ByteBuffer.wrap(original));
                int numBlocks = formatted.remaining() / BLOCK_SIZE;

                ByteBuffer reconstructed = ByteBuffer.allocate(size);
                for (int b = 0; b < numBlocks; b++) {
                    byte[] block = new byte[BLOCK_SIZE];
                    formatted.get(block);

                    assertTrue(LogBlockFormatter.verifyChecksum(block),
                            "Block " + b + " checksum failed for payload size " + size);

                    ByteBuffer payload = LogBlockFormatter.extractPayload(block);
                    int toRead = Math.min(payload.remaining(), size - reconstructed.position());
                    byte[] temp = new byte[toRead];
                    payload.get(temp);
                    reconstructed.put(temp);
                }

                reconstructed.flip();
                for (int i = 0; i < size; i++) {
                    assertEquals(original[i], reconstructed.get(i),
                            "Byte " + i + " mismatch for payload size " + size);
                }
            }
        }
    }
}
