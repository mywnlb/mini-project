package cn.zhangyis.minidb.sql.exec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全表扫描执行器
 */
public class ScanExec implements ExecNode {
    private final String tableName;
    private final String outputName;
    private final DataSourceSpi dataSource;
    private Iterator<Row> iterator;

    public ScanExec(String tableName) {
        this(tableName, tableName, MockDataSourceAdapter.INSTANCE);
    }

    public ScanExec(String tableName, String outputName) {
        this(tableName, outputName, MockDataSourceAdapter.INSTANCE);
    }

    public ScanExec(String tableName, String outputName, DataSourceSpi dataSource) {
        this.tableName = tableName;
        this.outputName = outputName;
        this.dataSource = dataSource;
    }

    @Override
    public void open() {
        List<Row> data = new ArrayList<>();
        Iterator<Row> srcIter = dataSource.scan(tableName);
        while (srcIter.hasNext()) {
            data.add(srcIter.next());
        }

        if (outputName.equalsIgnoreCase(tableName)) {
            iterator = data.iterator();
            return;
        }

        iterator = data.stream()
            .map(this::renameRowQualifier)
            .collect(Collectors.toList())
            .iterator();
    }

    @Override
    public Row next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        iterator = null;
    }

    private Row renameRowQualifier(Row row) {
        Map<String, Object> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.columns().entrySet()) {
            String key = entry.getKey();
            if (key.contains(".")) {
                String[] parts = key.split("\\.", 2);
                if (parts[0].equalsIgnoreCase(tableName)) {
                    key = outputName + "." + parts[1];
                }
            }
            renamed.put(key, entry.getValue());
        }
        return new Row(renamed);
    }
}
