package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * 逻辑值列表节点
 * 表示VALUES子句的常量行
 */
public class LogicalValues extends AbstractRelNode {
    private final List<List<Expression>> rows;

    /**
     * 创建值列表节点
     * 
     * @param rows  行列表，每行是一个表达式列表
     * @param alias 别名（可选）
     */
    public LogicalValues(List<List<Expression>> rows, String alias) {
        super();
        this.rows = new ArrayList<>();

        if (rows != null) {
            for (List<Expression> row : rows) {
                this.rows.add(new ArrayList<>(row));
            }
        }

        if (alias != null) {
            setAlias(alias);
        }

        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.VALUES;
    }

    @Override
    protected void deriveOutput() {
        outputExpressions.clear();
        outputNames.clear();

        // 如果没有行，无法推导输出
        if (rows.isEmpty()) {
            return;
        }

        // 从第一行推导输出列
        List<Expression> firstRow = rows.get(0);

        for (int i = 0; i < firstRow.size(); i++) {
            // 使用第一行的表达式作为输出表达式
            outputExpressions.add(firstRow.get(i));

            // 生成默认列名
            outputNames.add("col_" + (i + 1));
        }
    }

    /**
     * 获取行列表
     */
    public List<List<Expression>> getRows() {
        return rows;
    }

    /**
     * 获取行数
     */
    public int getRowCount() {
        return rows.size();
    }

    /**
     * 获取列数
     */
    public int getColumnCount() {
        return rows.isEmpty() ? 0 : rows.get(0).size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LogicalValues(rows=").append(getRowCount());
        sb.append(", columns=").append(getColumnCount());

        if (getAlias() != null) {
            sb.append(", alias=").append(getAlias());
        }

        sb.append(")");
        return sb.toString();
    }
}