package cn.zhangyis.sql;

import java.util.ArrayList;
import java.util.List;

public class SelectStatement extends SQLStatement {
    private List<SelectItem> selectItems;
    private List<TableReference> fromTables;
    private List<JoinClause> joins;
    private Expression whereCondition;
    private List<Expression> groupByColumns;
    private Expression havingCondition;
    private List<OrderByItem> orderByItems;
    private Integer limit;
    private boolean distinct;

    public SelectStatement(List<SelectItem> selectItems, List<TableReference> fromTables,
                           List<JoinClause> joins, Expression whereCondition,
                           List<Expression> groupByColumns, Expression havingCondition,
                           List<OrderByItem> orderByItems, Integer limit, boolean distinct) {
        super(SQLType.SELECT);
        this.selectItems = selectItems != null ? selectItems : new ArrayList<>();
        this.fromTables = fromTables != null ? fromTables : new ArrayList<>();
        this.joins = joins != null ? joins : new ArrayList<>();
        this.whereCondition = whereCondition;
        this.groupByColumns = groupByColumns != null ? groupByColumns : new ArrayList<>();
        this.havingCondition = havingCondition;
        this.orderByItems = orderByItems != null ? orderByItems : new ArrayList<>();
        this.limit = limit;
        this.distinct = distinct;
    }

    // Getters
    public List<SelectItem> getSelectItems() { return selectItems; }
    public List<TableReference> getFromTables() { return fromTables; }
    public List<JoinClause> getJoins() { return joins; }
    public Expression getWhereCondition() { return whereCondition; }
    public List<Expression> getGroupByColumns() { return groupByColumns; }
    public Expression getHavingCondition() { return havingCondition; }
    public List<OrderByItem> getOrderByItems() { return orderByItems; }
    public Integer getLimit() { return limit; }
    public boolean isDistinct() { return distinct; }
    
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
        if (distinct) sb.append("DISTINCT ");

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
            sb.append(" LIMIT ").append(limit);
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

        public Expression getExpression() { return expression; }
        public String getAlias() { return alias; }

        @Override
        public String toString() {
            return alias != null ? expression + " AS " + alias : expression.toString();
        }
    }

    public static class TableReference {
        private String tableName;
        private String alias;
        private SelectStatement subquery;

        public TableReference(String tableName, String alias) {
            this.tableName = tableName;
            this.alias = alias;
        }

        public TableReference(SelectStatement subquery, String alias) {
            this.subquery = subquery;
            this.alias = alias;
        }

        public String getTableName() { return tableName; }
        public String getAlias() { return alias; }
        public SelectStatement getSubquery() { return subquery; }
        public boolean isSubquery() { return subquery != null; }

        @Override
        public String toString() {
            String source = isSubquery() ? "(" + subquery + ")" : tableName;
            return alias != null ? source + " AS " + alias : source;
        }
    }

    public static class JoinClause {
        public enum JoinType { INNER, LEFT, RIGHT }

        private JoinType joinType;
        private TableReference joinTable;
        private Expression joinCondition;

        public JoinClause(JoinType joinType, TableReference joinTable, Expression joinCondition) {
            this.joinType = joinType;
            this.joinTable = joinTable;
            this.joinCondition = joinCondition;
        }

        public JoinType getJoinType() { return joinType; }
        public TableReference getJoinTable() { return joinTable; }
        public Expression getJoinCondition() { return joinCondition; }

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

        public Expression getExpression() { return expression; }
        public boolean isAscending() { return ascending; }

        @Override
        public String toString() {
            return expression + (ascending ? " ASC" : " DESC");
        }
    }
}