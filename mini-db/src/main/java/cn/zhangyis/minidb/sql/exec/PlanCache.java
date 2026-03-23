package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU 计划缓存：SQL 文本模板 → CachedPlan(parsedAst, paramCount)
 *
 * 仅缓存 AST（不缓存逻辑计划），避免统计信息变化导致计划失效。
 */
public class PlanCache {

    private final Map<String, CachedPlan> cache;

    public PlanCache(int maxSize) {
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPlan> eldest) {
                return size() > maxSize;
            }
        };
    }

    public CachedPlan get(String sql) {
        return cache.get(sql);
    }

    public void put(String sql, CachedPlan plan) {
        cache.put(sql, plan);
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    public record CachedPlan(SqlNode ast, int paramCount) {}
}
