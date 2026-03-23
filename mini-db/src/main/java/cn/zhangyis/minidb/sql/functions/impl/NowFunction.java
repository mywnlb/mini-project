package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class NowFunction implements ScalarFunction {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return "NOW";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        return LocalDateTime.now().format(FMT);
    }
}
