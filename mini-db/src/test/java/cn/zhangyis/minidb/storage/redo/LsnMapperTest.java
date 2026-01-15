package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LsnMapper 单元测试
 *
 * <p>验证 SN ↔ LSN ↔ FileOffset 转换的正确性。</p>
 *
 * <h2>测试覆盖</h2>
 * <ul>
 *   <li>snToLsn(): 验证结果必须落在 data 区域 (offset 12-507)</li>
 *   <li>lsnToSn(): 验证 header/trailer 吸附规则</li>
 *   <li>双向转换一致性: lsnToSn(snToLsn(sn)) == sn</li>
 *   <li>lsnToFilePosition(): 验证文件映射和环形布局</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("LsnMapper Tests")
class LsnMapperTest {

    private static final int BLOCK_SIZE = LsnMapper.OS_FILE_LOG_BLOCK_SIZE;  // 512
    private static final int HEADER_SIZE = LsnMapper.LOG_BLOCK_HDR_SIZE;     // 12
    private static final int DATA_SIZE = LsnMapper.LOG_BLOCK_DATA_SIZE;      // 496
    private static final int TRAILER_SIZE = LsnMapper.LOG_BLOCK_TRL_SIZE;    // 4

    // ==================== snToLsn 测试 ====================

    @Nested
    @DisplayName("snToLsn Tests")
    class SnToLsnTests {

        @Test
        @DisplayName("sn=0 should map to lsn=12 (first data byte in block 0)")
        void snZeroMapsToFirstDataByte() {
            long lsn = LsnMapper.snToLsn(0);
            assertEquals(HEADER_SIZE, lsn);  // 12
            assertTrue(LsnMapper.isLsnInDataArea(lsn));
        }

        @Test
        @DisplayName("sn=495 should map to lsn=507 (last data byte in block 0)")
        void snLastInFirstBlockMapsCorrectly() {
            long lsn = LsnMapper.snToLsn(495);
            assertEquals(HEADER_SIZE + 495, lsn);  // 507
            assertTrue(LsnMapper.isLsnInDataArea(lsn));
        }

        @Test
        @DisplayName("sn=496 should map to lsn=524 (first data byte in block 1)")
        void snFirstInSecondBlockMapsCorrectly() {
            long lsn = LsnMapper.snToLsn(DATA_SIZE);
            assertEquals(BLOCK_SIZE + HEADER_SIZE, lsn);  // 512 + 12 = 524
            assertTrue(LsnMapper.isLsnInDataArea(lsn));
        }

        @Test
        @DisplayName("sn=992 should map to lsn=1036 (first data byte in block 2)")
        void snInThirdBlockMapsCorrectly() {
            long lsn = LsnMapper.snToLsn(DATA_SIZE * 2);
            assertEquals(BLOCK_SIZE * 2 + HEADER_SIZE, lsn);  // 1024 + 12 = 1036
            assertTrue(LsnMapper.isLsnInDataArea(lsn));
        }

        @Test
        @DisplayName("Large SN values should map correctly")
        void largeSNValuesMapCorrectly() {
            // sn = 1000000 应该正确映射
            long sn = 1_000_000;
            long lsn = LsnMapper.snToLsn(sn);

            // 计算预期值
            long blockNo = sn / DATA_SIZE;
            long dataOffset = sn % DATA_SIZE;
            long expectedLsn = blockNo * BLOCK_SIZE + HEADER_SIZE + dataOffset;

            assertEquals(expectedLsn, lsn);
            assertTrue(LsnMapper.isLsnInDataArea(lsn));
        }
    }

    // ==================== lsnToSn 测试 ====================

    @Nested
    @DisplayName("lsnToSn Tests")
    class LsnToSnTests {

        @Test
        @DisplayName("LSN in header should snap to block start")
        void lsnInHeaderSnapsToBlockStart() {
            // LSN 0-11 (header) 应该吸附到 sn=0
            for (int offset = 0; offset < HEADER_SIZE; offset++) {
                long sn = LsnMapper.lsnToSn(offset);
                assertEquals(0, sn, "LSN " + offset + " should snap to sn=0");
            }
        }

