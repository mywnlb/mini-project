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
                            SqlNodeList orderBy, SqlNode limit, SqlNode offset) {
        return new SqlSelect(projection, from, where, false, groupBy, having, orderBy, limit, offset);
    }

    public SqlSelect select(SqlNodeList projection, SqlNode from, SqlNode where,
                            boolean distinct, SqlNodeList groupBy, SqlNode having,
                            SqlNodeList orderBy, SqlNode limit, SqlNode offset) {
        return new SqlSelect(projection, from, where, distinct, groupBy, having, orderBy, limit, offset);
    }

    public SqlJoin join(SqlNode left, SqlNode right, SqlNode condition) {
        return new SqlJoin(left, right, condition);
    }

    public SqlJoin join(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition) {
        return new SqlJoin(joinType, left, right, condition);
    }

    public SqlJoin naturalJoin(JoinType joinType, SqlNode left, SqlNode right) {
        return new SqlJoin(joinType, left, right, null, null, true);
    }

    public SqlJoin usingJoin(JoinType joinType, SqlNode left, SqlNode right, java.util.List<String> usingColumns) {
        return new SqlJoin(joinType, left, right, null, usingColumns, false);
    }

    public SqlTableRef tableRef(String tableName, String alias) {
        return new SqlTableRef(tableName, alias);
    }

    public SqlIdentifier identifier(String name) {
        return new SqlIdentifier(name);
    }

    public SqlAlias alias(SqlNode expression, String alias) {
        return new SqlAlias(expression, alias);
    }

    public SqlLiteral number(String value) {
        if (value.contains(".")) {
            return new SqlLiteral(value, SqlType.DECIMAL);
        }
        try {
            Integer.parseInt(value);
            return new SqlLiteral(value, SqlType.INT32);
        } catch (NumberFormatException ignored) {
            return new SqlLiteral(value, SqlType.BIGINT);
        }
    }

    public SqlLiteral string(String value) {
        return new SqlLiteral(value, SqlType.VARCHAR);
    }

    public SqlLiteral hex(String value) {
        return new SqlLiteral(value, SqlType.BLOB);
    }

    public SqlFunctionCall functionCall(String name, SqlNodeList args) {
        return new SqlFunctionCall(name, args);
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

    public SqlCase caseWhen(java.util.List<SqlCase.WhenThen> whenThens, SqlNode elseExpr) {
        return new SqlCase(whenThens, elseExpr);
    }

    public SqlSetOperation setOperation(SqlNode left, SqlNode right, boolean all) {
        return new SqlSetOperation(left, right, all);
    }

    public SqlSetOperation setOperation(SqlNode left, SqlNode right, boolean all, SqlSetOperation.SetOpType opType) {
        return new SqlSetOperation(left, right, all, opType);
    }

    public SqlNode nullLiteral() {
        return new SqlNullLiteral();
    }
}

class SqlNullLiteral implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.NULL_LITERAL; }
}
