package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

/**
 * COALESCE 函数实现：返回第一个非 NULL 参数
 */
public class CoalesceFunction implements ScalarFunction {

    @Override
    public String name() {
        return "COALESCE";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        for (Object arg : args) {
            if (arg != null) {
                return arg;
            }
        }
        return null;
    }
}