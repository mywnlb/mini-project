package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

public class TrimFunction implements ScalarFunction {

    @Override
    public String name() {
        return "TRIM";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.isEmpty() || args.get(0) == null) {
            return null;
        }
        return String.valueOf(args.get(0)).trim();
    }
}
