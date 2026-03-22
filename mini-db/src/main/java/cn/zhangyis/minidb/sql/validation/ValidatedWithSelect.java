package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.SqlCte;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

import java.util.List;
import java.util.Map;

/**
 * CTE (WITH) 查询验证结果，保留 CTE 定义及已验证的 CTE 查询供 SqlToRelConverter 展开
 */
public record ValidatedWithSelect(
    List<SqlCte> ctes,
    List<ValidatedSqlSelect> validatedCtes,
    Map<String, TableMeta> cteSchemas,
    ValidatedSqlSelect mainSelect
) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.WITH_SELECT; }
}
