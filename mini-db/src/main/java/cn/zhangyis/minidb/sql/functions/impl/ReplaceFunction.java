package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

public class ReplaceFunction implements ScalarFunction {

    @Override
    public String name() {
        return "REPLACE";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.size() < 3 || args.get(0) == null || args.get(1) == null || args.get(2) == null) {
            return null;
        }
        String str = String.valueOf(args.get(0));
        String from = String.valueOf(args.get(1));
        String to = String.valueOf(args.get(2));
        return str.replace(from, to);
    }
}
