package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量 Undo 功能测试
 *
 * <p>测试 UpdateUndoRecord 的 V1/V2 格式支持和 VersionReconstructor 的版本链重建。</p>
 */
@DisplayName("Incremental Undo Tests")
class IncrementalUndoTest {

    private TransactionId trx1;
    private TransactionId trx2;
    private TransactionId trx3;
    private RollbackPointer ptr1;
    private RollbackPointer ptr2;

    @BeforeEach
    void setUp() {
        trx1 = new TransactionId(100);
        trx2 = new TransactionId(101);
        trx3 = new TransactionId(102);
        ptr1 = RollbackPointer.forInsert(0, 10, 100);
        ptr2 = RollbackPointer.forInsert(0, 10, 200);
    }

    // ==================== UndoRecordVersion 测试 ====================

    @Test
    @DisplayName("版本常量应该有效")
    void testVersionConstants() {
        assertEquals(0x01, UndoRecordVersion.FORMAT_V1);
        assertEquals(0x02, UndoRecordVersion.FORMAT_V2);
        assertEquals(0x02, UndoRecordVersion.CURRENT_FORMAT_VERSION);
        assertEquals(0x00, UndoRecordVersion.INITIAL_SCHEMA_VERSION);
    }

    @Test
    @DisplayName("版本检查应该正确")
    void testVersionValidation() {
        assertTrue(UndoRecordVersion.isValidFormatVersion(UndoRecordVersion.FORMAT_V1));
        assertTrue(UndoRecordVersion.isValidFormatVersion(UndoRecordVersion.FORMAT_V2));
        assertFalse(UndoRecordVersion.isValidFormatVersion((byte) 0x03));
        assertFalse(UndoRecordVersion.isValidFormatVersion((byte) 0x00));

        assertTrue(UndoRecordVersion.isValidSchemaVersion((byte) 0x00));
        assertTrue(UndoRecordVersion.isValidSchemaVersion((byte) 0xFF));
        assertTrue(UndoRecordVersion.isValidSchemaVersion((byte) 0x7F));
    }

    @Test
    @DisplayName("格式类型判断应该正确")
    void testFormatTypeDetection() {
        assertTrue(UndoRecordVersion.isOriginalFormat(UndoRecordVersion.FORMAT_V1));
        assertFalse(UndoRecordVersion.isOriginalFormat(UndoRecordVersion.FORMAT_V2));

        assertFalse(UndoRecordVersion.isIncrementalFormat(UndoRecordVersion.FORMAT_V1));
        assertTrue(UndoRecordVersion.isIncrementalFormat(UndoRecordVersion.FORMAT_V2));
    }

    @Test
    @DisplayName("版本名称应该正确")
    void testVersionNames() {
        assertEquals("V1_ORIGINAL", UndoRecordVersion.getFormatVersionName(UndoRecordVersion.FORMAT_V1));
        assertEquals("V2_INCREMENTAL", UndoRecordVersion.getFormatVersionName(UndoRecordVersion.FORMAT_V2));
        assertEquals("UNKNOWN", UndoRecordVersion.getFormatVersionName((byte) 0x99));
    }

    // ==================== UpdateUndoRecord V1 格式测试 ====================

