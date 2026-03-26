package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.ConnectionSession;
import cn.zhangyis.minidb.server.netty.PacketWriter;
import cn.zhangyis.minidb.server.protocol.TypeMapping;
import cn.zhangyis.minidb.server.protocol.packets.*;
import cn.zhangyis.minidb.sql.exec.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * 二进制协议结果集写入器。
 *
 * <p>与 {@link ResultSetWriter} 结构相同（列数量 → 列定义 → EOF → 行 → EOF），
 * 但行数据使用二进制编码（{@link BinaryResultSetRowPacket}）而非文本编码。</p>
 *
 * <p>二进制编码的优势：整数/浮点数无需字符串转换，网络传输更紧凑。</p>
 */
public class BinaryResultSetWriter {

    /**
     * 将结果集以二进制协议写入客户端。
     *
     * @param rows        查询结果行
     * @param columnTypes 每列的 MySQL 类型码（来自 PREPARE 阶段或推断）
     * @param session     连接会话
     * @param writer      包写入器
     */
    public static void write(List<Row> rows, List<Integer> columnTypes,
                              ConnectionSession session, PacketWriter writer) {
        writeInternal(rows, null, columnTypes, session, writer);
    }

    public static void writeWithMetadata(List<Row> rows, List<ResultColumnMetadata> metadata,
                                         ConnectionSession session, PacketWriter writer) {
        writeInternal(rows, metadata, List.of(), session, writer);
    }

    private static void writeInternal(List<Row> rows, List<ResultColumnMetadata> metadata,
                                      List<Integer> columnTypes,
                                      ConnectionSession session, PacketWriter writer) {
        if (metadata != null && !metadata.isEmpty()) {
            writeUsingMetadata(rows, metadata, session, writer);
            return;
        }
        if (rows.isEmpty()) {
            int statusFlags = StatusFlagBuilder.build(session.executionContext());
            writer.writeOk(OkPacket.ok(statusFlags));
            writer.flush();
            return;
        }

        Row firstRow = rows.get(0);
        List<String> columnNames = new ArrayList<>(firstRow.columns().keySet());
        int columnCount = columnNames.size();
        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        // 补齐列类型（如果列数与传入类型数不一致）
        List<Integer> types = new ArrayList<>(columnTypes);
        while (types.size() < columnCount) {
            Object sample = firstRow.get(columnNames.get(types.size()));
            types.add(TypeMapping.inferMysqlType(sample));
        }

        // 列数量包
        writer.writeColumnCount(columnCount);

        // 列定义包
        for (int i = 0; i < columnCount; i++) {
            String rawName = columnNames.get(i);
            String table = "";
            String name = rawName;
            int dotIdx = rawName.indexOf('.');
            if (dotIdx > 0) {
                table = rawName.substring(0, dotIdx);
                name = rawName.substring(dotIdx + 1);
            }
            writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                    .table(table).orgTable(table)
                    .name(name).orgName(name)
                    .columnType(types.get(i))
                    .columnLength(TypeMapping.defaultColumnLength(types.get(i)))
                    .build());
        }

        // EOF（列定义结束）
        writer.writeEof(new EofPacket(0, statusFlags));

        // 二进制行数据包
        for (Row row : rows) {
            List<Object> values = new ArrayList<>(columnCount);
            for (String colName : columnNames) {
                values.add(row.get(colName));
            }
            writer.writeBinaryResultSetRow(new BinaryResultSetRowPacket(values, types));
        }

        // EOF（行数据结束）
        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }

    private static void writeUsingMetadata(List<Row> rows, List<ResultColumnMetadata> metadata,
                                           ConnectionSession session, PacketWriter writer) {
        int statusFlags = StatusFlagBuilder.build(session.executionContext());
        List<Integer> types = metadata.stream().map(ResultColumnMetadata::mysqlType).toList();

        writer.writeColumnCount(metadata.size());
        for (ResultColumnMetadata column : metadata) {
            writer.writeColumnDefinition(column.definition());
        }
        writer.writeEof(new EofPacket(0, statusFlags));

        for (Row row : rows) {
            List<Object> values = new ArrayList<>(metadata.size());
            for (ResultColumnMetadata column : metadata) {
                values.add(row.get(column.lookupKey()));
            }
            writer.writeBinaryResultSetRow(new BinaryResultSetRowPacket(values, types));
        }

        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }
}
