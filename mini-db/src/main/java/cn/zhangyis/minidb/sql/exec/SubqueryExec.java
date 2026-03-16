package cn.zhangyis.minidb.sql.exec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派生表（FROM 子查询）执行器
 * 先物化内层查询结果，将列名加上 alias 前缀（alias.column）
 */
public class SubqueryExec implements ExecNode {
    private final ExecNode input;
    private final String alias;
    private Iterator<Row> iterator;

    public SubqueryExec(ExecNode input, String alias) {
        this.input = input;
        this.alias = alias;
    }

    @Override
    public void open() {
        input.open();
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            rows.add(requalify(row));
        }
        input.close();
        iterator = rows.iterator();
    }

    @Override
    public Row next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        iterator = null;
    }

    /**
     * 将行的列名重新限定为 alias.column 格式
     * 如果列名已有 table 前缀（table.col），替换为 alias.col
     * 如果列名无前缀，加上 alias. 前缀
     */
    private Row requalify(Row row) {
        Map<String, Object> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.columns().entrySet()) {
            String key = entry.getKey();
            String colName;
            if (key.contains(".")) {
                colName = key.substring(key.indexOf('.') + 1);
            } else {
                colName = key;
            }
            renamed.put(alias + "." + colName, entry.getValue());
        }
        return new Row(renamed);
    }
}
