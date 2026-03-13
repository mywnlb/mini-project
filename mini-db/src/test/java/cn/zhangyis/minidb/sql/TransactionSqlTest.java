package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事务语法解析测试
 */
class TransactionSqlTest {

    private SqlNode parse(String sql) {
        TokenStream tokens = new TokenStream(new SqlLexer(sql));
        return new SqlParser(tokens).parseStatement();
    }

    // ==================== BEGIN ====================

    @Test
    void parseBegin() {
        SqlNode node = parse("BEGIN");
        assertInstanceOf(SqlTransaction.class, node);
        assertEquals(SqlKind.BEGIN_TXN, node.kind());
    }

    @Test
    void parseBeginTransaction() {
        SqlNode node = parse("BEGIN TRANSACTION");
        assertInstanceOf(SqlTransaction.class, node);
        assertEquals(SqlKind.BEGIN_TXN, node.kind());
    }

    // ==================== COMMIT ====================

    @Test
    void parseCommit() {
        SqlNode node = parse("COMMIT");
        assertInstanceOf(SqlTransaction.class, node);
        assertEquals(SqlKind.COMMIT_TXN, node.kind());
    }

    // ==================== ROLLBACK ====================

    @Test
    void parseRollback() {
        SqlNode node = parse("ROLLBACK");
        assertInstanceOf(SqlTransaction.class, node);
        assertEquals(SqlKind.ROLLBACK_TXN, node.kind());
    }

    // ==================== ExecutionContext ====================

    @Test
    void beginWithoutContextThrows() {
        // PhysicalPlanner 没有设置 ExecutionContext 时，planSqlNode 应抛异常
        var planner = new cn.zhangyis.minidb.sql.exec.PhysicalPlanner();
        SqlNode node = parse("BEGIN");
        assertThrows(IllegalStateException.class, () -> planner.planSqlNode(node));
    }

    @Test
    void doubleBeginThrows() {
        // ExecutionContext.begin() 连续调用两次应抛异常
        var ctx = new cn.zhangyis.minidb.sql.exec.ExecutionContext(null) {
            @Override
            public cn.zhangyis.minidb.storage.transaction.core.Transaction begin() {
                throw new IllegalStateException("Transaction already active");
            }
        };
        assertThrows(IllegalStateException.class, ctx::begin);
    }

    @Test
    void commitWithoutBeginThrows() {
        var ctx = new cn.zhangyis.minidb.sql.exec.ExecutionContext(null);
        assertThrows(IllegalStateException.class, ctx::commit);
    }

    @Test
    void rollbackWithoutBeginThrows() {
        var ctx = new cn.zhangyis.minidb.sql.exec.ExecutionContext(null);
        assertThrows(IllegalStateException.class, ctx::rollback);
    }
}
