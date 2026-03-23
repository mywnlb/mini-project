package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXPLAIN ANALYZE 执行器：
 * 1. 用 InstrumentedExecNode 递归包装执行树
 * 2. 实际执行查询（drain all rows）
 * 3. 收集每个算子的实际行数和时间
 * 4. 输出: ID, OPERATOR, EST_ROWS, ACT_ROWS, TIME_MS
 */
public class ExplainAnalyzeExec implements ExecNode {

    private final ExecNode rootExec;
    private final RelNode plan;
    private final CostModel costModel;
    private List<Row> rows;
    private int cursor;
    private int idSeq;

    public ExplainAnalyzeExec(ExecNode rootExec, RelNode plan, CostModel costModel) {
        this.rootExec = rootExec;
        this.plan = plan;
        this.costModel = costModel;
    }

    @Override
    public void open() {
        // 1. 递归包装执行树
        ExecNode instrumented = instrument(rootExec);

        // 2. 执行查询到完成
        instrumented.open();
        while (instrumented.next() != null) {
            // drain
        }
        instrumented.close();

        // 3. 收集统计信息并构建输出
        rows = new ArrayList<>();
        cursor = 0;
        idSeq = 0;
        List<InstrumentedExecNode> instrumentedNodes = new ArrayList<>();
        collectInstrumented(instrumented, instrumentedNodes);
        buildExplainRows(plan, instrumentedNodes, 0);
    }

    @Override
    public Row next() {
        if (cursor < rows.size()) {
            return rows.get(cursor++);
        }
        return null;
    }

    @Override
    public void close() {
        // no-op
    }

    private ExecNode instrument(ExecNode node) {
        // 已经是 Instrumented 则跳过
        if (node instanceof InstrumentedExecNode) return node;
        return new InstrumentedExecNode(node);
    }

    private void collectInstrumented(ExecNode node, List<InstrumentedExecNode> list) {
        if (node instanceof InstrumentedExecNode inst) {
            list.add(inst);
        }
    }

    private void buildExplainRows(RelNode node, List<InstrumentedExecNode> instrumented, int depth) {
        int id = ++idSeq;
        String indent = "  ".repeat(depth);
        String operator = nodeName(node);
        double estRows = costModel.estimateRows(node);

        // 从 instrumented 列表中获取对应统计（按遍历顺序对应）
        long actRows = 0;
        double timeMs = 0;
        if (id - 1 < instrumented.size()) {
            InstrumentedExecNode inst = instrumented.get(id - 1);
            actRows = inst.rowCount();
            timeMs = inst.totalTimeMs();
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", id);
        row.put("OPERATOR", indent + operator);
        row.put("EST_ROWS", formatRows(estRows));
        row.put("ACT_ROWS", actRows);
        row.put("TIME_MS", String.format("%.2f", timeMs));
        rows.add(new Row(row));

        for (RelNode child : children(node)) {
            buildExplainRows(child, instrumented, depth + 1);
        }
    }

    private String nodeName(RelNode node) {
        if (node instanceof RelScan) return "TableScan";
        if (node instanceof RelIndexedScan) return "IndexScan";
        if (node instanceof RelFilter) return "Filter";
        if (node instanceof RelProject) return "Project";
        if (node instanceof RelJoin j) return j.joinType().name() + " Join";
        if (node instanceof RelSemiJoin) return "SemiJoin";
        if (node instanceof RelAntiJoin) return "AntiJoin";
        if (node instanceof RelPartialAggregate) return "PartialAggregate";
        if (node instanceof RelFinalAggregate) return "FinalAggregate";
        if (node instanceof RelAggregate) return "Aggregate";
        if (node instanceof RelSort) return "Sort";
        if (node instanceof RelDistinct) return "Distinct";
        if (node instanceof RelUnion u) return u.all() ? "UnionAll" : "Union";
        return node.getClass().getSimpleName();
    }

    private List<RelNode> children(RelNode node) {
        List<RelNode> list = new ArrayList<>();
        if (node instanceof RelFilter f) list.add(f.input());
        else if (node instanceof RelProject p) list.add(p.input());
        else if (node instanceof RelSort s) list.add(s.input());
        else if (node instanceof RelDistinct d) list.add(d.input());
        else if (node instanceof RelPartialAggregate pa) list.add(pa.input());
        else if (node instanceof RelFinalAggregate fa) list.add(fa.input());
        else if (node instanceof RelAggregate a) list.add(a.input());
        else if (node instanceof RelJoin j) { list.add(j.left()); list.add(j.right()); }
        else if (node instanceof RelSemiJoin s) { list.add(s.left()); list.add(s.right()); }
        else if (node instanceof RelAntiJoin a) { list.add(a.left()); list.add(a.right()); }
        else if (node instanceof RelUnion u) { list.add(u.left()); list.add(u.right()); }
        else if (node instanceof RelDerivedScan d) list.add(d.input());
        return list;
    }

    private String formatRows(double rows) {
        if (rows == (long) rows) return String.valueOf((long) rows);
        return String.format("%.1f", rows);
    }
}
