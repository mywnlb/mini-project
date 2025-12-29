package cn.zhangyis.sql.parser;

import cn.zhangyis.sql.parser.enums.JoinType;
import cn.zhangyis.sql.parser.expression.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL SELECT 语句表示
 * 包含选择项、表引用、连接、条件、分组、排序等信息
 * 参考 Apache Calcite 的 SelectStatement 设计
 */
public class SelectStatement extends SQLStatement {
    private List<SelectItem> selectItems;
    private List<TableReference> fromTables;
    private List<JoinClause> joins;
    private Expression whereCondition;
    private List<Expression> groupByColumns;
    private Expression havingCondition;
    private List<OrderByItem> orderByItems;
    private Integer limit;
    private Integer offset;
    private boolean distinct;

    public SelectStatement(List<SelectItem> selectItems, List<TableReference> fromTables,
                           List<JoinClause> joins, Expression whereCondition,
                           List<Expression> groupByColumns, Expression havingCondition,
                           List<OrderByItem> orderByItems, Integer limit, Integer offset, boolean distinct) {
        super(SQLType.SELECT);
        this.selectItems = selectItems != null ? selectItems : new ArrayList<>();
        this.fromTables = fromTables != null ? fromTables : new ArrayList<>();
        this.joins = joins != null ? joins : new ArrayList<>();
        this.whereCondition = whereCondition;
        this.groupByColumns = groupByColumns != null ? groupByColumns : new ArrayList<>();
        this.havingCondition = havingCondition;
        this.orderByItems = orderByItems != null ? orderByItems : new ArrayList<>();
        this.limit = limit;
        this.offset = offset;
        this.distinct = distinct;
    }

    // Getters
    public List<SelectItem> getSelectItems() {
        return selectItems;
    }

    public List<TableReference> getFromTables() {
        return fromTables;
    }

    public List<JoinClause> getJoins() {
        return joins;
    }

    public Expression getWhereCondition() {
        return whereCondition;
    }

    public List<Expression> getGroupByColumns() {
        return groupByColumns;
    }

    public Expression getHavingCondition() {
        return havingCondition;
    }

    public List<OrderByItem> getOrderByItems() {
        return orderByItems;
    }

