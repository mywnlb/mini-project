package cn.zhangyis.minidb.sql.ast;

public class SqlNodeFactory {
    public static final SqlNodeFactory DEFAULT = new SqlNodeFactory();

    public SqlSelect select(SqlNodeList projection, SqlIdentifier table, SqlNode where) {
        return new SqlSelect(projection, table, where);
    }

    public SqlIdentifier identifier(String name) {
        return new SqlIdentifier(name);
    }

    public SqlLiteral number(String value) {
        return new SqlLiteral(value, SqlType.INT32);
    }

    public SqlLiteral string(String value) {
        return new SqlLiteral(value, SqlType.VARCHAR);
    }

    public SqlNodeList nodeList() {
        return new SqlNodeList(List.of());
    }

    public SqlNode star() {
        return new SqlStar();
    }

    public SqlBinaryOp binaryEq(SqlNode left, SqlNode right) {
        return new SqlBinaryOp(SqlKind.BINARY_EQ, left, right);
    }

    public SqlNode nullLiteral() {
        return new SqlNullLiteral();
    }
}

class SqlNullLiteral implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.NULL_LITERAL; }
}