package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlCreateIndex;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.IndexMeta;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.CreateIndexExec;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelCreateIndex;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateIndexSqlTest {

    @Test
    void parseCreateUniqueIndex_marksUniqueAndResolvesQualifiedTableName() {
        SqlNode node = parse("CREATE UNIQUE INDEX UQ_USERS_NAME ON TEST_DB.USERS(NAME)");

        SqlCreateIndex createIndex = assertInstanceOf(SqlCreateIndex.class, node);
        assertTrue(createIndex.unique());
        assertEquals("UQ_USERS_NAME", createIndex.indexName());
        assertEquals("USERS", createIndex.table().name());
        assertEquals(List.of("NAME"), createIndex.columns());
    }

    @Test
    void createIndexExecPropagatesUniqueFlagToCatalog() {
        MockCatalog catalog = new MockCatalog();
        SqlCreateIndex createIndex = assertInstanceOf(SqlCreateIndex.class,
                parse("CREATE UNIQUE INDEX UQ_USERS_NAME ON USERS(NAME)"));
        SqlCreateIndex validated = (SqlCreateIndex) new SqlValidator(catalog).validate(createIndex);
        RelCreateIndex rel = (RelCreateIndex) new SqlToRelConverter(catalog).convert(validated);

        CreateIndexExec exec = new CreateIndexExec(rel);
        exec.open();
        exec.close();

        List<IndexMeta> indexes = catalog.getIndexes("USERS");
        assertTrue(indexes.stream().anyMatch(index ->
                index.indexName().equalsIgnoreCase("UQ_USERS_NAME")
                        && index.unique()
                        && !index.primary()));
    }

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }
}
