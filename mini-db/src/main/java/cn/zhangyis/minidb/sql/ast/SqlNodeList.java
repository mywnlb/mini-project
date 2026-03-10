package cn.zhangyis.minidb.sql.ast;

import java.util.List;


import java.util.ArrayList;
import java.util.List;

public class SqlNodeList implements SqlNode {
    private final List<SqlNode> nodes = new ArrayList<>();

    public SqlNodeList() {}

    public void add(SqlNode node) {
        nodes.add(node);
    }

    public SqlNode get(int index) {
        return nodes.get(index);
    }

    public int size() {
        return nodes.size();
    }

    @Override
    public SqlKind kind() {
        return SqlKind.NODE_LIST;
    }

    public List<SqlNode> nodes() {
        return List.copyOf(nodes);
    }
}