package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.types.SqlType;

public class SqlNodeFactory {
    public static final SqlNodeFactory DEFAULT = new SqlNodeFactory();

    public SqlSelect select(SqlNodeList projection, SqlNode from, SqlNode where) {
        return new SqlSelect(projection, from, where);
    }

    public SqlSelect select(SqlNodeList projection, SqlNode from, SqlNode where,
                            SqlNodeList groupBy, SqlNode having) {
        return new SqlSelect(projection, from, where, groupBy, having);
    }

    public SqlSelect select(SqlNodeList projection, SqlNode from, SqlNode where,
                            SqlNodeList groupBy, SqlNode having,
                            SqlNodeList orderBy, SqlNode limit) {
        return new SqlSelect(projection, from, where, groupBy, having, orderBy, limit);
    }

    public SqlJoin join(SqlNode left, SqlNode right, SqlNode condition) {
        return new SqlJoin(left, right, condition);
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
        return new SqlNodeList();
    }

    public SqlNode star() {
        return new SqlStar();
    }

    public SqlBinaryOp binary(SqlKind kind, SqlNode left, SqlNode right) {
        return new SqlBinaryOp(kind, left, right);
    }

    public SqlBinaryOp binaryEq(SqlNode left, SqlNode right) {
        return new SqlBinaryOp(SqlKind.BINARY_EQ, left, right);
    }

    public SqlAggCall aggCall(String funcName, SqlNode arg) {
        return new SqlAggCall(funcName, arg);
    }

    public SqlNode nullLiteral() {
        return new SqlNullLiteral();
    }
}

class SqlNullLiteral implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.NULL_LITERAL; }
}
