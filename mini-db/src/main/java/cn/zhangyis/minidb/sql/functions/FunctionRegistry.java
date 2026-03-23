package cn.zhangyis.minidb.sql.functions;

import cn.zhangyis.minidb.sql.functions.impl.*;
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
        // 字符串函数
        register(new ConcatFunction());
        register(new SubstringFunction());
        register(new TrimFunction());
        register(new LengthFunction());
        register(new ReplaceFunction());
        // 数学函数
        register(new AbsFunction());
        register(new CeilFunction());
        register(new FloorFunction());
        register(new RoundFunction());
        register(new ModFunction());
        // 日期函数
        register(new NowFunction());
        register(new DateFormatFunction());
        register(new DateDiffFunction());
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