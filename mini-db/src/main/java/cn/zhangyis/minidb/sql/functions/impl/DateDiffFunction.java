package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class DateDiffFunction implements ScalarFunction {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String name() {
        return "DATEDIFF";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.size() < 2 || args.get(0) == null || args.get(1) == null) {
            return null;
        }
        try {
            String s1 = String.valueOf(args.get(0));
            String s2 = String.valueOf(args.get(1));
            // 截取日期部分（忽略时间部分）
            if (s1.contains(" ")) s1 = s1.substring(0, 10);
            if (s2.contains(" ")) s2 = s2.substring(0, 10);
            LocalDate d1 = LocalDate.parse(s1, FMT);
            LocalDate d2 = LocalDate.parse(s2, FMT);
            return (int) ChronoUnit.DAYS.between(d2, d1);
        } catch (Exception e) {
            return null;
        }
    }
}
