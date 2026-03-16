package cn.zhangyis.minidb.sql.ast;

public record SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where,
                         boolean distinct, SqlNodeList groupBy, SqlNode having,
                         SqlNodeList orderBy, SqlNode limit, SqlNode offset) implements SqlNode {

    public SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where) {
        this(projection, from, where, false, null, null, null, null, null);
    }

    public SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where,
                     SqlNodeList groupBy, SqlNode having) {
        this(projection, from, where, false, groupBy, having, null, null, null);
    }

    public SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where,
                     SqlNodeList groupBy, SqlNode having,
                     SqlNodeList orderBy, SqlNode limit) {
        this(projection, from, where, false, groupBy, having, orderBy, limit, null);
    }

    public SqlSelect(SqlNodeList projection, SqlNode from, SqlNode where,
                     SqlNodeList groupBy, SqlNode having,
                     SqlNodeList orderBy, SqlNode limit, SqlNode offset) {
        this(projection, from, where, false, groupBy, having, orderBy, limit, offset);
    }

    @Override
    public SqlKind kind() { return SqlKind.SELECT; }
}
