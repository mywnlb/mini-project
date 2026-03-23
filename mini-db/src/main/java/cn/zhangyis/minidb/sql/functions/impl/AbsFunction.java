package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

public class AbsFunction implements ScalarFunction {

    @Override
    public String name() {
        return "ABS";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.isEmpty() || args.get(0) == null) {
            return null;
        }
        Number num = (Number) args.get(0);
        if (num instanceof Integer) {
            return Math.abs(num.intValue());
        } else if (num instanceof Long) {
            return Math.abs(num.longValue());
        } else {
            return Math.abs(num.doubleValue());
        }
    }
}
