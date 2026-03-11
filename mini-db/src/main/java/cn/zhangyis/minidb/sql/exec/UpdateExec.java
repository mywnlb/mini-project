package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.RelScan;
import cn.zhangyis.minidb.sql.rel.RelFilter;
import cn.zhangyis.minidb.sql.rel.RelUpdate;

import java.util.Map;

/**
 * UPDATE 执行器：扫描满足 WHERE 条件的行，执行 SET 赋值
 */
public class UpdateExec implements ExecNode {
    private final RelUpdate relUpdate;
    private int affectedRows;
    private boolean returned;

    public UpdateExec(RelUpdate relUpdate) {
        this.relUpdate = relUpdate;
    }

    @Override
    public void open() {
        // 提取表名和 WHERE 条件
        String tableName = extractTableName(relUpdate.input());
        SqlNode whereCondition = extractCondition(relUpdate.input());

        affectedRows = MockDataSource.updateRows(tableName,
            row -> whereCondition == null || FilterExec.evaluate(whereCondition, row),
            row -> {
                for (SqlNode node : relUpdate.assignments().nodes()) {
                    SqlAssignment assign = (SqlAssignment) node;
                    Object value = FilterExec.resolveValue(assign.value(), row);
                    row.put(assign.column().name(), value);
                }
            }
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
