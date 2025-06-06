package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.ColumnExpression;
import cn.zhangyis.sql.Expression;
import cn.zhangyis.sql.SQLStatement;
import cn.zhangyis.sql.SelectStatement;
import cn.zhangyis.storage.catalog.Column;

/**
 * @Description 验证表名和列名的语义正确性
 * @Date 2025/6/6 14:43
 * @Created by libo
 */
public class ValidateTablesAndColumnsUtil {

    public ValidateTablesAndColumnsUtil() {
    }

    public static void validate(SQLStatement statement, SqlValidatorScope scope) {
        try {
            new ValidateTablesAndColumnsUtil().validateTablesAndColumns(statement, scope);
        } catch (SemanticException e) {
            throw new RuntimeException("Semantic validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 验证表名和列名
     * 使用作用域进行名称解析
     */
    private void validateTablesAndColumns(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;

            // 验证SELECT子句中的列
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                validateSelectItem(item, scope);
            }

            // 验证WHERE子句中的列
            if (select.getWhere() != null) {
                validateExpression(select.getWhere(), scope);
            }

            // TODO: 验证其他子句
        }
    }


    /**
     * 验证 SELECT 项
     */
    private void validateSelectItem(SelectStatement.SelectItem item, SqlValidatorScope scope) throws SemanticException {
        if (item.getExpression() instanceof ColumnExpression) {
            ColumnExpression colExpr = (ColumnExpression) item.getExpression();

            if (colExpr.isStar()) {
                // 星号表达式，稍后在展开阶段处理
                return;
            }

            // 使用当前作用域解析列名
            Column column = scope.findColumn(colExpr.getTableName(), colExpr.getColumnName());

            if (column == null) {
                String fullName = colExpr.getTableName() != null ?
                        colExpr.getTableName() + "." + colExpr.getColumnName() :
                        colExpr.getColumnName();
                throw new SemanticException.ColumnNotFoundException(colExpr.getTableName(), colExpr.getColumnName());
            }

            // 设置列的类型信息
            colExpr.setType(column.getType());
        } else {
            // 处理其他类型的表达式
            validateExpression(item.getExpression(), scope);
        }
    }


    /**
     * 验证表达式
     */
    private void validateExpression(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr instanceof ColumnExpression) {
            ColumnExpression colExpr = (ColumnExpression) expr;

            if (!colExpr.isStar()) {
                Column column = scope.findColumn(colExpr.getTableName(), colExpr.getColumnName());

                if (column == null) {
                    throw new SemanticException.ColumnNotFoundException(colExpr.getTableName(), colExpr.getColumnName());
                }

                colExpr.setType(column.getType());
            }
        } else if (expr instanceof BinaryExpression) {
            BinaryExpression binExpr = (BinaryExpression) expr;
            validateExpression(binExpr.getLeft(), scope);
            validateExpression(binExpr.getRight(), scope);
        }
        // TODO: 处理其他类型的表达式
    }

}
