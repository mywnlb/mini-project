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
            projectNode(projected, node, row);
        }
        return new Row(projected);
    }

    @Override
    public void close() { input.close(); }

    private void projectNode(Map<String, Object> projected, SqlNode node, Row row) {
        if (node instanceof SqlAlias alias) {
            projected.put(alias.alias(), resolveProjectionValue(alias.expression(), row));
            return;
        }
        if (node instanceof SqlIdentifier id) {
            projected.put(id.name(), row.get(id.name()));
            return;
        }
        if (node instanceof SqlAggCall agg) {
            String key = aggKey(agg);
            projected.put(key, row.get(key));
            return;
        }
        if (node instanceof SqlLiteral lit) {
            projected.put(lit.value(), FilterExec.resolveValue(lit, row));
        }
    }

    private Object resolveProjectionValue(SqlNode node, Row row) {
        if (node instanceof SqlIdentifier id) {
            return row.get(id.name());
        }
        if (node instanceof SqlAggCall agg) {
            return row.get(aggKey(agg));
        }
        if (node instanceof SqlLiteral lit) {
            return FilterExec.resolveValue(lit, row);
        }
        return null;
    }

    private String aggKey(SqlAggCall agg) {
        // 聚合列由 AggregateExec 处理，这里只负责读取结果并可选重命名
        return agg.funcName() + "(" + agg.arg() + ")";
    }
}