    public Integer getLimit() {
        return limit;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public Integer getOffset() {
        return offset;
    }

    // Setters
    public void setSelectItems(List<SelectItem> selectItems) {
        this.selectItems = selectItems != null ? selectItems : new ArrayList<>();
    }

    public void setFromTables(List<TableReference> fromTables) {
        this.fromTables = fromTables != null ? fromTables : new ArrayList<>();
    }

    public void setJoins(List<JoinClause> joins) {
        this.joins = joins != null ? joins : new ArrayList<>();
    }

    public void setWhereCondition(Expression whereCondition) {
        this.whereCondition = whereCondition;
    }

    public void setGroupByColumns(List<Expression> groupByColumns) {
        this.groupByColumns = groupByColumns != null ? groupByColumns : new ArrayList<>();
    }

    public void setHavingCondition(Expression havingCondition) {
        this.havingCondition = havingCondition;
    }

    public void setOrderByItems(List<OrderByItem> orderByItems) {
        this.orderByItems = orderByItems != null ? orderByItems : new ArrayList<>();
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    // 便利方法 - 兼容新的语义分析器接口
    public List<TableReference> getFrom() {
        return getFromTables();
    }

    public Expression getWhere() {
        return getWhereCondition();
    }

    public void setWhere(Expression where) {
        setWhereCondition(where);
    }

    public Expression getHaving() {
        return getHavingCondition();
    }

    public void setHaving(Expression having) {
        setHavingCondition(having);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SELECT ");

        // 选择项
        for (int i = 0; i < selectItems.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(selectItems.get(i));
        }

        // FROM子句
        if (!fromTables.isEmpty()) {
            sb.append(" FROM ");
            for (int i = 0; i < fromTables.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(fromTables.get(i));
            }
        }

        // JOIN子句
        for (JoinClause join : joins) {
            sb.append(" ");
            switch (join.getJoinType()) {
                case INNER:
                    sb.append("INNER JOIN ");
                    break;
                case LEFT:
                    sb.append("LEFT JOIN ");
                    break;
                case RIGHT:
                    sb.append("RIGHT JOIN ");
                    break;
            }
            sb.append(join.getJoinTable());
            sb.append(" ON ");
            sb.append(join.getJoinCondition());
        }

        // WHERE子句
        if (whereCondition != null) {
            sb.append(" WHERE ").append(whereCondition);
        }

        // GROUP BY
        if (!groupByColumns.isEmpty()) {
            sb.append(" GROUP BY ");
            for (int i = 0; i < groupByColumns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(groupByColumns.get(i));
            }
        }

        // HAVING
        if (havingCondition != null) {
            sb.append(" HAVING ").append(havingCondition);
        }

        // ORDER BY
        if (!orderByItems.isEmpty()) {
            sb.append(" ORDER BY ");
            for (int i = 0; i < orderByItems.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(orderByItems.get(i));
            }
        }

        // LIMIT
        if (limit != null) {
            if (Objects.nonNull(offset)) {
                sb.append(" OFFSET ").append(offset);
            } else {
                sb.append(" OFFSET 0");
            }
            sb.append(" get ").append(limit);
        }

        return sb.toString();
    }

    // SELECT语句的内部类组件
    public static class SelectItem {
        private Expression expression;
        private String alias;

        public SelectItem(Expression expression, String alias) {
            this.expression = expression;
            this.alias = alias;
        }

        public Expression getExpression() {
            return expression;
        }

        public void setExpression(Expression expression) {
            this.expression = expression;
        }

        public String getAlias() {
            return alias;
        }

        @Override
        public String toString() {
            return alias != null ? expression + " AS " + alias : expression.toString();
        }

        public String getAliasOrName() {
            return alias != null ? alias : expression.toString();
        }
    }

    public static class TableReference {
        private String tableName;
        private String alias;
        private SelectStatement subquery;
        private List<List<Expression>> valuesList; // VALUES子句
        private String functionName; // 表值函数名
        private List<Expression> functionArguments; // 表值函数参数

        // 普通表引用构造器
        public TableReference(String tableName, String alias) {
            this.tableName = tableName;
            this.alias = alias;
        }

        // 子查询引用构造器
        public TableReference(SelectStatement subquery, String alias) {
            this.subquery = subquery;
            this.alias = alias;
        }

        // VALUES子句构造器
        public TableReference(List<List<Expression>> valuesList, String alias) {
            this.valuesList = valuesList;
            this.alias = alias;
        }

        // 表值函数构造器
        public TableReference(String functionName, List<Expression> functionArguments, String alias) {
            this.functionName = functionName;
            this.functionArguments = functionArguments;
            this.alias = alias;
        }

        public String getTableName() {
            return tableName;
        }

        public String getAlias() {
            return alias;
        }

        public SelectStatement getSubquery() {
            return subquery;
        }

        public List<List<Expression>> getValuesList() {
            return valuesList;
        }

        public String getFunctionName() {
            return functionName;
        }

        public List<Expression> getFunctionArguments() {
            return functionArguments;
        }

        public boolean isSubquery() {
            return subquery != null;
        }

        public boolean isValues() {
            return valuesList != null;
        }

        public boolean isTableFunction() {
            return functionName != null;
        }

        @Override
        public String toString() {
            String source;
            if (isSubquery()) {
                source = "(" + subquery + ")";
            } else if (isValues()) {
                source = "VALUES " + formatValuesList();
            } else if (isTableFunction()) {
                source = "TABLE(" + functionName + "(" + formatFunctionArguments() + "))";
            } else {
                source = tableName;
            }
            return alias != null ? source + " AS " + alias : source;
        }

        private String formatValuesList() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < valuesList.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("(");
                List<Expression> row = valuesList.get(i);
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(row.get(j));
                }
                sb.append(")");
            }
            return sb.toString();
        }

        private String formatFunctionArguments() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < functionArguments.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(functionArguments.get(i));
            }
            return sb.toString();
        }
    }

    public static class JoinClause {

        private JoinType joinType;
        private TableReference joinTable;
        private Expression joinCondition;

        public JoinClause(JoinType joinType, TableReference joinTable, Expression joinCondition) {
            this.joinType = joinType;
            this.joinTable = joinTable;
            this.joinCondition = joinCondition;
        }

        public JoinType getJoinType() {
            return joinType;
        }

        public TableReference getJoinTable() {
            return joinTable;
        }

        public Expression getJoinCondition() {
            return joinCondition;
        }

        public void setJoinCondition(Expression joinCondition) {
            this.joinCondition = joinCondition;
        }

        @Override
        public String toString() {
            return joinType + " JOIN " + joinTable + " ON " + joinCondition;
        }
    }

    public static class OrderByItem {
        private Expression expression;
        private boolean ascending;

        public OrderByItem(Expression expression, boolean ascending) {
            this.expression = expression;
            this.ascending = ascending;
        }

        public Expression getExpression() {
            return expression;
        }

        public void setExpression(Expression expression) {
            this.expression = expression;
        }

        public boolean isAscending() {
            return ascending;
        }

        @Override
        public String toString() {
            return expression + (ascending ? " ASC" : " DESC");
        }
    }
}