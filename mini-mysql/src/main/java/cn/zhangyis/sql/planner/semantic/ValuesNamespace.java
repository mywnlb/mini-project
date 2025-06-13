package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.sql.parser.expression.LiteralExpression;
import cn.zhangyis.storage.catalog.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VALUES命名空间实现
 * 代表一个VALUES子句的命名空间
 * 参考 Apache Calcite 的 ValuesNamespace
 * 
 * 用于处理如下SQL:
 * VALUES (1, 'a'), (2, 'b'), (3, 'c')
 * INSERT INTO table VALUES (1, 'a'), (2, 'b')
 */
public class ValuesNamespace implements SqlValidatorNamespace {
    private final List<List<Expression>> valuesList;
    private final String alias;
    private final Map<String, Column> columnMap;
    private List<Column> columns;
    private boolean validated = false;
    
    public ValuesNamespace(List<List<Expression>> valuesList, String alias) {
        this.valuesList = valuesList;
        this.alias = alias;
        this.columnMap = new HashMap<>();
        this.columns = new ArrayList<>();
    }
    
    @Override
    public String getName() {
        return alias != null ? alias : "VALUES";
    }
    
    @Override
    public NamespaceType getType() {
        return NamespaceType.VALUES;
    }
    
    @Override
    public Column findColumn(String columnName) {
        return columnMap.get(columnName.toLowerCase());
    }
    
    @Override
    public List<Column> getColumns() {
        return new ArrayList<>(columns);
    }
    
    @Override
    public boolean hasColumn(String columnName) {
        return columnMap.containsKey(columnName.toLowerCase());
    }
    
    @Override
    public int getColumnCount() {
        return columns.size();
    }
    
    @Override
    public void validate() throws SemanticException {
        if (validated) {
            return;
        }
        
        // 推导VALUES子句的列信息
        deriveColumnsFromValues();
        validated = true;
    }
    
    @Override
    public boolean isValidated() {
        return validated;
    }
    
    /**
     * 从VALUES子句推导列信息
     */
    private void deriveColumnsFromValues() throws SemanticException {
        columns.clear();
        columnMap.clear();
        
        if (valuesList.isEmpty()) {
            throw new SemanticException("VALUES clause cannot be empty");
        }
        
        // 从第一行确定列数
        List<Expression> firstRow = valuesList.get(0);
        int columnCount = firstRow.size();
        
        // 验证所有行的列数是否一致
        for (List<Expression> row : valuesList) {
            if (row.size() != columnCount) {
                throw new SemanticException("VALUES clause rows must have the same number of columns");
            }
        }
        
        // 为每列推导类型并创建列对象
        for (int i = 0; i < columnCount; i++) {
            String columnName = "column_" + (i + 1);
            String columnType = deriveColumnType(i);
            
            Column column = new Column(columnName, columnType);
            columns.add(column);
            columnMap.put(columnName.toLowerCase(), column);
        }
    }
    
    /**
     * 推导指定列位置的类型
     */
    private String deriveColumnType(int columnIndex) {
        // 遍历所有行中指定位置的值，推导最合适的类型
        String resultType = null;
        
        for (List<Expression> row : valuesList) {
            Expression expr = row.get(columnIndex);
            String exprType = inferExpressionType(expr);
            
            if (resultType == null) {
                resultType = exprType;
            } else {
                // 类型合并逻辑
                resultType = mergeTypes(resultType, exprType);
            }
        }
        
        return resultType != null ? resultType : "VARCHAR";
    }
    
    /**
     * 推导表达式的类型
     */
    private String inferExpressionType(Expression expr) {
        if (expr instanceof LiteralExpression) {
            LiteralExpression literal = (LiteralExpression) expr;
            
            if (literal.isNumber()) {
                String value = literal.getValue().toString();
                if (value.contains(".")) {
                    return "DOUBLE";
                } else {
                    try {
                        Long.parseLong(value);
                        return "BIGINT";
                    } catch (NumberFormatException e) {
                        return "DOUBLE";
                    }
                }
            } else if (literal.isString()) {
                return "VARCHAR";
            } else if (literal.isBoolean()) {
                return "BOOLEAN";
            } else if (literal.isNull()) {
                return "VARCHAR"; // NULL可以转换为任何类型，默认使用VARCHAR
            }
        }
        
        // 其他类型的表达式
        return "VARCHAR";
    }
    
    /**
     * 合并两个类型，选择更通用的类型
     */
    private String mergeTypes(String type1, String type2) {
        if (type1.equals(type2)) {
            return type1;
        }
        
        // 数值类型优先级：DOUBLE > FLOAT > BIGINT > INT
        if (isNumericType(type1) && isNumericType(type2)) {
            if (type1.equals("DOUBLE") || type2.equals("DOUBLE")) {
                return "DOUBLE";
            }
            if (type1.equals("FLOAT") || type2.equals("FLOAT")) {
                return "FLOAT";
            }
            if (type1.equals("BIGINT") || type2.equals("BIGINT")) {
                return "BIGINT";
            }
            return "INT";
        }
        
        // 字符串类型优先级：TEXT > VARCHAR > CHAR
        if (isStringType(type1) && isStringType(type2)) {
            if (type1.equals("TEXT") || type2.equals("TEXT")) {
                return "TEXT";
            }
            return "VARCHAR";
        }
        
        // 不同类型族之间，默认转换为VARCHAR
        return "VARCHAR";
    }
    
    /**
     * 检查是否是数值类型
     */
    private boolean isNumericType(String type) {
        return type.equals("INT") || type.equals("BIGINT") || 
               type.equals("FLOAT") || type.equals("DOUBLE");
    }
    
    /**
     * 检查是否是字符串类型
     */
    private boolean isStringType(String type) {
        return type.equals("CHAR") || type.equals("VARCHAR") || type.equals("TEXT");
    }
    
    /**
     * 获取VALUES列表
     */
    public List<List<Expression>> getValuesList() {
        return valuesList;
    }
    
    /**
     * 获取别名
     */
    public String getAlias() {
        return alias;
    }
    
    @Override
    public String toString() {
        return "ValuesNamespace{" +
                "alias='" + alias + '\'' +
                ", rowCount=" + valuesList.size() +
                ", columnCount=" + getColumnCount() +
                '}';
    }
} 