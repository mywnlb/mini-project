package cn.zhangyis.minidb.sql.functions;

import cn.zhangyis.minidb.sql.exec.Row;
import java.util.List;

/**
 * 标量函数接口 - 可扩展的函数求值
 */
public interface ScalarFunction {
    String name();
    Object evaluate(Row row, List<Object> args);
}