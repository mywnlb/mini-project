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
        String label = projectionLabel(node);
        SqlNode expression = node instanceof SqlAlias alias ? alias.expression() : node;
        projected.put(label, resolveProjectionValue(expression, row));
    }

    private Object resolveProjectionValue(SqlNode node, Row row) {
        if (node instanceof SqlAggCall agg) {
            return row.get(aggKey(agg));
        }
        if (node instanceof SqlWindowFunction wf) {
            return row.get(wf.toString());
        }
        return FilterExec.resolveValue(node, row);
    }

    /**
     * 生成 CAST 表达式的 label
     */
    private String castLabel(SqlCast cast) {
        return "CAST(" + cast.expr() + " AS " + cast.targetType() + ")";
    }

    private String projectionLabel(SqlNode node) {
        if (node instanceof SqlAlias alias) {
            return alias.alias();
        }
        if (node instanceof SqlIdentifier id) {
            return id.name();
        }
        if (node instanceof SqlAggCall agg) {
            return aggKey(agg);
        }
        if (node instanceof SqlCast cast) {
            return castLabel(cast);
        }
        if (node instanceof SqlWindowFunction wf) {
            return wf.toString();
        }
        return String.valueOf(node);
    }

    private String aggKey(SqlAggCall agg) {
        // 聚合列由 AggregateExec 处理，这里只负责读取结果并可选重命名
        if (agg.arg().kind() == SqlKind.STAR) {
            return agg.funcName().toUpperCase() + "(*)";
        }
        String argStr = agg.arg().toString().replaceAll("\\s+", "");
        return agg.funcName().toUpperCase() + "(" + argStr + ")";
    }
}
