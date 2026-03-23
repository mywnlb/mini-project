package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预编译语句：解析一次，绑定参数多次执行。
 *
 * <pre>
 * PreparedStatement ps = new PreparedStatement(sql, catalog, planCache);
 * ps.setInt(1, 42);
 * ps.setString(2, "alice");
 * List&lt;Row&gt; rows = ps.execute();
 * ps.reset();
 * ps.setInt(1, 99);
 * ps.setString(2, "bob");
 * List&lt;Row&gt; rows2 = ps.execute();
 * </pre>
 */
public class PreparedStatement {

    private final String sql;
    private final SqlNode cachedAst;
    private final int paramCount;
    private final CatalogSpi catalog;
    private final PlanCache planCache;
    private final PhysicalPlanner planner;

    private final Map<Integer, Object> params = new HashMap<>();

    /**
     * @param sql       含 ? 占位符的 SQL
     * @param catalog   目录
     * @param planCache 可选计划缓存（可为 null）
     * @param planner   物理计划器
     */
    public PreparedStatement(String sql, CatalogSpi catalog, PlanCache planCache,
                             PhysicalPlanner planner) {
        this.sql = sql;
        this.catalog = catalog;
        this.planCache = planCache;
        this.planner = planner;

        // 尝试从缓存获取
        PlanCache.CachedPlan cached = planCache != null ? planCache.get(sql) : null;
        if (cached != null) {
            this.cachedAst = cached.ast();
            this.paramCount = cached.paramCount();
        } else {
            // 解析
            SqlParser parser = new SqlParser(new TokenStream(new SqlLexer(sql)));
            this.cachedAst = parser.parseStatement();
            this.paramCount = parser.paramCount();
            // 存入缓存
            if (planCache != null) {
                planCache.put(sql, new PlanCache.CachedPlan(cachedAst, paramCount));
            }
        }
    }

    /** 设置参数（索引从1开始，JDBC标准） */
    public void setInt(int index, int value) {
        checkIndex(index);
        params.put(index - 1, value);
    }

    public void setLong(int index, long value) {
        checkIndex(index);
        params.put(index - 1, value);
    }

    public void setDouble(int index, double value) {
        checkIndex(index);
        params.put(index - 1, value);
    }

    public void setString(int index, String value) {
        checkIndex(index);
        params.put(index - 1, value);
    }

    public void setObject(int index, Object value) {
        checkIndex(index);
        params.put(index - 1, value);
    }

    public void setNull(int index) {
        checkIndex(index);
        params.put(index - 1, null);
    }

    /** 执行绑定后的语句 */
    public List<Row> execute() {
        // 检查所有参数已绑定
        if (params.size() < paramCount) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < paramCount; i++) {
                if (!params.containsKey(i)) {
                    missing.add(i + 1);
                }
            }
            throw new IllegalStateException("Parameters not set: " + missing);
        }

        // 绑定参数到 AST（不修改缓存的原始 AST）
        List<Object> paramList = new ArrayList<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            paramList.add(params.get(i));
        }
        ParameterBinder binder = new ParameterBinder(paramList);
        SqlNode boundAst = binder.bind(cachedAst);

        // validate → convert → optimize → plan → execute
        SqlValidator validator = new SqlValidator(catalog);
        SqlNode validated = validator.validate(boundAst);
        SqlToRelConverter converter = new SqlToRelConverter(catalog);
        RelNode logicalPlan = converter.convert(validated);
        RuleOptimizer ruleOpt = new RuleOptimizer(false, catalog);
        RelNode optimized = ruleOpt.optimize(logicalPlan);
        ExecNode exec = planner.plan(optimized);

        List<Row> results = new ArrayList<>();
        exec.open();
        try {
            Row row;
            while ((row = exec.next()) != null) {
                results.add(row);
            }
        } finally {
            exec.close();
        }
        return results;
    }

    /** 清空参数，复用缓存的 AST */
    public void reset() {
        params.clear();
    }

    public int paramCount() {
        return paramCount;
    }

    private void checkIndex(int index) {
        if (index < 1 || index > paramCount) {
            throw new IllegalArgumentException(
                    "Parameter index " + index + " out of range [1, " + paramCount + "]");
        }
    }
}
