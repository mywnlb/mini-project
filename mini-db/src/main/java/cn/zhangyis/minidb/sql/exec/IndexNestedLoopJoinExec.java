package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlNode;

/**
 * Index Nested Loop Join：外表扫描 + 内表索引查找
 * 模拟实现：用 HashMap 模拟索引
 * 时间复杂度: O(M * logN) 或 O(M) with hash index
 */
public class IndexNestedLoopJoinExec implements ExecNode {
    private final ExecNode outer;
    private final ExecNode inner;
    private final String outerKey;
    private final String innerKey;

    // 内部复用 HashJoinExec 的 build 逻辑模拟索引
    private HashJoinExec delegate;

    public IndexNestedLoopJoinExec(ExecNode outer, ExecNode inner, String outerKey, String innerKey) {
        this.outer = outer;
        this.inner = inner;
        this.outerKey = outerKey;
        this.innerKey = innerKey;
        // 外表做 probe side，内表做 build side（模拟索引）
        this.delegate = new HashJoinExec(outer, inner, outerKey, innerKey);
    }

    @Override
    public void open() {
        delegate.open();
    }

    @Override
    public Row next() {
        return delegate.next();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
