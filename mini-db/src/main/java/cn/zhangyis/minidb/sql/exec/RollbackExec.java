package cn.zhangyis.minidb.sql.exec;

import java.util.Map;

/**
 * ROLLBACK 执行器：回滚当前事务
 */
public class RollbackExec implements ExecNode {
    private final ExecutionContext ctx;
    private boolean returned;

    public RollbackExec(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void open() {
        ctx.rollback();
        returned = false;
    }

    @Override
    public Row next() {
        if (!returned) {
            returned = true;
            return new Row(Map.of("status", "ROLLBACK OK"));
        }
        return null;
    }

    @Override
    public void close() {}
}
