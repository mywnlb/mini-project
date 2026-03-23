package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

public class ModFunction implements ScalarFunction {

    @Override
    public String name() {
        return "MOD";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.size() < 2 || args.get(0) == null || args.get(1) == null) {
            return null;
        }
        Number x = (Number) args.get(0);
        Number y = (Number) args.get(1);
        if (y.doubleValue() == 0) {
            return null;
        }
        if (x instanceof Integer && y instanceof Integer) {
            return x.intValue() % y.intValue();
        } else if (x instanceof Long || y instanceof Long) {
            return x.longValue() % y.longValue();
        } else {
            return x.doubleValue() % y.doubleValue();
        }
    }
}
