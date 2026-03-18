package cn.zhangyis.minidb.storage.transaction.dml;

import cn.zhangyis.minidb.storage.btree.SimpleRecordBuilder;
import cn.zhangyis.minidb.storage.record.RecordHeader;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Encodes clustered primary-index leaf records as:
 * outer-header | primary-key | extra-bytes(u16) | row-blob-length(u16) | row-blob
 *
 * <p>The outer header keeps B+Tree search semantics stable, while the metadata
 * makes the inner row {@code recStart} recoverable without guessing offsets.</p>
 */
public final class ClusteredLeafRecordCodec {

    private static final int OUTER_HEADER_SIZE = SimpleRecordBuilder.RECORD_HEADER_SIZE;
    private static final int EXTRA_BYTES_SIZE = Short.BYTES;
    private static final int ROW_BLOB_LENGTH_SIZE = Short.BYTES;
    private static final int METADATA_SIZE = EXTRA_BYTES_SIZE + ROW_BLOB_LENGTH_SIZE;

    private ClusteredLeafRecordCodec() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static byte[] encode(byte[] primaryKey, byte[] rowBlob, int extraBytes) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        Objects.requireNonNull(rowBlob, "rowBlob");
        if (extraBytes < 0 || extraBytes > rowBlob.length) {
            throw new IllegalArgumentException("Invalid extraBytes: " + extraBytes);
        }
        if (rowBlob.length > 0xFFFF) {
            throw new IllegalArgumentException("rowBlob is too large: " + rowBlob.length);
        }

        byte[] record = new byte[OUTER_HEADER_SIZE + primaryKey.length + METADATA_SIZE + rowBlob.length];
        ByteBuffer buffer = ByteBuffer.wrap(record);

        RecordHeader header = new RecordHeader();
        header.setRecType(RecordHeader.REC_ORDINARY);
        header.setHeapNo(2);
        header.writeTo(buffer, 0);

        buffer.position(OUTER_HEADER_SIZE);
        buffer.put(primaryKey);
        buffer.putShort((short) extraBytes);
        buffer.putShort((short) rowBlob.length);
        buffer.put(rowBlob);

        return record;
    }

    public static LeafRecord locate(ByteBuffer buffer, int recordOffset, int keyLength) {
        int metadataOffset = recordOffset + OUTER_HEADER_SIZE + keyLength;
        int extraBytes = buffer.getShort(metadataOffset) & 0xFFFF;
        int rowBlobLength = buffer.getShort(metadataOffset + EXTRA_BYTES_SIZE) & 0xFFFF;
        if (extraBytes > rowBlobLength) {
            throw new IllegalStateException("extraBytes exceeds rowBlobLength");
        }

        int rowBlobOffset = metadataOffset + METADATA_SIZE;
        int rowRecStart = rowBlobOffset + extraBytes;
        return new LeafRecord(recordOffset, keyLength, metadataOffset, extraBytes, rowBlobLength, rowBlobOffset, rowRecStart);
    }

    public record LeafRecord(
        int recordOffset,
        int keyLength,
        int metadataOffset,
        int extraBytes,
        int rowBlobLength,
        int rowBlobOffset,
        int rowRecStart
    ) {
        public int totalRecordLength() {
            return OUTER_HEADER_SIZE + keyLength + METADATA_SIZE + rowBlobLength;
        }
    }
}
