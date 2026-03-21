package cn.zhangyis.minidb.sql.ast;

public record SqlAnalyzeTable(String tableName) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.ANALYZE_TABLE; }
}
