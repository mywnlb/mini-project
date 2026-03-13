package cn.zhangyis.minidb.sql.exec;

public class ExecutionContext {
    public void commit() {}
    public void rollback() {}
    public Object begin() { return null; }
}
