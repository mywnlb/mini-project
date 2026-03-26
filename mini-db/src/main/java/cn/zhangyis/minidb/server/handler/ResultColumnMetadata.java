package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.protocol.TypeMapping;
import cn.zhangyis.minidb.server.protocol.packets.ColumnDefinitionPacket;

/**
 * 结果列元数据：协议列定义 + 从执行结果 Row 取值所需的 lookup key。
 */
public record ResultColumnMetadata(String lookupKey, ColumnDefinitionPacket definition) {

    public static ResultColumnMetadata of(String lookupKey, String schema,
                                          String table, String orgTable,
                                          String name, String orgName,
                                          int mysqlType, int flags) {
        return of(lookupKey, schema, table, orgTable, name, orgName,
                mysqlType, flags, TypeMapping.defaultColumnLength(mysqlType));
    }

    public static ResultColumnMetadata of(String lookupKey, String schema,
                                          String table, String orgTable,
                                          String name, String orgName,
                                          int mysqlType, int flags,
                                          int columnLength) {
        ColumnDefinitionPacket definition = new ColumnDefinitionPacket.Builder()
                .schema(schema != null ? schema : "")
                .table(table != null ? table : "")
                .orgTable(orgTable != null ? orgTable : "")
                .name(name != null ? name : "")
                .orgName(orgName != null ? orgName : "")
                .columnType(mysqlType)
                .flags(flags)
                .columnLength(columnLength)
                .build();
        return new ResultColumnMetadata(lookupKey, definition);
    }

    public int mysqlType() {
        return definition.columnType();
    }
}
