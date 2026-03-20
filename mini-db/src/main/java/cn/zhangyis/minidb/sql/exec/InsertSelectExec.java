package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.RelInsertSelect;
import cn.zhangyis.minidb.sql.types.TypeCoercion;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INSERT INTO ... SELECT 执行器：流式逐行从 SELECT 读取并插入
 */
public class InsertSelectExec implements ExecNode {
    private final RelInsertSelect relInsertSelect;
    private final ExecNode selectExec;
    private final DataSourceSpi dataSource;
    private int affectedRows;
    private boolean returned;

    public InsertSelectExec(RelInsertSelect relInsertSelect, ExecNode selectExec, DataSourceSpi dataSource) {
        this.relInsertSelect = relInsertSelect;
        this.selectExec = selectExec;
        this.dataSource = dataSource;
    }

    @Override
    public void open() {
        String tableName = relInsertSelect.tableName();
        TableMeta meta = relInsertSelect.tableMeta();

        // 确定目标列名列表
        List<String> colNames;
        if (relInsertSelect.columns().size() > 0) {
            colNames = relInsertSelect.columns().nodes().stream()
                .map(n -> ((SqlIdentifier) n).name())
                .toList();
        } else {
            colNames = meta.columns().stream()
                .map(ColumnMeta::name)
                .toList();
        }

        List<ColumnMeta> allColumns = meta.columns();

        // 流式逐行：从 SELECT 读取一行 → 映射列 → 插入
        selectExec.open();
        affectedRows = 0;
        try {
            Row selectRow;
            while ((selectRow = selectExec.next()) != null) {
                Map<String, Object> map = new LinkedHashMap<>();
                // 按位置映射：SELECT 结果的第 i 列 → 目标表的第 i 列
                Iterator<Object> valIter = selectRow.columns().values().iterator();
                for (String colName : colNames) {
                    if (!valIter.hasNext()) break;
                    Object rawValue = valIter.next();
                    String qualifiedName = tableName + "." + colName;
                    ColumnMeta colMeta = findColumn(allColumns, colName);
                    if (colMeta != null) {
                        rawValue = TypeCoercion.coerce(rawValue, colMeta.type(), colName);
                    }
                    map.put(qualifiedName, rawValue);
                }
                dataSource.insertRow(tableName, new Row(map));
                affectedRows++;
            }
        } finally {
            selectExec.close();
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

    private ColumnMeta findColumn(List<ColumnMeta> columns, String name) {
        return columns.stream()
            .filter(c -> c.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }
}
