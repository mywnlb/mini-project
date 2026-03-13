package cn.zhangyis.minidb.sql.exec;

public class BeginExec implements ExecNode {
    public BeginExec(ExecutionContext ctx) {}
    public void open() {}
    public Row next() { return null; }
    public void close() {}
}
