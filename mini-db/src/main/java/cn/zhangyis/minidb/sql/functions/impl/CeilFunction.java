package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

public class CeilFunction implements ScalarFunction {

    @Override
    public String name() {
        return "CEIL";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.isEmpty() || args.get(0) == null) {
            return null;
        }
        Number num = (Number) args.get(0);
        return (long) Math.ceil(num.doubleValue());
    }
}