        @Test
        @DisplayName("LSN in data area should map directly")
        void lsnInDataAreaMapsDirect() {
            // LSN 12-507 (data) 应该直接映射
            for (int dataOffset = 0; dataOffset < DATA_SIZE; dataOffset++) {
                long lsn = HEADER_SIZE + dataOffset;
                long sn = LsnMapper.lsnToSn(lsn);
                assertEquals(dataOffset, sn);
            }
        }

        @Test
        @DisplayName("LSN in trailer should snap to next block start")
        void lsnInTrailerSnapsToNextBlockStart() {
            // LSN 508-511 (trailer) 应该吸附到 sn=496 (下一个 block 的起始)
            for (int offset = BLOCK_SIZE - TRAILER_SIZE; offset < BLOCK_SIZE; offset++) {
                long sn = LsnMapper.lsnToSn(offset);
                assertEquals(DATA_SIZE, sn, "LSN " + offset + " should snap to sn=" + DATA_SIZE);
            }
        }

        @Test
        @DisplayName("LSN in second block header should snap correctly")
        void lsnInSecondBlockHeaderSnapsCorrectly() {
            // Block 1 header: LSN 512-523
            for (int offset = 0; offset < HEADER_SIZE; offset++) {
                long lsn = BLOCK_SIZE + offset;  // 512 + offset
                long sn = LsnMapper.lsnToSn(lsn);
                assertEquals(DATA_SIZE, sn, "LSN " + lsn + " should snap to sn=" + DATA_SIZE);
            }
        }
    }

    // ==================== 双向转换一致性测试 ====================

    @Nested
    @DisplayName("Bidirectional Conversion Tests")
    class BidirectionalTests {

        @Test
        @DisplayName("lsnToSn(snToLsn(sn)) should equal sn")
        void roundTripFromSnIsIdentity() {
            // 测试各种 SN 值
            long[] testValues = {0, 1, 100, 495, 496, 497, 992, 1000, 10000, 100000, 1000000};

            for (long sn : testValues) {
                long lsn = LsnMapper.snToLsn(sn);
                long roundTrippedSn = LsnMapper.lsnToSn(lsn);
                assertEquals(sn, roundTrippedSn, "Round trip failed for sn=" + sn);
            }
        }

        @Test
        @DisplayName("snToLsn should always produce LSN in data area")
        void snToLsnAlwaysProducesDataAreaLsn() {
            for (long sn = 0; sn < 10000; sn++) {
                long lsn = LsnMapper.snToLsn(sn);
                assertTrue(LsnMapper.isLsnInDataArea(lsn),
                        "LSN " + lsn + " for sn=" + sn + " should be in data area");
            }
        }
    }

    // ==================== isLsnInDataArea 测试 ====================

    @Nested
    @DisplayName("isLsnInDataArea Tests")
    class IsLsnInDataAreaTests {

        @Test
        @DisplayName("Header bytes should not be in data area")
        void headerNotInDataArea() {
            for (int offset = 0; offset < HEADER_SIZE; offset++) {
                assertFalse(LsnMapper.isLsnInDataArea(offset));
            }
        }

        @Test
        @DisplayName("Data bytes should be in data area")
        void dataInDataArea() {
            for (int offset = HEADER_SIZE; offset < BLOCK_SIZE - TRAILER_SIZE; offset++) {
                assertTrue(LsnMapper.isLsnInDataArea(offset));
            }
        }

        @Test
        @DisplayName("Trailer bytes should not be in data area")
        void trailerNotInDataArea() {
            for (int offset = BLOCK_SIZE - TRAILER_SIZE; offset < BLOCK_SIZE; offset++) {
                assertFalse(LsnMapper.isLsnInDataArea(offset));
            }
        }
    }

    // ==================== lsnToFilePosition 测试 ====================

    @Nested
    @DisplayName("lsnToFilePosition Tests")
    class FilePositionTests {

        private static final long FILE_SIZE = 16 * 1024 * 1024;  // 16MB
        private static final int HEADER_2KB = LsnMapper.CHECKPOINT_HEADER_SIZE;

