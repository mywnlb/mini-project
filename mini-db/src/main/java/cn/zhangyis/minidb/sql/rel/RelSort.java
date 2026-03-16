package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import java.util.List;
import java.util.stream.Collectors;

public class RelSort extends RelNode {
    private final RelNode input;
    private final SqlNodeList orderBy;
    private final SqlNode limit;
    private final SqlNode offset;

    public RelSort(RelNode input, SqlNodeList orderBy, SqlNode limit) {
        this(input, orderBy, limit, null);
    }

    public RelSort(RelNode input, SqlNodeList orderBy, SqlNode limit, SqlNode offset) {
        this.input = input;
        this.orderBy = orderBy;
        this.limit = limit;
        this.offset = offset;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelSort(inputs.get(0), orderBy, limit, offset);
    }

    @Override
    public String explain() {
        String order = orderBy != null
            ? orderBy.nodes().stream().map(Object::toString).collect(Collectors.joining(", "))
            : "[]";
        String lim = limit != null ? limit.toString() : "none";
        String off = offset != null ? offset.toString() : "none";
        return "RelSort(orderBy=[" + order + "], limit=" + lim + ", offset=" + off + ")\n" +
               "  " + input.explain();
    }

    public RelNode input() { return input; }
    public SqlNodeList orderBy() { return orderBy; }
    public SqlNode limit() { return limit; }
    public SqlNode offset() { return offset; }
}
