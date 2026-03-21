package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.SqlCte;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;

import java.util.List;

/**
 * CTE (WITH) 查询验证结果，保留 CTE 定义供 SqlToRelConverter 展开
 */
public record ValidatedWithSelect(
    List<SqlCte> ctes,
    ValidatedSqlSelect mainSelect
) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.WITH_SELECT; }
}