        @Test
        @DisplayName("LSN=0 should map to file 0, offset=checkpoint_header")
        void lsnZeroMapsToFileStart() {
            LsnMapper.FilePosition pos = LsnMapper.lsnToFilePosition(0, FILE_SIZE);
            assertEquals(0, pos.fileIndex());
            assertEquals(HEADER_2KB, pos.offsetInFile());
        }

        @Test
        @DisplayName("LSN should wrap around between two files")
        void lsnWrapsAround() {
            long usablePerFile = FILE_SIZE - HEADER_2KB;

            // LSN 在第一个文件的末尾
            LsnMapper.FilePosition pos1 = LsnMapper.lsnToFilePosition(usablePerFile - 512, FILE_SIZE);
            assertEquals(0, pos1.fileIndex());

            // LSN 在第二个文件的开头
            LsnMapper.FilePosition pos2 = LsnMapper.lsnToFilePosition(usablePerFile, FILE_SIZE);
            assertEquals(1, pos2.fileIndex());
            assertEquals(HEADER_2KB, pos2.offsetInFile());
        }

        @Test
        @DisplayName("LSN should cycle back to file 0 after filling both files")
        void lsnCyclesBackToFile0() {
            long usablePerFile = FILE_SIZE - HEADER_2KB;
            long totalCapacity = 2 * usablePerFile;

            // LSN = totalCapacity 应该回到 file 0
            LsnMapper.FilePosition pos = LsnMapper.lsnToFilePosition(totalCapacity, FILE_SIZE);
            assertEquals(0, pos.fileIndex());
            assertEquals(HEADER_2KB, pos.offsetInFile());
        }
    }

    // ==================== needSwitchFile 测试 ====================

    @Nested
    @DisplayName("needSwitchFile Tests")
    class NeedSwitchFileTests {

        private static final long FILE_SIZE = 16 * 1024 * 1024;  // 16MB

        @Test
        @DisplayName("Should not switch file when plenty of space")
        void noSwitchWhenPlentyOfSpace() {
            // 在文件开头，不需要切换
            assertFalse(LsnMapper.needSwitchFile(0, BLOCK_SIZE, FILE_SIZE));
        }

        @Test
        @DisplayName("Should switch file when near file end")
        void switchWhenNearEnd() {
            long usablePerFile = FILE_SIZE - LsnMapper.CHECKPOINT_HEADER_SIZE;
            // 在文件末尾只剩 256 bytes 时，需要切换
            long lsnNearEnd = usablePerFile - 256;
            assertTrue(LsnMapper.needSwitchFile(lsnNearEnd, BLOCK_SIZE, FILE_SIZE));
        }
    }

    // ==================== Block 对齐测试 ====================

    @Nested
    @DisplayName("Block Alignment Tests")
    class BlockAlignmentTests {

        @Test
        @DisplayName("isBlockAligned should detect aligned LSNs")
        void isBlockAlignedWorks() {
            assertTrue(LsnMapper.isBlockAligned(0));
            assertTrue(LsnMapper.isBlockAligned(512));
            assertTrue(LsnMapper.isBlockAligned(1024));
            assertTrue(LsnMapper.isBlockAligned(512 * 100));

            assertFalse(LsnMapper.isBlockAligned(1));
            assertFalse(LsnMapper.isBlockAligned(12));
            assertFalse(LsnMapper.isBlockAligned(500));
        }

        @Test
        @DisplayName("alignToNextBlock should round up correctly")
        void alignToNextBlockWorks() {
            assertEquals(0, LsnMapper.alignToNextBlock(0));
            assertEquals(512, LsnMapper.alignToNextBlock(1));
            assertEquals(512, LsnMapper.alignToNextBlock(12));
            assertEquals(512, LsnMapper.alignToNextBlock(500));
            assertEquals(512, LsnMapper.alignToNextBlock(511));
            assertEquals(512, LsnMapper.alignToNextBlock(512));
            assertEquals(1024, LsnMapper.alignToNextBlock(513));
        }
    }
}
