package cn.zhangyis.minidb.sql.ast;

import java.util.List;

/**
 * CASE WHEN 表达式
 * 支持: CASE WHEN cond THEN result [WHEN ...] [ELSE elseExpr] END
 */
public record SqlCase(List<WhenThen> whenThens, SqlNode elseExpr) implements SqlNode {

    public record WhenThen(SqlNode condition, SqlNode result) {
    }

    @Override
    public SqlKind kind() {
        return SqlKind.CASE;
    }
}