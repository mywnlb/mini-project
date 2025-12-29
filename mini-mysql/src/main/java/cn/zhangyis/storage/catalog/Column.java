package cn.zhangyis.storage.catalog;

import cn.zhangyis.enums.FiledType;
import cn.zhangyis.exceptions.ColumnException;
import cn.zhangyis.sql.parser.enums.ComparisonOperator;
import lombok.Data;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * 数据库列定义
 * 表示数据库表中的一个列，包含列的元数据信息和值操作
 */
@Data
public abstract class Column implements Comparable<Column> {

    private Table table; // 所属的表
    private String name; // 列名
    private FiledType type; // 列类型
    private boolean nullable = true; // 是否允许为NULL
    private Integer length; // 长度（适用于VARCHAR等）
    private Integer precision; // 精度（适用于DECIMAL等）
    private Integer position; // 列在表中的位置
    private boolean isPrimaryKey; // 是否为主键
    private boolean isUnique; // 是否唯一
    private String defaultValue; // 默认值表达式
    private String comment; // 列注释

    /**
     * 无参构造函数
     */
    public Column() {
    }

    /**
     * 基础构造函数
     */
    public Column(String name, FiledType type) {
        this.name = name;
        this.type = type;
    }

    /**
     * 完整构造函数
     */
    public Column(String name, FiledType type, boolean nullable, Integer length, Integer precision,
            boolean isPrimaryKey, boolean isUnique, String defaultValue, String comment) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.length = length;
        this.precision = precision;
        this.isPrimaryKey = isPrimaryKey;
        this.isUnique = isUnique;
        this.defaultValue = defaultValue;
        this.comment = comment;
    }

    /**
     * 将字段值序列化到输出流
     *
     * @param dos 数据输出流
     * @throws IOException 如果发生I/O错误
     */
    public abstract void serialize(DataOutputStream dos) throws IOException;

    /**
     * 获取此字段的值作为对象
     * 
     * @return 字段值对象
     */
    public abstract Object getValue();

    /**
     * 设置此字段的值
     * 
     * @param value 要设置的值
     * @throws IllegalArgumentException 如果值与字段类型不兼容
     */
    public abstract void setValue(Object value);

    /**
     * 比较此字段的值与另一个字段
     *
     * @param op    比较操作符
     * @param value 要比较的字段
     * @return 比较结果
     */
    public abstract boolean compare(ComparisonOperator op, Column value);

    /**
     * 检查值是否为NULL
     * 
     * @return 如果值为NULL返回true
     */
    public abstract boolean isNull();

    /**
     * 获取字段类型
     */
    public FiledType getType() {
        return type;
    }

    /**
     * 获取字段类型描述，包含长度和精度信息
     */
    public String getTypeDescription() {
        return type.getTypeDescription(length, precision);
    }

    /**
     * 获取字段的DDL定义
     */
    public String getDDLDefinition() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" ").append(getTypeDescription());

        if (!nullable) {
            sb.append(" NOT NULL");
        }

        if (defaultValue != null && !defaultValue.isEmpty()) {
            sb.append(" DEFAULT ").append(defaultValue);
        }

        if (isPrimaryKey) {
            sb.append(" PRIMARY KEY");
        }

        if (isUnique && !isPrimaryKey) {
            sb.append(" UNIQUE");
        }

        if (comment != null && !comment.isEmpty()) {
            sb.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
        }

        return sb.toString();
    }

    /**
     * 检查值是否符合列约束
     */
    public void checkConstraints(Object value) throws IllegalArgumentException {
        // 检查NULL约束
        if (value == null && !nullable) {
            throw new ColumnException("Column '" + name + "' cannot be NULL");
        }

        // 检查类型兼容性
        if (value != null && !type.isCompatibleWith(value)) {
            throw new ColumnException("Value '" + value +
                    "' is incompatible with column '" + name + "' of type " + type.getSqlTypeName());
        }

        // 子类可以实现更多约束检查
    }

    @Override
    public int compareTo(Column other) {
        if (this.isNull() && other.isNull()) {
            return 0;
        }
        if (this.isNull()) {
            return -1;
        }
        if (other.isNull()) {
            return 1;
        }

        // 委托给子类实现具体比较逻辑
        return compareValues(other);
    }

    /**
     * 比较两个非NULL字段值
     */
    protected abstract int compareValues(Column other);

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Column column = (Column) o;
        return Objects.equals(name, column.name) &&
                Objects.equals(type, column.type) &&
                Objects.equals(table, column.table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, table);
    }

    @Override
    public String toString() {
        return String.format("%s.%s: %s",
                table != null ? table.getName() : "?",
                name,
                getTypeDescription());
    }

}
