package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.TypeMapping;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * MySQL 列定义包（Column Definition）。
 *
 * <p>结果集中每一列的元数据，包括列名、表名、类型、长度等。
 * 客户端据此解析后续的行数据。</p>
 *
 * <p>设计模式：Builder 模式——列定义字段较多，使用 Builder
 * 避免构造器参数过长导致的可读性问题。</p>
 */
public class ColumnDefinitionPacket {

    private final String catalog;
    private final String schema;
    private final String table;
    private final String orgTable;
    private final String name;
    private final String orgName;
    private final int charset;
    private final int columnLength;
    private final int columnType;
    private final int flags;
    private final int decimals;

    private ColumnDefinitionPacket(Builder builder) {
        this.catalog = builder.catalog;
        this.schema = builder.schema;
        this.table = builder.table;
        this.orgTable = builder.orgTable;
        this.name = builder.name;
        this.orgName = builder.orgName;
        this.charset = builder.charset;
        this.columnLength = builder.columnLength;
        this.columnType = builder.columnType;
        this.flags = builder.flags;
        this.decimals = builder.decimals;
    }

    /**
     * 将列定义写入 ByteBuf。
     *
     * <p>包结构：
     * <pre>
     * lenenc  catalog ("def")
     * lenenc  schema
     * lenenc  table (虚拟表名)
     * lenenc  org_table (原始表名)
     * lenenc  name (列别名)
     * lenenc  org_name (原始列名)
     * 1       filler (0x0c)
     * 2       character_set
     * 4       column_length
     * 1       column_type
     * 2       flags
     * 1       decimals
     * 2       filler (0x0000)
     * </pre></p>
     */
    public void writeTo(ByteBuf buf) {
        MysqlBufUtil.writeLengthEncodedString(buf, catalog, StandardCharsets.UTF_8);
        MysqlBufUtil.writeLengthEncodedString(buf, schema, StandardCharsets.UTF_8);
        MysqlBufUtil.writeLengthEncodedString(buf, table, StandardCharsets.UTF_8);
        MysqlBufUtil.writeLengthEncodedString(buf, orgTable, StandardCharsets.UTF_8);
        MysqlBufUtil.writeLengthEncodedString(buf, name, StandardCharsets.UTF_8);
        MysqlBufUtil.writeLengthEncodedString(buf, orgName, StandardCharsets.UTF_8);
        buf.writeByte(0x0C); // length of fixed-length fields
        MysqlBufUtil.writeFixedLengthInt(buf, charset, 2);
        MysqlBufUtil.writeFixedLengthInt(buf, columnLength, 4);
        buf.writeByte(columnType);
        MysqlBufUtil.writeFixedLengthInt(buf, flags, 2);
        buf.writeByte(decimals);
        buf.writeZero(2); // filler
    }

    /**
     * 从 ByteBuf 解码列定义（JDBC 驱动客户端使用）。
     */
    public static ColumnDefinitionPacket decode(ByteBuf buf) {
        String cat = MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8);
        String sch = MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8);
        String tbl = MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8);
        String orgTbl = MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8);
        String nm = MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8);
        String orgNm = MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8);
        buf.skipBytes(1); // filler 0x0c
        int cs = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        int colLen = (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
        int colType = buf.readByte() & 0xFF;
        int flg = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        int dec = buf.readByte() & 0xFF;
        buf.skipBytes(2); // filler

        return new Builder()
                .catalog(cat).schema(sch).table(tbl).orgTable(orgTbl)
                .name(nm).orgName(orgNm).charset(cs)
                .columnLength(colLen).columnType(colType).flags(flg).decimals(dec)
                .build();
    }

    public String name() { return name; }
    public String table() { return table; }
    public int columnType() { return columnType; }
    public int flags() { return flags; }
    public int columnLength() { return columnLength; }
    public int charset() { return charset; }
    public int decimals() { return decimals; }

    // ==================== Builder ====================

    public static class Builder {
        private String catalog = "def";
        private String schema = "";
        private String table = "";
        private String orgTable = "";
        private String name = "";
        private String orgName = "";
        private int charset = MysqlConstants.CHARSET_UTF8MB4;
        private int columnLength = 255;
        private int columnType = MysqlConstants.MYSQL_TYPE_VAR_STRING;
        private int flags = 0;
        private int decimals = 0;

        public Builder catalog(String catalog) { this.catalog = catalog; return this; }
        public Builder schema(String schema) { this.schema = schema; return this; }
        public Builder table(String table) { this.table = table; return this; }
        public Builder orgTable(String orgTable) { this.orgTable = orgTable; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder orgName(String orgName) { this.orgName = orgName; return this; }
        public Builder charset(int charset) { this.charset = charset; return this; }
        public Builder columnLength(int columnLength) { this.columnLength = columnLength; return this; }
        public Builder columnType(int columnType) { this.columnType = columnType; return this; }
        public Builder flags(int flags) { this.flags = flags; return this; }
        public Builder decimals(int decimals) { this.decimals = decimals; return this; }

        public ColumnDefinitionPacket build() {
            return new ColumnDefinitionPacket(this);
        }
    }
}
