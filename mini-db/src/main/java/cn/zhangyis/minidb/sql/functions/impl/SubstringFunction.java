package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.util.List;

public class SubstringFunction implements ScalarFunction {

    @Override
    public String name() {
        return "SUBSTRING";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.size() < 2 || args.get(0) == null) {
            return null;
        }
        String str = String.valueOf(args.get(0));
        int start = ((Number) args.get(1)).intValue();
        // SQL下标从1开始，转换为Java的0开始
        int javaStart = Math.max(start - 1, 0);
        if (javaStart >= str.length()) {
            return "";
        }
        if (args.size() >= 3 && args.get(2) != null) {
            int len = ((Number) args.get(2)).intValue();
            int end = Math.min(javaStart + len, str.length());
            return str.substring(javaStart, end);
        }
        return str.substring(javaStart);
    }
}
