package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 投影执行器：只保留指定列
 */
public class ProjectExec implements ExecNode {
    private final ExecNode input;
    private final SqlNodeList projection;

    public ProjectExec(ExecNode input, SqlNodeList projection) {
        this.input = input;
        this.projection = projection;
    }

    @Override
    public void open() { input.open(); }

    @Override
    public Row next() {
        Row row = input.next();
        if (row == null) return null;

        // SELECT * 直接返回
        if (projection.size() == 1 && projection.get(0).kind() == SqlKind.STAR) {
            return row;
        }

        Map<String, Object> projected = new LinkedHashMap<>();
        for (SqlNode node : projection.nodes()) {
            if (node instanceof SqlIdentifier id) {
                projected.put(id.name(), row.get(id.name()));
            } else if (node instanceof SqlAggCall agg) {
                // 聚合列由 AggregateExec 处理，这里直接透传
                String key = agg.funcName() + "(" + agg.arg() + ")";
                projected.put(key, row.get(key));
            }
        }
        return new Row(projected);
    }

    @Override
    public void close() { input.close(); }
}
