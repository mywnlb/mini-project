package cn.zhangyis.minidb.sql.ast;

public class SqlStar implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.STAR; }
}