package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.ast.*;
import cn.zhangyis.sql.catalog.CatalogManager;
import cn.zhangyis.sql.catalog.Table;
import cn.zhangyis.sql.catalog.Column;

import java.util.*;

/**
 * 子查询验证器
 * 负责验证SQL子查询的语义正确性
 */
public class SubqueryValidator {
    private final CatalogManager catalogManager;
    private final ExpressionValidator expressionValidator;
    private final SqlValidatorScope scope;
    
    public SubqueryValidator(CatalogManager catalogManager, SqlValidatorScope scope) {
        this.catalogManager = catalogManager;
        this.expressionValidator = new ExpressionValidator(catalogManager);
        this.scope = scope;
    }
    
    /**
     * 验证子查询
     */
    public void validate(Subquery subquery) throws SemanticException {
        // 创建子查询的作用域
        SqlValidatorScope subqueryScope = scope.createChildScope();
        
        // 验证FROM子句并构建作用域
        validateFromClause(subquery, subqueryScope);
        
        // 验证SELECT子句
        validateSelectClause(subquery, subqueryScope);
        
        // 验证WHERE子句
        if (subquery.getWhereClause() != null) {
            expressionValidator.validate(subquery.getWhereClause());
        }
        
        // 验证GROUP BY子句
        if (subquery.getGroupByItems() != null) {
            for (Expression groupByItem : subquery.getGroupByItems()) {
                expressionValidator.validate(groupByItem);
            }
        }
        
        // 验证HAVING子句
        if (subquery.getHavingClause() != null) {
            expressionValidator.validate(subquery.getHavingClause());
        }
        
        // 验证ORDER BY子句
        if (subquery.getOrderByItems() != null) {
            for (OrderByItem orderByItem : subquery.getOrderByItems()) {
                expressionValidator.validate(orderByItem.getExpression());
            }
        }
    }
    
    /**
     * 验证FROM子句
     */
    private void validateFromClause(Subquery subquery, SqlValidatorScope subqueryScope) throws SemanticException {
        for (FromItem fromItem : subquery.getFromItems()) {
            if (fromItem instanceof TableFromItem) {
                TableFromItem tableItem = (TableFromItem) fromItem;
                String tableName = tableItem.getTableName();
                String alias = tableItem.getAlias();
                
                // 验证表是否存在
                if (!catalogManager.tableExists(tableName)) {
                    throw new SemanticException.TableNotFoundException(tableName);
                }
                
                // 添加表到作用域
                Table table = catalogManager.getTable(tableName);
                subqueryScope.addTable(alias != null ? alias : tableName, table);
                
            } else if (fromItem instanceof SubqueryFromItem) {
                SubqueryFromItem subqueryItem = (SubqueryFromItem) fromItem;
                String alias = subqueryItem.getAlias();
                
                // 验证子查询
                validate(subqueryItem.getSubquery());
                
                // 添加子查询的列到作用域
                for (SelectItem selectItem : subqueryItem.getSubquery().getSelectItems()) {
                    if (selectItem instanceof ExpressionSelectItem) {
                        ExpressionSelectItem exprItem = (ExpressionSelectItem) selectItem;
                        String columnName = exprItem.getAlias() != null ? 
                            exprItem.getAlias() : exprItem.getExpression().toString();
                        String columnKey = alias + "." + columnName;
                        subqueryScope.getColumns().put(columnKey, 
                            new Column(columnName, exprItem.getExpression().getType()));
                    }
                }
                
            } else if (fromItem instanceof JoinFromItem) {
                JoinFromItem joinItem = (JoinFromItem) fromItem;
                validateFromClause(new Subquery(Arrays.asList(joinItem.getLeft())), subqueryScope);
                validateFromClause(new Subquery(Arrays.asList(joinItem.getRight())), subqueryScope);
                
                if (joinItem.getCondition() != null) {
                    expressionValidator.validate(joinItem.getCondition());
                }
            }
        }
    }
    
    /**
     * 验证SELECT子句
     */
    private void validateSelectClause(Subquery subquery, SqlValidatorScope subqueryScope) throws SemanticException {
        // 检查是否是标量子查询
        if (subquery.isScalar() && subquery.getSelectItems().size() != 1) {
            throw new SemanticException("Scalar subquery must return exactly one column");
        }
        
        // 检查是否是IN子查询
        if (subquery.isInSubquery() && subquery.getSelectItems().size() != 1) {
            throw new SemanticException("IN subquery must return exactly one column");
        }
        
        // 检查是否是EXISTS子查询
        if (subquery.isExistsSubquery() && !subquery.getSelectItems().isEmpty()) {
            throw new SemanticException("EXISTS subquery should not have select items");
        }
        
        // 验证每个选择项
        for (SelectItem selectItem : subquery.getSelectItems()) {
            if (selectItem instanceof ExpressionSelectItem) {
                ExpressionSelectItem exprItem = (ExpressionSelectItem) selectItem;
                expressionValidator.validate(exprItem.getExpression());
            } else if (selectItem instanceof StarSelectItem) {
                // 星号选择项不需要验证
            } else {
                throw new SemanticException("Unsupported select item type: " + 
                    selectItem.getClass().getName());
            }
        }
    }
} 