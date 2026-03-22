package cn.zhangyis.minidb.sql.ast;

import java.util.List;

public record SqlCte(String name, List<String> columnNames, SqlSelect query) implements SqlNode {

    /** 兼容旧代码的构造函数（columnNames为null表示未指定） */
    public SqlCte(String name, SqlSelect query) {
        this(name, null, query);
    }

    @Override
    public SqlKind kind() { return SqlKind.CTE; }

    /** 是否指定了列名列表 */
    public boolean hasColumnNames() {
        return columnNames != null && !columnNames.isEmpty();
    }
}
