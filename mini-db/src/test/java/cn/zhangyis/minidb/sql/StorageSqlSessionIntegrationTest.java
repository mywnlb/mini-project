package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.IndexMeta;
import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageSqlSessionIntegrationTest {

    private static final int SYSTEM_SPACE_ID = 0;

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;
    private TransactionManager txnManager;
    private ExecutionContext executionContext;
    private StorageCatalog catalog;
    private StorageDataSource dataSource;
    private SqlSession session;

    @BeforeEach
    void setUp() throws Exception {
        diskManager = new DiskManager(tempDir);
        diskManager.createTablespace(SYSTEM_SPACE_ID, "system");
        bufferPool = new BufferPool(128, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
        catalogManager.createDatabase("APP");

        txnManager = new TransactionManager(bufferPool, null);
        txnManager.initializeInMemory();
        executionContext = new ExecutionContext(txnManager);
        catalog = new StorageCatalog(catalogManager, "APP", bufferPool);
        dataSource = new StorageDataSource(catalogManager, "APP", executionContext, bufferPool);
        session = new SqlSession(catalog, executionContext, dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (txnManager != null) {
            txnManager.close();
        }
        if (catalogManager != null) {
            catalogManager.close();
        }
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    void explicitTransactionCommitAndRollbackAreVisibleThroughSqlSession() {
        execute("CREATE TABLE USERS (ID INT PRIMARY KEY, NAME VARCHAR)");

        execute("BEGIN");
        execute("INSERT INTO USERS (ID, NAME) VALUES (1, 'ALICE')");
        assertEquals(1, execute("SELECT * FROM USERS").size());
        execute("ROLLBACK");
        assertTrue(execute("SELECT * FROM USERS").isEmpty());

        execute("BEGIN");
        execute("INSERT INTO USERS (ID, NAME) VALUES (2, 'BOB')");
        execute("COMMIT");

        List<Row> rows = execute("SELECT * FROM USERS");
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).get("ID"));
        assertEquals("BOB", rows.get(0).get("NAME"));
    }

    @Test
    void autoCommitPersistsSingleStatementDml() {
        execute("CREATE TABLE USERS (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("INSERT INTO USERS (ID, NAME) VALUES (1, 'ALICE')");

        List<Row> rows = execute("SELECT * FROM USERS");
        assertEquals(1, rows.size());
        assertEquals("ALICE", rows.get(0).get("NAME"));
    }

    @Test
    void storageCatalogDDLAndIndexMetadataAreVisibleToSqlLayer() {
        execute("CREATE TABLE USERS (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("CREATE INDEX IDX_USERS_NAME ON USERS(NAME)");

        List<IndexMeta> indexes = catalog.getIndexes("USERS");
        assertTrue(indexes.stream().anyMatch(index -> index.primary() && index.indexName().equalsIgnoreCase("PRIMARY")));
        assertTrue(indexes.stream().anyMatch(index -> !index.primary() && index.indexName().equalsIgnoreCase("IDX_USERS_NAME")));

        execute("DROP INDEX IDX_USERS_NAME ON USERS");
        indexes = catalog.getIndexes("USERS");
        assertTrue(indexes.stream().anyMatch(IndexMeta::primary));
        assertFalse(indexes.stream().anyMatch(index -> index.indexName().equalsIgnoreCase("IDX_USERS_NAME")));

        assertThrows(UnsupportedOperationException.class,
            () -> execute("ALTER TABLE USERS ADD COLUMN AGE INT"));
    }

    @Test
    void optimizerAndPlannerUseRealPrimaryLookupPath() {
        execute("CREATE TABLE USERS (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("INSERT INTO USERS (ID, NAME) VALUES (1, 'ALICE')");
        execute("INSERT INTO USERS (ID, NAME) VALUES (2, 'BOB')");

        RelNode optimized = buildOptimized("SELECT * FROM USERS WHERE ID = 1");
        assertNotNull(findIndexedScan(optimized));

        String explain = new CostOptimizer(true).decidePhysicalPlan(optimized);
        assertTrue(explain.contains("INDEX_SCAN"), explain);

        ExecNode exec = new PhysicalPlanner(new CostOptimizer(true), dataSource, catalog).plan(findIndexedScan(optimized));
        List<Row> rows = consume(exec);
        assertEquals(1, rows.size());
        assertEquals("ALICE", rows.get(0).get("NAME"));
    }

    @Test
    void cboAndPlannerCanChooseLookupJoinWhenInnerSideHasPrimaryKey() {
        execute("CREATE TABLE USERS (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("CREATE TABLE ORDERS (ID INT PRIMARY KEY, USER_ID INT, AMOUNT INT)");
        execute("INSERT INTO USERS (ID, NAME) VALUES (1, 'ALICE')");
        execute("INSERT INTO USERS (ID, NAME) VALUES (2, 'BOB')");
        execute("INSERT INTO ORDERS (ID, USER_ID, AMOUNT) VALUES (10, 1, 50)");
        execute("INSERT INTO ORDERS (ID, USER_ID, AMOUNT) VALUES (11, 2, 80)");

        RelNode optimized = buildOptimized("SELECT * FROM ORDERS JOIN USERS ON ORDERS.USER_ID = USERS.ID");
        RelJoin join = findJoin(optimized);
        assertNotNull(join);

        CostOptimizer costOptimizer = new CostOptimizer(true);
        assertEquals(PhysicalPlanner.JoinAlgorithm.INDEX_NESTED_LOOP, costOptimizer.chooseJoinAlgorithm(join));

        ExecNode exec = new PhysicalPlanner(costOptimizer, dataSource, catalog).plan(join);
        assertInstanceOf(IndexNestedLoopJoinExec.class, exec);
        assertEquals(2, consume(exec).size());
    }

    @Test
    void multiJoinProjectionExpressionAndUpdateSetExpressionWork() {
        execute("CREATE TABLE USERS (ID INT PRIMARY KEY, SCORE INT)");
        execute("CREATE TABLE ORDERS (ID INT PRIMARY KEY, USER_ID INT, AMOUNT INT)");
        execute("CREATE TABLE PAYMENTS (ID INT PRIMARY KEY, ORDER_ID INT, FEE INT)");
        execute("INSERT INTO USERS (ID, SCORE) VALUES (1, 7)");
        execute("INSERT INTO USERS (ID, SCORE) VALUES (2, 3)");
        execute("INSERT INTO ORDERS (ID, USER_ID, AMOUNT) VALUES (10, 1, 50)");
        execute("INSERT INTO ORDERS (ID, USER_ID, AMOUNT) VALUES (11, 2, 80)");
        execute("INSERT INTO PAYMENTS (ID, ORDER_ID, FEE) VALUES (100, 10, 5)");
        execute("INSERT INTO PAYMENTS (ID, ORDER_ID, FEE) VALUES (101, 11, 8)");

        List<Row> rows = execute("""
            SELECT U.ID + 1 AS NEXT_ID, O.AMOUNT, P.FEE
            FROM USERS U
            JOIN ORDERS O ON U.ID = O.USER_ID
            JOIN PAYMENTS P ON O.ID = P.ORDER_ID
            ORDER BY NEXT_ID
            """);

        assertEquals(2, rows.size());
        assertEquals(2.0, rows.get(0).get("NEXT_ID"));
        assertEquals(3.0, rows.get(1).get("NEXT_ID"));

        execute("UPDATE USERS SET SCORE = SCORE + 5 WHERE ID = 1");
        rows = execute("SELECT SCORE + 1 AS SCORE_PLUS_ONE FROM USERS WHERE ID = 1");
        assertEquals(1, rows.size());
        assertEquals(13.0, rows.get(0).get("SCORE_PLUS_ONE"));
    }

    private List<Row> execute(String sql) {
        return session.execute(sql);
    }

    private RelNode buildOptimized(String sql) {
        SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
        SqlNode validated = new SqlValidator(catalog).validate(ast);
        RelNode logical = new SqlToRelConverter(catalog).convert(validated);
        return new RuleOptimizer(true).optimize(logical);
    }

    private RelIndexedScan findIndexedScan(RelNode node) {
        if (node instanceof RelIndexedScan scan) return scan;
        if (node instanceof RelProject project) return findIndexedScan(project.input());
        if (node instanceof RelFilter filter) return findIndexedScan(filter.input());
        if (node instanceof RelSort sort) return findIndexedScan(sort.input());
        if (node instanceof RelDistinct distinct) return findIndexedScan(distinct.input());
        if (node instanceof RelAggregate aggregate) return findIndexedScan(aggregate.input());
        return null;
    }

    private RelJoin findJoin(RelNode node) {
        if (node instanceof RelJoin join) return join;
        if (node instanceof RelProject project) return findJoin(project.input());
        if (node instanceof RelFilter filter) return findJoin(filter.input());
        if (node instanceof RelSort sort) return findJoin(sort.input());
        if (node instanceof RelDistinct distinct) return findJoin(distinct.input());
        if (node instanceof RelAggregate aggregate) return findJoin(aggregate.input());
        return null;
    }

    private List<Row> consume(ExecNode exec) {
        List<Row> rows = new ArrayList<>();
        exec.open();
        try {
            Row row;
            while ((row = exec.next()) != null) {
                rows.add(row);
            }
        } finally {
            exec.close();
        }
        return rows;
    }
}
