package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXPLAIN 执行器：输出查询计划树，每行一个算子，含缩进、估算行数和代价。
 *
 * 输出列：ID, OPERATOR, EST_ROWS, DETAILS
 */
public class ExplainExec implements ExecNode {

    private final RelNode plan;
    private final CostModel costModel;
    private List<Row> rows;
    private int cursor;
    private int idSeq;

    public ExplainExec(RelNode plan, CostModel costModel) {
        this.plan = plan;
        this.costModel = costModel;
    }

    @Override
    public void open() {
        rows = new ArrayList<>();
        cursor = 0;
        idSeq = 0;
        buildExplainRows(plan, 0);
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

    private void buildExplainRows(RelNode node, int depth) {
        int id = ++idSeq;
        String indent = "  ".repeat(depth);
        String operator = nodeName(node);
        double estRows = costModel.estimateRows(node);
        String details = nodeDetails(node);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", id);
        row.put("OPERATOR", indent + operator);
        row.put("EST_ROWS", formatRows(estRows));
        row.put("DETAILS", details);
        rows.add(new Row(row));

        // 递归子节点
        for (RelNode child : children(node)) {
            buildExplainRows(child, depth + 1);
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
        if (node instanceof RelAggregate) return "Aggregate";
        if (node instanceof RelSort) return "Sort";
        if (node instanceof RelDistinct) return "Distinct";
        if (node instanceof RelUnion u) return u.all() ? "UnionAll" : "Union";
        if (node instanceof RelDerivedScan) return "DerivedScan";
        if (node instanceof RelInsert) return "Insert";
        if (node instanceof RelInsertSelect) return "InsertSelect";
        if (node instanceof RelUpdate) return "Update";
        if (node instanceof RelDelete) return "Delete";
        if (node instanceof RelCreateTable) return "CreateTable";
        if (node instanceof RelDropTable) return "DropTable";
        if (node instanceof RelAlterTable) return "AlterTable";
        if (node instanceof RelCreateIndex) return "CreateIndex";
        if (node instanceof RelDropIndex) return "DropIndex";
        if (node instanceof RelAnalyzeTable) return "AnalyzeTable";
        return node.getClass().getSimpleName();
    }

    private String nodeDetails(RelNode node) {
        if (node instanceof RelScan s) {
            return "table=" + s.tableName()
                + (s.outputName().equalsIgnoreCase(s.tableName()) ? "" : ", alias=" + s.outputName());
        }
        if (node instanceof RelIndexedScan s) {
            return "table=" + s.tableName() + ", index_cond=" + s.indexCondition();
        }
        if (node instanceof RelFilter f) {
            return "condition=" + (f.condition() != null ? f.condition() : "true");
        }
        if (node instanceof RelProject p) {
            return "fields=" + p.projection().nodes().size();
        }
        if (node instanceof RelJoin j) {
            return "condition=" + (j.condition() != null ? j.condition() : "true");
        }
        if (node instanceof RelSemiJoin s) {
            return "condition=" + (s.condition() != null ? s.condition() : "true");
        }
        if (node instanceof RelAntiJoin a) {
            return "condition=" + (a.condition() != null ? a.condition() : "true");
        }
        if (node instanceof RelAggregate a) {
            int groupCount = a.groupKeys() != null ? a.groupKeys().nodes().size() : 0;
            return "groups=" + groupCount + ", aggs=" + a.aggCalls().size();
        }
        if (node instanceof RelSort s) {
            StringBuilder sb = new StringBuilder();
            if (s.orderBy() != null) sb.append("orderBy=").append(s.orderBy().nodes().size()).append(" keys");
            if (s.limit() != null) sb.append(sb.length() > 0 ? ", " : "").append("limit=").append(s.limit());
            if (s.offset() != null) sb.append(sb.length() > 0 ? ", " : "").append("offset=").append(s.offset());
            return sb.toString();
        }
        if (node instanceof RelDerivedScan d) {
            return "alias=" + d.alias();
        }
        if (node instanceof RelCreateTable ct) {
            return "table=" + ct.createTable().table().name();
        }
        if (node instanceof RelDropTable dt) {
            return "table=" + dt.dropTable().table().name();
        }
        if (node instanceof RelAlterTable at) {
            return "table=" + at.alterTable().table().name() + ", addColumn=" + at.alterTable().columnName();
        }
        if (node instanceof RelAnalyzeTable at) {
            return "table=" + at.tableName();
        }
        return "";
    }

    private List<RelNode> children(RelNode node) {
        List<RelNode> list = new ArrayList<>();
        if (node instanceof RelFilter f) list.add(f.input());
        else if (node instanceof RelProject p) list.add(p.input());
        else if (node instanceof RelSort s) list.add(s.input());
        else if (node instanceof RelDistinct d) list.add(d.input());
        else if (node instanceof RelAggregate a) list.add(a.input());
        else if (node instanceof RelJoin j) { list.add(j.left()); list.add(j.right()); }
        else if (node instanceof RelSemiJoin s) { list.add(s.left()); list.add(s.right()); }
        else if (node instanceof RelAntiJoin a) { list.add(a.left()); list.add(a.right()); }
        else if (node instanceof RelUnion u) { list.add(u.left()); list.add(u.right()); }
        else if (node instanceof RelDerivedScan d) list.add(d.input());
        else if (node instanceof RelUpdate u) list.add(u.input());
        else if (node instanceof RelDelete d) list.add(d.input());
        else if (node instanceof RelInsertSelect is) list.add(is.input());
        return list;
    }

    private String formatRows(double rows) {
        if (rows == (long) rows) return String.valueOf((long) rows);
        return String.format("%.1f", rows);
    }
}
