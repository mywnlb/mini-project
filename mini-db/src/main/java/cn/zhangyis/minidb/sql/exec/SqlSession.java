package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL 会话：统一的 SQL 执行入口。
 *
 * <p>将 parser → validator → converter → optimizer → planner 组装在一起，
 * 确保事务控制语句和普通 DML 共享同一个 {@link ExecutionContext}。</p>
 *
 * <h3>事务语义</h3>
 * <ul>
 *   <li>BEGIN → 创建显式事务，后续 DML 复用 currentTxn</li>
 *   <li>COMMIT/ROLLBACK → 结束显式事务</li>
 *   <li>无显式事务时，每条 DML 自动 begin/commit（auto-commit）</li>
 * </ul>
 */
public class SqlSession {

    private final CatalogSpi catalog;
    private final ExecutionContext executionContext;
    private final PhysicalPlanner planner;
    private final boolean enableIndexedLookup;

    public SqlSession(CatalogSpi catalog, ExecutionContext executionContext,
                      DataSourceSpi dataSource) {
        this.catalog = Objects.requireNonNull(catalog);
        this.executionContext = Objects.requireNonNull(executionContext);
        this.enableIndexedLookup = dataSource instanceof StorageDataSource;
        this.planner = new PhysicalPlanner(new CostOptimizer(enableIndexedLookup), dataSource, catalog);
        this.planner.setExecutionContext(executionContext);
    }

    /**
     * 执行一条 SQL，返回结果行列表。
     */
    public List<Row> execute(String sql) {
        SqlNode ast = parse(sql);

        // 事务控制语句直接走 planSqlNode，不经过 validator/converter
        if (ast instanceof SqlTransaction) {
            return execNode(planner.planSqlNode(ast));
        }

        // EXPLAIN：走完整优化链路但不实际执行，仅输出计划
        if (ast instanceof SqlExplain explain) {
            SqlNode inner = explain.query();
            if (inner instanceof SqlTransaction || inner instanceof SqlExplain) {
                throw new IllegalArgumentException("EXPLAIN does not support: " + inner.kind());
            }
            return executeExplain(inner);
        }

        // 普通语句走完整链路
        SqlValidator validator = new SqlValidator(catalog);
        SqlNode validated = validator.validate(ast);

        SqlToRelConverter converter = new SqlToRelConverter(catalog);
        RelNode logicalPlan = converter.convert(validated);

        // DDL 不需要优化
        RelNode plan;
        if (isDdl(logicalPlan)) {
            plan = logicalPlan;
        } else {
            RuleOptimizer ruleOpt = new RuleOptimizer(enableIndexedLookup);
            plan = ruleOpt.optimize(logicalPlan);
        }

        return execNode(planner.plan(plan));
    }

    public ExecutionContext executionContext() {
        return executionContext;
    }

    private SqlNode parse(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        TokenStream tokens = new TokenStream(lexer);
        SqlParser parser = new SqlParser(tokens);
        return parser.parseStatement();
    }

    private List<Row> execNode(ExecNode exec) {
        exec.open();
        List<Row> results = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            results.add(row);
        }
        exec.close();
        return results;
    }

    private List<Row> executeExplain(SqlNode query) {
        SqlValidator validator = new SqlValidator(catalog);
        SqlNode validated = validator.validate(query);
        SqlToRelConverter converter = new SqlToRelConverter(catalog);
        RelNode logicalPlan = converter.convert(validated);

        RelNode optimized;
        if (isDdl(logicalPlan)) {
            optimized = logicalPlan;
        } else {
            RuleOptimizer ruleOpt = new RuleOptimizer(enableIndexedLookup);
            optimized = ruleOpt.optimize(logicalPlan);
        }

        CostModel costModel = new CostModel(enableIndexedLookup);
        return execNode(new ExplainExec(optimized, costModel));
    }

    private boolean isDdl(RelNode node) {
        return node instanceof RelCreateTable
            || node instanceof RelDropTable
            || node instanceof RelAlterTable
            || node instanceof RelCreateIndex
            || node instanceof RelDropIndex;
    }
}
