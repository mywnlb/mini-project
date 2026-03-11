package cn.zhangyis.minidb.sql.ast;

public record SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where,
                         SqlNodeList groupBy, SqlNode having,
                         SqlNodeList orderBy, SqlNode limit) implements SqlNode {

    public SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where) {
        this(projection, from, where, null, null, null, null);
    }

    public SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where,
                     SqlNodeList groupBy, SqlNode having) {
        this(projection, from, where, groupBy, having, null, null);
    }

    @Override
    public SqlKind kind() { return SqlKind.SELECT; }
}
