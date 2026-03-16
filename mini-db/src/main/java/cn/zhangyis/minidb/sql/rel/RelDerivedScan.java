package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlSelect;
import java.util.List;

/**
 * 派生表（FROM 子查询）的逻辑计划节点
 * 包含内层查询的 RelNode 子树和别名
 */
public class RelDerivedScan extends RelNode {
    private final RelNode input;
    private final String alias;
    private final SqlSelect originalSelect;

    public RelDerivedScan(RelNode input, String alias, SqlSelect originalSelect) {
        this.input = input;
        this.alias = alias;
        this.originalSelect = originalSelect;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelDerivedScan(inputs.get(0), alias, originalSelect);
    }

    @Override
    public String explain() {
        return "RelDerivedScan(alias=" + alias + ")\n  " + input.explain();
    }

    public RelNode input() { return input; }
    public String alias() { return alias; }
    public SqlSelect originalSelect() { return originalSelect; }
}
