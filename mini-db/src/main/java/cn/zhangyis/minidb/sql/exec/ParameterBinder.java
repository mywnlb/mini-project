package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.types.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数绑定器：递归遍历 AST 树，将 SqlParameter(idx) 替换为 SqlLiteral(params[idx])。
 * 不修改原始 AST，生成新树。
 */
public class ParameterBinder {

    private final List<Object> params;

    public ParameterBinder(List<Object> params) {
        this.params = params;
    }

    public SqlNode bind(SqlNode node) {
        if (node == null) return null;

        if (node instanceof SqlParameter p) {
            if (p.index() >= params.size()) {
                throw new IllegalArgumentException(
                        "Parameter index " + (p.index() + 1) + " not set (only " + params.size() + " parameters bound)");
            }
            return toLiteral(params.get(p.index()));
        }

        if (node instanceof SqlSelect s) {
            return new SqlSelect(
                    bindNodeList(s.projection()),
                    bind(s.from()),
                    bind(s.where()),
                    s.distinct(),
                    bindNodeList(s.groupBy()),
                    bind(s.having()),
                    bindNodeList(s.orderBy()),
                    bind(s.limit()),
                    bind(s.offset())
            );
        }

        if (node instanceof SqlWithSelect withSelect) {
            List<SqlCte> ctes = new ArrayList<>();
            for (SqlCte cte : withSelect.ctes()) {
                ctes.add(new SqlCte(cte.name(), cte.columnNames(), (SqlSelect) bind(cte.query())));
            }
            return new SqlWithSelect(ctes, (SqlSelect) bind(withSelect.select()));
        }

        if (node instanceof SqlSetOperation setOp) {
            return new SqlSetOperation(bind(setOp.left()), bind(setOp.right()), setOp.all(), setOp.opType());
        }

        if (node instanceof SqlExplain explain) {
            return new SqlExplain(bind(explain.query()), explain.analyze());
        }

        if (node instanceof SqlJoin join) {
            return new SqlJoin(
                    join.joinType(),
                    bind(join.left()),
                    bind(join.right()),
                    bind(join.condition()),
                    join.usingColumns(),
                    join.natural()
            );
        }

        if (node instanceof SqlDerivedTable derived) {
            return new SqlDerivedTable((SqlSelect) bind(derived.select()), derived.alias());
        }

        if (node instanceof SqlBinaryOp b) {
            SqlNode newLeft = bind(b.left());
            SqlNode newRight = bind(b.right());
            if (newLeft == b.left() && newRight == b.right()) return node;
            return new SqlBinaryOp(b.opKind(), newLeft, newRight);
        }

        if (node instanceof SqlInsert ins) {
            return new SqlInsert(ins.table(), ins.columns(), bindNodeList(ins.valueRows()));
        }

        if (node instanceof SqlUpdate upd) {
            return new SqlUpdate(upd.table(), bindNodeList(upd.assignments()), bind(upd.where()));
        }

        if (node instanceof SqlDelete del) {
            return new SqlDelete(del.table(), bind(del.where()));
        }

        if (node instanceof SqlBetween b) {
            return new SqlBetween(bind(b.expr()), bind(b.low()), bind(b.high()));
        }

        if (node instanceof SqlInList in) {
            return new SqlInList(bind(in.expr()), bindNodeList(in.values()));
        }

        if (node instanceof SqlFunctionCall f) {
            return new SqlFunctionCall(f.functionName(), bindNodeList(f.arguments()));
        }

        if (node instanceof SqlAssignment a) {
            return new SqlAssignment(a.column(), bind(a.value()));
        }

        if (node instanceof SqlCase c) {
            List<SqlCase.WhenThen> newWhenThens = new ArrayList<>();
            for (SqlCase.WhenThen wt : c.whenThens()) {
                newWhenThens.add(new SqlCase.WhenThen(bind(wt.condition()), bind(wt.result())));
            }
            return new SqlCase(newWhenThens, bind(c.elseExpr()));
        }

        if (node instanceof SqlCast cast) {
            return new SqlCast(bind(cast.expr()), cast.targetType());
        }

        if (node instanceof SqlAlias alias) {
            SqlNode newExpr = bind(alias.expression());
            if (newExpr == alias.expression()) return node;
            return new SqlAlias(newExpr, alias.alias());
        }

        if (node instanceof SqlOrderByItem item) {
            SqlNode newCol = bind(item.column());
            if (newCol == item.column()) return node;
            return new SqlOrderByItem(newCol, item.ascending());
        }

        if (node instanceof SqlWindowFunction wf) {
            return new SqlWindowFunction(
                    wf.funcName(),
                    bind(wf.arg()),
                    bindNodeList(wf.partitionBy()),
                    bindNodeList(wf.orderBy()),
                    bindNodeList(wf.extraArgs())
            );
        }

        if (node instanceof SqlAggCall agg) {
            SqlNode newArg = bind(agg.arg());
            if (newArg == agg.arg()) return node;
            return new SqlAggCall(agg.funcName(), newArg);
        }

        if (node instanceof SqlSubquery sub) {
            SqlNode bound = bind(sub.select());
            if (bound == sub.select()) return node;
            return new SqlSubquery((SqlSelect) bound);
        }

        if (node instanceof SqlExists ex) {
            SqlNode bound = bind(ex.select());
            if (bound == ex.select()) return node;
            return new SqlExists((SqlSelect) bound, ex.negated());
        }

        if (node instanceof SqlNodeList list) {
            return bindNodeList(list);
        }

        // 叶子节点（SqlIdentifier, SqlLiteral, SqlStar 等）无需绑定
        return node;
    }

    private SqlNodeList bindNodeList(SqlNodeList list) {
        if (list == null) return null;
        SqlNodeList result = new SqlNodeFactory().nodeList();
        boolean changed = false;
        for (SqlNode n : list.nodes()) {
            SqlNode bound = bind(n);
            result.add(bound);
            if (bound != n) changed = true;
        }
        return changed ? result : list;
    }

    private SqlNode toLiteral(Object value) {
        if (value == null) {
            return SqlNodeFactory.DEFAULT.nullLiteral();
        }
        if (value instanceof Integer || value instanceof Long) {
            return new SqlLiteral(String.valueOf(value), SqlType.INT32);
        }
        if (value instanceof Double || value instanceof Float) {
            return new SqlLiteral(String.valueOf(value), SqlType.DECIMAL);
        }
        // 默认作为字符串
        return new SqlLiteral(String.valueOf(value), SqlType.VARCHAR);
    }
}
