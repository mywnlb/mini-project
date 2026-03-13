package cn.zhangyis.minidb.sql.ast;

/**
 * 事务控制语句 AST 节点：BEGIN / COMMIT / ROLLBACK
 */
public record SqlTransaction(SqlKind txnKind) implements SqlNode {

    @Override
    public SqlKind kind() { return txnKind; }

    @Override
    public String toString() {
        return switch (txnKind) {
            case BEGIN_TXN -> "BEGIN";
            case COMMIT_TXN -> "COMMIT";
            case ROLLBACK_TXN -> "ROLLBACK";
            default -> "UNKNOWN_TXN";
        };
    }
}
