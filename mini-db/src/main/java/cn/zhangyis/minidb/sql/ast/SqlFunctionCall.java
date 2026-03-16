package cn.zhangyis.minidb.sql.ast;

public record SqlFunctionCall(String functionName, SqlNodeList arguments) implements SqlNode {
    @Override
    public SqlKind kind() {
        return SqlKind.FUNCTION_CALL;
    }
}