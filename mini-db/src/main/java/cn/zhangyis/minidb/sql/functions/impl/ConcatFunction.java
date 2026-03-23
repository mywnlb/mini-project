package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

public class ConcatFunction implements ScalarFunction {

    @Override
    public String name() {
        return "CONCAT";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            if (arg == null) {
                return null;
            }
            sb.append(arg);
        }
        return sb.toString();
    }
}
