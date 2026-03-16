package cn.zhangyis.minidb.sql.functions;

import cn.zhangyis.minidb.sql.functions.impl.LowerFunction;
import cn.zhangyis.minidb.sql.functions.impl.CoalesceFunction;
import cn.zhangyis.minidb.sql.functions.impl.UpperFunction;
import java.util.HashMap;
import java.util.Map;

/**
 * 函数注册表 - 简单实现，支持 UPPER 等
 */
public class FunctionRegistry {
    private static final Map<String, ScalarFunction> FUNCTIONS = new HashMap<>();

    static {
        register(new UpperFunction());
        register(new LowerFunction());
        register(new CoalesceFunction());
        // CAST 后续实现
    }

    public static void register(ScalarFunction function) {
        FUNCTIONS.put(function.name().toUpperCase(), function);
    }

    public static ScalarFunction get(String name) {
        return FUNCTIONS.get(name.toUpperCase());
    }

    public static boolean contains(String name) {
        return FUNCTIONS.containsKey(name.toUpperCase());
    }
}