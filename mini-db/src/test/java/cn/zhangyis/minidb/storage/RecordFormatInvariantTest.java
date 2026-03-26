package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.record.format.FieldOffsets;
import cn.zhangyis.minidb.storage.record.format.RecordFormat;
import cn.zhangyis.minidb.storage.record.format.RecordFormatFactory;
import cn.zhangyis.minidb.storage.record.format.RecordFormatType;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class RecordFormatInvariantTest {

    @Test
    void compactFormat_roundTripsNullOffsetsAndSystemColumns() {
        RecordSchema schema = RecordSchema.builder()
                .version(7)
                .column("id", FieldKind.INT, false)
                .column("name", FieldKind.VARCHAR, 64, true)
                .column("score", FieldKind.BIGINT, false)
                .build();
        SystemLayout layout = SystemLayout.WITH_PK;
        RecordFormat format = RecordFormatFactory.create(RecordFormatType.COMPACT);
        DataTuple tuple = DataTuple.fromValues(schema, 123, null, 456L);

        ByteBuffer buffer = ByteBuffer.allocate(512);
        int recStart = 160;
        long rollPtr = RollbackPointer.forUpdate(3, 9, 17).encode();

        int extraBytes = format.encodeTo(buffer, recStart, tuple, schema, layout, 11L, rollPtr, 7, 0L);
        FieldOffsets offsets = format.parseOffsets(buffer, recStart, schema, layout);
        DataTuple decoded = format.decode(buffer, recStart, offsets, schema, layout);

        assertEquals(tuple, decoded);
        assertTrue(offsets.isNull(1));
        assertEquals(extraBytes, offsets.getExtraBytesBeforeHeader());
        assertEquals(7, format.peekRowVersion(buffer, recStart));
        assertArrayEquals(new long[]{11L, rollPtr, 7L, 0L},
                format.readSystemColumns(buffer, recStart, layout));
    }

    @Test
    void dynamicFormat_roundTripsAndPreservesRowId() {
        RecordSchema schema = RecordSchema.builder()
                .version(9)
                .column("payload", FieldKind.VARBINARY, 32, false)
                .column("note", FieldKind.VARCHAR, 255, true)
                .build();
        SystemLayout layout = SystemLayout.WITHOUT_PK;
        RecordFormat format = RecordFormatFactory.create(RecordFormatType.DYNAMIC);
        DataTuple tuple = DataTuple.fromValues(schema, new byte[]{1, 2, 3}, "hello");

        ByteBuffer buffer = ByteBuffer.allocate(512);
        int recStart = 192;
        long rollPtr = RollbackPointer.forInsert(1, 12, 99).encode();

        format.encodeTo(buffer, recStart, tuple, schema, layout, 21L, rollPtr, 9, 88L);
        FieldOffsets offsets = format.parseOffsets(buffer, recStart, schema, layout);
        DataTuple decoded = format.decode(buffer, recStart, offsets, schema, layout);

        assertEquals(tuple, decoded);
        assertEquals(layout.userColumnsOffset(), offsets.getUserColumnsOffset());
        assertEquals(9, format.peekRowVersion(buffer, recStart));
        assertArrayEquals(new long[]{21L, rollPtr, 9L, 88L},
                format.readSystemColumns(buffer, recStart, layout));
    }

    @Test
    void unsupportedFormats_areRejected() {
        assertThrows(UnsupportedOperationException.class,
                () -> RecordFormatFactory.create(RecordFormatType.REDUNDANT));
        assertThrows(UnsupportedOperationException.class,
                () -> RecordFormatFactory.create(RecordFormatType.COMPRESSED));
    }
}
