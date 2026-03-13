package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.rel.RelDelete;
import cn.zhangyis.minidb.sql.rel.RelFilter;
import cn.zhangyis.minidb.sql.rel.RelScan;

import java.util.Map;

/**
 * DELETE 执行器：删除满足 WHERE 条件的行
 */
public class DeleteExec implements ExecNode {
    private final RelDelete relDelete;
    private final DataSourceSpi dataSource;
    private int affectedRows;
    private boolean returned;

    public DeleteExec(RelDelete relDelete) {
        this(relDelete, MockDataSourceAdapter.INSTANCE);
    }

    public DeleteExec(RelDelete relDelete, DataSourceSpi dataSource) {
        this.relDelete = relDelete;
        this.dataSource = dataSource;
    }

    @Override
    public void open() {
        String tableName = extractTableName(relDelete.input());
        SqlNode whereCondition = extractCondition(relDelete.input());

        affectedRows = dataSource.deleteRows(tableName,
            row -> whereCondition == null || FilterExec.evaluate(whereCondition, row)
        );
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

    private String extractTableName(cn.zhangyis.minidb.sql.rel.RelNode input) {
        if (input instanceof RelScan scan) return scan.tableName();
        if (input instanceof RelFilter filter) return extractTableName(filter.input());
        throw new IllegalStateException("Cannot extract table name from: " + input.getClass().getSimpleName());
    }

    private SqlNode extractCondition(cn.zhangyis.minidb.sql.rel.RelNode input) {
        if (input instanceof RelFilter filter) return filter.condition();
        return null;
    }
}
