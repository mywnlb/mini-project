package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.RelInsert;
import cn.zhangyis.minidb.sql.types.TypeCoercion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INSERT 执行器：将 VALUES 行转为 Row 插入 MockDataSource
 */
public class InsertExec implements ExecNode {
    private final RelInsert relInsert;
    private final DataSourceSpi dataSource;
    private int affectedRows;
    private boolean returned;

    public InsertExec(RelInsert relInsert) {
        this(relInsert, MockDataSourceAdapter.INSTANCE);
    }

    public InsertExec(RelInsert relInsert, DataSourceSpi dataSource) {
        this.relInsert = relInsert;
        this.dataSource = dataSource;
    }

    @Override
    public void open() {
        String tableName = relInsert.tableName();
        TableMeta meta = relInsert.tableMeta();

        // 确定列名列表
        List<String> colNames;
        if (relInsert.columns().size() > 0) {
            colNames = relInsert.columns().nodes().stream()
                .map(n -> ((SqlIdentifier) n).name())
                .toList();
        } else {
            colNames = meta.columns().stream()
                .map(ColumnMeta::name)
                .toList();
        }

        // 构建列类型映射
        List<ColumnMeta> allColumns = meta.columns();

        // 逐行插入
        affectedRows = 0;
        for (SqlNode rowNode : relInsert.valueRows().nodes()) {
            SqlNodeList rowList = (SqlNodeList) rowNode;
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < colNames.size(); i++) {
                String colName = colNames.get(i);
                String qualifiedName = tableName + "." + colName;
                Object rawValue = resolveValue(rowList.get(i));

                // 查找列类型并做类型校验和转换
                ColumnMeta colMeta = findColumn(allColumns, colName);
                if (colMeta != null) {
                    rawValue = TypeCoercion.coerce(rawValue, colMeta.type(), colName);
                }
                map.put(qualifiedName, rawValue);
            }
            dataSource.insertRow(tableName, new Row(map));
            affectedRows++;
        }
        returned = false;
    }

    @Override
    public Row next() {
        if (!returned) {
            returned = true;
            return new Row(Map.of("affected_rows", affectedRows));
        }
        return null;
    }

    @Override
    public void close() {}

    private Object resolveValue(SqlNode node) {
        return FilterExec.resolveValue(node, new Row(Map.of()));
    }

    private ColumnMeta findColumn(List<ColumnMeta> columns, String name) {
        return columns.stream()
            .filter(c -> c.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }
}
