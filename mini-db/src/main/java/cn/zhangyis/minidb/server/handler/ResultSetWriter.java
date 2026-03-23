package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.ConnectionSession;
import cn.zhangyis.minidb.server.netty.PacketWriter;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.TypeMapping;
import cn.zhangyis.minidb.server.protocol.packets.*;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.exec.Row;

import java.util.*;

/**
 * 文本协议结果集写入器。
 *
 * <p>将 SQL 引擎返回的 {@code List<Row>} 编码为 MySQL 文本协议结果集：
 * 列数量包 → 列定义包 × N → EOF → 行数据包 × M → EOF。</p>
 *
 * <p>设计模式：门面模式——封装结果集编码的完整流程，
 * 上层只需调用 {@link #write} 一个方法。</p>
 *
 * <p>列类型推断策略：
 * <ol>
 *   <li>优先从 CatalogSpi 获取 ColumnMeta（已知表的列有精确类型）</li>
 *   <li>回退：从第一行的 Java Object 类型推断 MySQL 类型</li>
 * </ol></p>
 */
public class ResultSetWriter {

    /**
     * 将结果集写入客户端。
     *
     * @param rows    查询结果行
     * @param session 连接会话（用于获取列元数据和状态）
     * @param writer  包写入器
     */
    public static void write(List<Row> rows, ConnectionSession session, PacketWriter writer) {
        if (rows.isEmpty()) {
            writeEmptyResultSet(session, writer);
            return;
        }

        // 从第一行提取列名（LinkedHashMap 保序）
        Row firstRow = rows.get(0);
        List<String> columnNames = new ArrayList<>(firstRow.columns().keySet());
        int columnCount = columnNames.size();
        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        // 列数量包
        writer.writeColumnCount(columnCount);

        // 列定义包
        for (String colName : columnNames) {
            ColumnDefinitionPacket colDef = buildColumnDefinition(colName, firstRow.get(colName), session);
            writer.writeColumnDefinition(colDef);
        }

        // EOF（列定义结束）
        writer.writeEof(new EofPacket(0, statusFlags));

        // 行数据包
        for (Row row : rows) {
            List<String> values = new ArrayList<>(columnCount);
            for (String colName : columnNames) {
                Object val = row.get(colName);
                values.add(val == null ? null : val.toString());
            }
            writer.writeResultSetRow(new ResultSetRowPacket(values));
        }

        // EOF（行数据结束）
        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }

    /**
     * 构建单列的 ColumnDefinitionPacket。
     */
    private static ColumnDefinitionPacket buildColumnDefinition(String rawColName, Object sampleValue,
                                                                 ConnectionSession session) {
        // 分离 table.column 格式
        String table = "";
        String name = rawColName;
        int dotIdx = rawColName.indexOf('.');
        if (dotIdx > 0) {
            table = rawColName.substring(0, dotIdx);
            name = rawColName.substring(dotIdx + 1);
        }

        // 推断 MySQL 列类型
        int mysqlType = inferColumnType(name, table, sampleValue, session);
        int columnLength = TypeMapping.defaultColumnLength(mysqlType);

        // 列标志
        int flags = 0;
        ColumnMeta meta = findColumnMeta(name, table, session);
        if (meta != null) {
            if (meta.isPrimaryKey()) flags |= MysqlConstants.COLUMN_FLAG_PRI_KEY;
            if (!meta.nullable()) flags |= MysqlConstants.COLUMN_FLAG_NOT_NULL;
        }

        return new ColumnDefinitionPacket.Builder()
                .table(table).orgTable(table)
                .name(name).orgName(name)
                .columnType(mysqlType)
                .columnLength(columnLength)
                .flags(flags)
                .build();
    }

    /**
     * 推断列的 MySQL 类型码。优先使用 catalog 元数据，回退到 Java 类型推断。
     */
    private static int inferColumnType(String name, String table, Object sampleValue,
                                        ConnectionSession session) {
        ColumnMeta meta = findColumnMeta(name, table, session);
        if (meta != null) {
            return TypeMapping.toMysqlType(meta.type());
        }
        return TypeMapping.inferMysqlType(sampleValue);
    }

    /**
     * 从 catalog 查找列元数据。
     */
    private static ColumnMeta findColumnMeta(String name, String table, ConnectionSession session) {
        if (table.isEmpty() || session.catalog() == null) {
            return null;
        }
        try {
            var columns = session.catalog().getColumns(table);
            if (columns != null) {
                for (var col : columns) {
                    if (col.name().equalsIgnoreCase(name)) {
                        return col;
                    }
                }
            }
        } catch (Exception ignored) {
            // 表不存在等异常：降级为类型推断
        }
        return null;
    }

    /**
     * 写入空结果集（无列无行）。
     */
    private static void writeEmptyResultSet(ConnectionSession session, PacketWriter writer) {
        int statusFlags = StatusFlagBuilder.build(session.executionContext());
        writer.writeOk(OkPacket.ok(statusFlags));
        writer.flush();
    }
}