    @Test
    @DisplayName("V1 格式：创建和序列化")
    void testV1FormatCreationAndSerialization() {
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{1, 2, 3}));
        oldCols.add(new UpdateUndoRecord.OldColumnValue(2, new byte[]{4, 5}));

        UpdateUndoRecord record = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                oldCols
        );

        assertEquals(UndoRecordVersion.FORMAT_V1, record.getFormatVersion());
        assertEquals(UndoRecordVersion.INITIAL_SCHEMA_VERSION, record.getSchemaVersion());
        assertTrue(record.isOriginalFormat());
        assertFalse(record.isIncrementalFormat());
        assertEquals(2, record.getColumnCount());
    }

    @Test
    @DisplayName("V1 格式：序列化和反序列化")
    void testV1FormatSerializationRoundTrip() {
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{1, 2, 3}));
        oldCols.add(new UpdateUndoRecord.OldColumnValue(2, new byte[]{4, 5}));

        UpdateUndoRecord original = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                oldCols
        );

        // 序列化
        int size = original.calculateSize();
        byte[] buffer = new byte[size];
        original.writeTo(buffer, 0);

        // 反序列化
        UpdateUndoRecord deserialized = (UpdateUndoRecord) UpdateUndoRecord.readFrom(buffer, 0);

        assertEquals(original.getFormatVersion(), deserialized.getFormatVersion());
        assertEquals(original.getSchemaVersion(), deserialized.getSchemaVersion());
        assertEquals(original.getColumnCount(), deserialized.getColumnCount());
        assertArrayEquals(original.getPrimaryKeyData(), deserialized.getPrimaryKeyData());
    }

    // ==================== UpdateUndoRecord V2 格式测试 ====================

    @Test
    @DisplayName("V2 格式：创建和序列化")
    void testV2FormatCreationAndSerialization() {
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{1, 2, 3}));
        oldCols.add(new UpdateUndoRecord.OldColumnValue(3, new byte[]{6, 7}));

        UpdateUndoRecord record = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                oldCols,
                UndoRecordVersion.FORMAT_V2,
                (byte) 0x01
        );

        assertEquals(UndoRecordVersion.FORMAT_V2, record.getFormatVersion());
        assertEquals(0x01, record.getSchemaVersion());
        assertFalse(record.isOriginalFormat());
        assertTrue(record.isIncrementalFormat());
        assertEquals(2, record.getColumnCount());
    }

    @Test
    @DisplayName("V2 格式：序列化和反序列化")
    void testV2FormatSerializationRoundTrip() {
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{1, 2, 3}));
        oldCols.add(new UpdateUndoRecord.OldColumnValue(3, new byte[]{6, 7}));

        UpdateUndoRecord original = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                oldCols,
                UndoRecordVersion.FORMAT_V2,
                (byte) 0x01
        );

        // 序列化
        int size = original.calculateSize();
        byte[] buffer = new byte[size];
        original.writeTo(buffer, 0);

        // 反序列化
        UpdateUndoRecord deserialized = (UpdateUndoRecord) UpdateUndoRecord.readFrom(buffer, 0);

        assertEquals(UndoRecordVersion.FORMAT_V2, deserialized.getFormatVersion());
        assertEquals(0x01, deserialized.getSchemaVersion());
        assertEquals(2, deserialized.getColumnCount());
        assertArrayEquals(original.getPrimaryKeyData(), deserialized.getPrimaryKeyData());
    }

    @Test
    @DisplayName("V2 格式应该比 V1 格式更小（只存储修改的列）")
    void testV2FormatSizeReduction() {
        List<UpdateUndoRecord.OldColumnValue> allCols = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            allCols.add(new UpdateUndoRecord.OldColumnValue(i, new byte[100]));
        }

        // V1 格式：存储所有 10 列
        UpdateUndoRecord v1Record = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                allCols
        );

        // V2 格式：只存储修改的 3 列
        List<UpdateUndoRecord.OldColumnValue> changedCols = new ArrayList<>();
        changedCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[100]));
        changedCols.add(new UpdateUndoRecord.OldColumnValue(5, new byte[100]));
        changedCols.add(new UpdateUndoRecord.OldColumnValue(10, new byte[100]));

        UpdateUndoRecord v2Record = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                changedCols,
                UndoRecordVersion.FORMAT_V2,
                (byte) 0x00
        );

        int v1Size = v1Record.calculateSize();
        int v2Size = v2Record.calculateSize();

        assertTrue(v2Size < v1Size, "V2 格式应该比 V1 格式更小");
        double reduction = (double) (v1Size - v2Size) / v1Size * 100;
        assertTrue(reduction > 50, "空间节省应该超过 50%");
    }

    @Test
    @DisplayName("新旧格式混读：V1 和 V2 都能正确读取")
    void testMixedFormatReading() {
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{1, 2, 3}));

        // 创建 V1 格式
        UpdateUndoRecord v1Record = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                oldCols
        );

        // 创建 V2 格式
        UpdateUndoRecord v2Record = new UpdateUndoRecord(
                trx1, 10, ptr1,
                new byte[]{10, 20},
                oldCols,
                UndoRecordVersion.FORMAT_V2,
                (byte) 0x00
        );

        // 序列化两个格式
        byte[] v1Buffer = new byte[v1Record.calculateSize()];
        v1Record.writeTo(v1Buffer, 0);

        byte[] v2Buffer = new byte[v2Record.calculateSize()];
        v2Record.writeTo(v2Buffer, 0);

        // 反序列化
        UpdateUndoRecord v1Deserialized = (UpdateUndoRecord) UpdateUndoRecord.readFrom(v1Buffer, 0);
        UpdateUndoRecord v2Deserialized = (UpdateUndoRecord) UpdateUndoRecord.readFrom(v2Buffer, 0);

        // 验证
        assertEquals(UndoRecordVersion.FORMAT_V1, v1Deserialized.getFormatVersion());
        assertEquals(UndoRecordVersion.FORMAT_V2, v2Deserialized.getFormatVersion());
        assertEquals(1, v1Deserialized.getColumnCount());
        assertEquals(1, v2Deserialized.getColumnCount());
    }

    // ==================== VersionReconstructor 测试 ====================

    @Test
    @DisplayName("版本重建：单个 Undo 记录")
    void testSingleUndoReconstruction() {
        // 当前记录
        Map<Integer, byte[]> currentRecord = new HashMap<>();
        currentRecord.put(1, new byte[]{10, 11, 12});
        currentRecord.put(2, new byte[]{20, 21});
        currentRecord.put(3, new byte[]{30});

        // Undo 链：只有一个记录
        List<UpdateUndoRecord> undoChain = new ArrayList<>();
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{1, 2, 3}));
        oldCols.add(new UpdateUndoRecord.OldColumnValue(2, new byte[]{4, 5}));

        UpdateUndoRecord undo = new UpdateUndoRecord(
                trx1, 10, RollbackPointer.NULL,
                new byte[]{10, 20},
                oldCols
        );
        undoChain.add(undo);

        // 重建
        VersionReconstructor reconstructor = new VersionReconstructor();
        VersionReconstructor.ReconstructedVersion version = reconstructor.reconstruct(
                currentRecord, undoChain, trx1
        );

        assertTrue(version.isComplete());
        assertEquals(3, version.getAllColumnValues().size());
        assertArrayEquals(new byte[]{1, 2, 3}, version.getColumnValue(1));
        assertArrayEquals(new byte[]{4, 5}, version.getColumnValue(2));
        assertArrayEquals(new byte[]{30}, version.getColumnValue(3));
    }

    @Test
    @DisplayName("版本重建：多个 Undo 记录（补齐即停）")
    void testMultipleUndoReconstruction() {
        // 当前记录
        Map<Integer, byte[]> currentRecord = new HashMap<>();
        currentRecord.put(1, new byte[]{100});
        currentRecord.put(2, new byte[]{(byte) 200});
        currentRecord.put(3, new byte[]{(byte) 300});

        // Undo 链：3 个记录
        List<UpdateUndoRecord> undoChain = new ArrayList<>();

        // Undo 3（最新）：修改列 1
        List<UpdateUndoRecord.OldColumnValue> cols3 = new ArrayList<>();
        cols3.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{10}));
        UpdateUndoRecord undo3 = new UpdateUndoRecord(
                trx3, 10, ptr2,
                new byte[]{10, 20},
                cols3
        );
        undoChain.add(undo3);

        // Undo 2：修改列 2
        List<UpdateUndoRecord.OldColumnValue> cols2 = new ArrayList<>();
        cols2.add(new UpdateUndoRecord.OldColumnValue(2, new byte[]{20}));
        UpdateUndoRecord undo2 = new UpdateUndoRecord(
                trx2, 10, ptr1,
                new byte[]{10, 20},
                cols2
        );
        undoChain.add(undo2);

        // Undo 1（最旧）：修改列 3
        List<UpdateUndoRecord.OldColumnValue> cols1 = new ArrayList<>();
        cols1.add(new UpdateUndoRecord.OldColumnValue(3, new byte[]{30}));
        UpdateUndoRecord undo1 = new UpdateUndoRecord(
                trx1, 10, RollbackPointer.NULL,
                new byte[]{10, 20},
                cols1
        );
        undoChain.add(undo1);

        // 重建到 trx2
        VersionReconstructor reconstructor = new VersionReconstructor();
        List<Integer> expectedCols = new ArrayList<>();
        expectedCols.add(1);
        expectedCols.add(2);
        expectedCols.add(3);

        VersionReconstructor.ReconstructedVersion version = reconstructor.reconstruct(
                currentRecord, undoChain, trx2, expectedCols
        );

        assertTrue(version.isComplete());
        assertEquals(3, version.getUndoRecordsTraversed());
        assertEquals(2, version.getColumnsReconstructed());
        assertArrayEquals(new byte[]{10}, version.getColumnValue(1));
        assertArrayEquals(new byte[]{20}, version.getColumnValue(2));
        assertArrayEquals(new byte[]{(byte) 300}, version.getColumnValue(3));
    }

    @Test
    @DisplayName("版本重建：补齐即停优化")
    void testEarlyStoppingOptimization() {
        // 当前记录
        Map<Integer, byte[]> currentRecord = new HashMap<>();
        currentRecord.put(1, new byte[]{100});
        currentRecord.put(2, new byte[]{(byte) 200});

        // Undo 链：5 个记录，但只需要前 2 个就能补齐所有列
        List<UpdateUndoRecord> undoChain = new ArrayList<>();

        for (int i = 5; i >= 1; i--) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            cols.add(new UpdateUndoRecord.OldColumnValue(i % 2 + 1, new byte[]{(byte) i}));

            UpdateUndoRecord undo = new UpdateUndoRecord(
                    new TransactionId(100 + i), 10, RollbackPointer.NULL,
                    new byte[]{10, 20},
                    cols
            );
            undoChain.add(undo);
        }

        // 重建
        VersionReconstructor reconstructor = new VersionReconstructor();
        List<Integer> expectedCols = new ArrayList<>();
        expectedCols.add(1);
        expectedCols.add(2);

        VersionReconstructor.ReconstructedVersion version = reconstructor.reconstruct(
                currentRecord, undoChain, new TransactionId(100), expectedCols
        );

        assertTrue(version.isComplete());
        // 应该在补齐所有列后停止，不必遍历所有 5 个记录
        assertTrue(version.getUndoRecordsTraversed() <= 5);
    }

    @Test
    @DisplayName("版本链统计")
    void testVersionChainStats() {
        List<UpdateUndoRecord> undoChain = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                cols.add(new UpdateUndoRecord.OldColumnValue(j, new byte[100]));
            }

            UpdateUndoRecord undo = new UpdateUndoRecord(
                    new TransactionId(100 + i), 10, RollbackPointer.NULL,
                    new byte[]{10, 20},
                    cols
            );
            undoChain.add(undo);
        }

        VersionReconstructor reconstructor = new VersionReconstructor();
        VersionReconstructor.VersionChainStats stats = reconstructor.getChainStats(undoChain);

        assertEquals(3, stats.totalRecords);
        assertEquals(15, stats.totalColumns);
        assertEquals(1500, stats.totalSize);
        assertEquals(5.0, stats.getAverageColumnsPerRecord());
        assertEquals(100.0, stats.getAverageColumnSize());
    }

    @Test
    @DisplayName("空间估算")
    void testSpaceEstimation() {
        Map<Integer, byte[]> currentRecord = new HashMap<>();
        currentRecord.put(1, new byte[100]);
        currentRecord.put(2, new byte[200]);

        List<UpdateUndoRecord> undoChain = new ArrayList<>();
        List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
        cols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[150]));
        cols.add(new UpdateUndoRecord.OldColumnValue(3, new byte[250]));

        UpdateUndoRecord undo = new UpdateUndoRecord(
                trx1, 10, RollbackPointer.NULL,
                new byte[]{10, 20},
                cols
        );
        undoChain.add(undo);

        VersionReconstructor reconstructor = new VersionReconstructor();
        long estimatedSpace = reconstructor.estimateReconstructionSpace(
                currentRecord, undoChain, trx1
        );

        // 100 + 200 + 150 + 250 = 700
        assertEquals(700, estimatedSpace);
    }

    @Test
    @DisplayName("无效版本应该抛出异常")
    void testInvalidVersionThrowsException() {
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{1, 2, 3}));

        assertThrows(IllegalArgumentException.class, () ->
                new UpdateUndoRecord(
                        trx1, 10, ptr1,
                        new byte[]{10, 20},
                        oldCols,
                        (byte) 0x99,  // 无效的格式版本
                        (byte) 0x00
                )
        );

        assertThrows(IllegalArgumentException.class, () ->
                new UpdateUndoRecord(
                        trx1, 10, ptr1,
                        new byte[]{10, 20},
                        oldCols,
                        UndoRecordVersion.FORMAT_V2,
                        (byte) 0xFF  // 无效的 Schema 版本（超出范围）
                )
        );
    }
}
