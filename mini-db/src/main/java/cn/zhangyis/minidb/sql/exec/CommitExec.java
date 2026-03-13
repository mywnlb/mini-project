package cn.zhangyis.minidb.sql.exec;

import java.util.Map;

/**
 * COMMIT 执行器：提交当前事务
 */
public class CommitExec implements ExecNode {
    private final ExecutionContext ctx;
    private boolean returned;

    public CommitExec(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void open() {
        ctx.commit();
        returned = false;
    }

    @Override
    public Row next() {
        if (!returned) {
            returned = true;
            return new Row(Map.of("status", "COMMIT OK"));
        }
        return null;
    }

    @Override
    public void close() {}
}
