package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

/**
 * DML 校验结果（INSERT/UPDATE/DELETE）
 */
public record ValidatedDml(SqlNode original, TableMeta tableMeta) implements SqlNode {
    @Override
    public SqlKind kind() { return original.kind(); }
}
