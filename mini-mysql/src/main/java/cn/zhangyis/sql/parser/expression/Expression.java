package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.enums.FiledType;

import java.util.Collections;
import java.util.List;

/**
 * SQL表达式接口
 * 所有类型的表达式都实现此接口
 */
public abstract class Expression {
    protected ExpressionType type;
    protected FiledType columnType = null; // 用于存储列类型，默认为null

    /**
     * 获取表达式的类型
     *
     * @return 表达式类型
     */
    public abstract ExpressionType getType();

    /**
     * 设置表达式的类型
     *
     * @param type 表达式类型
     */
    public void setType(ExpressionType type) {
        this.type = type;
    }

    /**
     * 获取表达式的字符串表示
     */
    @Override
    public abstract String toString();

    /**
     * 获取所有子表达式
     * 用于支持递归处理表达式树
     *
     * @return 子表达式列表，如果没有子表达式则返回空列表
     */
    public List<Expression> getChildExpressions() {
        return Collections.emptyList();
    }

    public FiledType getColumnType() {
        return columnType;
    }

    public void setColumnType(FiledType columnType) {
        this.columnType = columnType;
    }
}

