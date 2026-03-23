package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreparedStatementTest {
    private final MockCatalog catalog = new MockCatalog();
    private PlanCache planCache;

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
        planCache = new PlanCache(64);
    }

    private PreparedStatement prepare(String sql) {
        return new PreparedStatement(sql, catalog, planCache, new PhysicalPlanner());
    }

    // ==================== 基本参数绑定 ====================

    @Nested
    class BasicBinding {

        @Test
        void selectWithIntParam() {
            PreparedStatement ps = prepare("SELECT * FROM users WHERE id = ?");
            ps.setInt(1, 1);
            List<Row> rows = ps.execute();
            assertEquals(1, rows.size());
            assertEquals(1, rows.get(0).get("users.id"));
        }

        @Test
        void selectWithStringParam() {
            PreparedStatement ps = prepare("SELECT * FROM users WHERE name = ?");
            ps.setString(1, "alice");
            List<Row> rows = ps.execute();
            assertEquals(2, rows.size()); // two alice rows
        }

        @Test
        void multipleParams() {
            PreparedStatement ps = prepare("SELECT * FROM orders WHERE user_id = ? AND amount > ?");
            ps.setInt(1, 1);
            ps.setInt(2, 100);
            List<Row> rows = ps.execute();
            assertEquals(1, rows.size()); // order 101: user_id=1, amount=250
            assertEquals(250, rows.get(0).get("orders.amount"));
        }
    }

    // ==================== 复用 ====================

    @Nested
    class Reuse {

        @Test
        void sameStatementDifferentParams() {
            PreparedStatement ps = prepare("SELECT * FROM users WHERE id = ?");

            ps.setInt(1, 1);
            List<Row> rows1 = ps.execute();
            assertEquals(1, rows1.size());

            ps.reset();
            ps.setInt(1, 2);
            List<Row> rows2 = ps.execute();
            assertEquals(1, rows2.size());
            assertEquals("bob", rows2.get(0).get("users.name"));
        }

        @Test
        void paramCountPreservedAfterReset() {
            PreparedStatement ps = prepare("SELECT * FROM users WHERE id = ?");
            assertEquals(1, ps.paramCount());
            ps.setInt(1, 1);
            ps.execute();
            ps.reset();
            assertEquals(1, ps.paramCount());
        }
    }

    // ==================== 错误检测 ====================

    @Nested
    class ErrorDetection {

        @Test
        void paramIndexOutOfRange() {
            PreparedStatement ps = prepare("SELECT * FROM users WHERE id = ?");
            assertThrows(IllegalArgumentException.class, () -> ps.setInt(0, 1));
            assertThrows(IllegalArgumentException.class, () -> ps.setInt(2, 1));
        }

        @Test
        void missingParamThrows() {
            PreparedStatement ps = prepare("SELECT * FROM orders WHERE user_id = ? AND amount > ?");
            ps.setInt(1, 1);
            // param 2 not set
            assertThrows(IllegalStateException.class, ps::execute);
        }

        @Test
        void noParamStatement() {
            PreparedStatement ps = prepare("SELECT * FROM users");
            assertEquals(0, ps.paramCount());
            List<Row> rows = ps.execute();
            assertEquals(5, rows.size());
        }
    }

    // ==================== PlanCache ====================

    @Nested
    class CacheTests {

        @Test
        void cacheHit() {
            String sql = "SELECT * FROM users WHERE id = ?";
            PreparedStatement ps1 = prepare(sql);
            assertEquals(1, planCache.size());

            // 第二次 prepare 应命中缓存
            PreparedStatement ps2 = prepare(sql);
            assertEquals(1, planCache.size()); // 没有新增

            ps1.setInt(1, 1);
            ps2.setInt(1, 2);
            assertEquals(1, ps1.execute().size());
            assertEquals(1, ps2.execute().size());
        }

        @Test
        void cacheEviction() {
            PlanCache smallCache = new PlanCache(2);
            new PreparedStatement("SELECT * FROM users WHERE id = ?", catalog, smallCache, new PhysicalPlanner());
            new PreparedStatement("SELECT * FROM orders WHERE order_id = ?", catalog, smallCache, new PhysicalPlanner());
            assertEquals(2, smallCache.size());

            // 第三条 SQL 应淘汰最久未使用的
            new PreparedStatement("SELECT * FROM users WHERE name = ?", catalog, smallCache, new PhysicalPlanner());
            assertEquals(2, smallCache.size());
        }
    }

    // ==================== NULL 参数 ====================

    @Nested
    class NullParam {

        @Test
        void setNullParam() {
            PreparedStatement ps = prepare("SELECT * FROM users WHERE id = ?");
            ps.setNull(1);
            // NULL = anything is false in SQL, should return empty
            List<Row> rows = ps.execute();
            assertEquals(0, rows.size());
        }

        @Test
        void setObjectNull() {
            PreparedStatement ps = prepare("SELECT * FROM users WHERE id = ?");
            ps.setObject(1, null);
            List<Row> rows = ps.execute();
            assertEquals(0, rows.size());
        }
    }

    // ==================== INSERT 参数绑定 ====================

    @Nested
    class InsertBinding {

        @Test
        void insertWithParams() {
            PreparedStatement ps = prepare("INSERT INTO users (id, name) VALUES (?, ?)");
            assertEquals(2, ps.paramCount());
            ps.setInt(1, 99);
            ps.setString(2, "newuser");
            ps.execute();

            // 验证插入成功
            List<Row> rows = MockDataSource.getTableData("USERS");
            assertEquals(6, rows.size());
        }
    }

    // ==================== Lexer/Parser 单元测试 ====================

    @Nested
    class LexerParser {

        @Test
        void questionMarkTokenized() {
            var lexer = new cn.zhangyis.minidb.sql.lexer.SqlLexer("SELECT ? FROM users WHERE id = ?");
            var ts = new cn.zhangyis.minidb.sql.lexer.TokenStream(lexer);
            // SELECT
            assertEquals(cn.zhangyis.minidb.sql.lexer.TokenType.SELECT, ts.current().type());
            ts.next();
            // ?
            assertEquals(cn.zhangyis.minidb.sql.lexer.TokenType.PARAMETER, ts.current().type());
        }

        @Test
        void parserCountsParams() {
            var parser = new cn.zhangyis.minidb.sql.parser.SqlParser(
                    new cn.zhangyis.minidb.sql.lexer.TokenStream(
                            new cn.zhangyis.minidb.sql.lexer.SqlLexer("SELECT * FROM users WHERE id = ? AND name = ?")));
            parser.parseStatement();
            assertEquals(2, parser.paramCount());
        }
    }
}
