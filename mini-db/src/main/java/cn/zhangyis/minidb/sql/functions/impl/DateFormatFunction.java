package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class DateFormatFunction implements ScalarFunction {

    @Override
    public String name() {
        return "DATE_FORMAT";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.size() < 2 || args.get(0) == null || args.get(1) == null) {
            return null;
        }
        String dateStr = String.valueOf(args.get(0));
        String mysqlFmt = String.valueOf(args.get(1));

        // 将MySQL格式转为Java格式: %Y→yyyy, %m→MM, %d→dd, %H→HH, %i→mm, %s→ss
        String javaFmt = mysqlFmt
                .replace("%Y", "yyyy")
                .replace("%m", "MM")
                .replace("%d", "dd")
                .replace("%H", "HH")
                .replace("%i", "mm")
                .replace("%s", "ss");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(javaFmt);
        try {
            if (dateStr.contains(" ")) {
                LocalDateTime dt = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return dt.format(formatter);
            } else {
                LocalDate d = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                return d.format(formatter);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
