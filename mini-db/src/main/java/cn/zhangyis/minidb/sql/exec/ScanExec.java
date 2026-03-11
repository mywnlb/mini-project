package cn.zhangyis.minidb.sql.exec;

import java.util.Iterator;
import java.util.List;

/**
 * 全表扫描执行器
 */
public class ScanExec implements ExecNode {
    private final String tableName;
    private Iterator<Row> iterator;

    public ScanExec(String tableName) {
        this.tableName = tableName;
    }

    @Override
    public void open() {
        List<Row> data = MockDataSource.getTableData(tableName);
        iterator = data.iterator();
    }

    @Override
    public Row next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        iterator = null;
    }
}
