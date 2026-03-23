package cn.zhangyis.minidb.sql.functions.impl;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class RoundFunction implements ScalarFunction {

    @Override
    public String name() {
        return "ROUND";
    }

    @Override
    public Object evaluate(Row row, List<Object> args) {
        if (args.isEmpty() || args.get(0) == null) {
            return null;
        }
        Number num = (Number) args.get(0);
        int decimals = 0;
        if (args.size() >= 2 && args.get(1) != null) {
            decimals = ((Number) args.get(1)).intValue();
        }
        BigDecimal bd = BigDecimal.valueOf(num.doubleValue());
        bd = bd.setScale(decimals, RoundingMode.HALF_UP);
        if (decimals == 0) {
            return bd.longValue();
        }
        return bd.doubleValue();
    }
}
