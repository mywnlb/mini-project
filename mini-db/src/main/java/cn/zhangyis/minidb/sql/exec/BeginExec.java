package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;

import java.util.Map;

/**
 * BEGIN 执行器：开启新事务
 */
public class BeginExec implements ExecNode {
    private final ExecutionContext ctx;
    private boolean returned;

    public BeginExec(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void open() {
        Transaction txn = ctx.begin();
        returned = false;
    }

    @Override
    public Row next() {
        if (!returned) {
            returned = true;
            return new Row(Map.of("status", "BEGIN OK, trxId=" + ctx.currentTxn().getId()));
        }
        return null;
    }

    @Override
    public void close() {}
}
