package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.storage.catalog.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表值函数命名空间实现
 * 代表一个表值函数调用的命名空间
 * 参考 Apache Calcite 的 TableFunctionNamespace
 * 
 * 用于处理如下SQL:
 * SELECT * FROM TABLE(json_table('{"a": 1, "b": 2}', '$' COLUMNS(a INT PATH '$.a', b INT PATH '$.b')))
 * SELECT * FROM TABLE(generate_series(1, 10)) AS t(value)
 * SELECT * FROM unnest(ARRAY[1,2,3]) AS t(value)
 */
public class TableFunctionNamespace implements SqlValidatorNamespace {
    private final String functionName;
    private final List<Expression> arguments;
    private final String alias;
    private final Map<String, Column> columnMap;
    private List<Column> columns;
    private boolean validated = false;
    
    // 支持的表值函数定义
    private static final Map<String, TableFunctionSignature> SUPPORTED_FUNCTIONS = initSupportedFunctions();
    
    public TableFunctionNamespace(String functionName, List<Expression> arguments, String alias) {
        this.functionName = functionName;
        this.arguments = arguments;
        this.alias = alias;
        this.columnMap = new HashMap<>();
        this.columns = new ArrayList<>();
    }
    
    @Override
    public String getName() {
        return alias != null ? alias : functionName;
    }
    
    @Override
    public NamespaceType getType() {
        return NamespaceType.TABLE_FUNCTION;
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
        
        // 验证表值函数
        validateTableFunction();
        validated = true;
    }
    
    @Override
    public boolean isValidated() {
        return validated;
    }
    
    /**
     * 验证表值函数
     */
    private void validateTableFunction() throws SemanticException {
        columns.clear();
        columnMap.clear();
        
        // 检查函数是否支持
        TableFunctionSignature signature = SUPPORTED_FUNCTIONS.get(functionName.toUpperCase());
        if (signature == null) {
            throw new SemanticException("Unsupported table function: " + functionName);
        }
        
        // 验证参数数量
        if (arguments.size() < signature.getMinArgs() || arguments.size() > signature.getMaxArgs()) {
            throw new SemanticException("Invalid number of arguments for table function " + functionName +
                ": expected " + signature.getMinArgs() + "-" + signature.getMaxArgs() + 
                ", got " + arguments.size());
        }
        
        // 根据函数类型推导列信息
        deriveColumnsFromFunction(signature);
    }
    
    /**
     * 根据函数签名推导列信息
     */
    private void deriveColumnsFromFunction(TableFunctionSignature signature) throws SemanticException {
        switch (functionName.toUpperCase()) {
            case "GENERATE_SERIES":
                deriveGenerateSeriesColumns();
                break;
            case "UNNEST":
                deriveUnnestColumns();
                break;
            case "JSON_TABLE":
                deriveJsonTableColumns();
                break;
            case "STRING_SPLIT":
                deriveStringSplitColumns();
                break;
            default:
                // 使用默认的列定义
                deriveDefaultColumns(signature);
        }
    }
    
    /**
     * 推导GENERATE_SERIES函数的列
     */
    private void deriveGenerateSeriesColumns() {
        Column valueColumn = new Column("value", "BIGINT");
        columns.add(valueColumn);
        columnMap.put("value", valueColumn);
    }
    
    /**
     * 推导UNNEST函数的列
     */
    private void deriveUnnestColumns() {
        // UNNEST函数返回数组元素，类型根据输入数组推导
        String elementType = "VARCHAR"; // 默认类型
        
        // TODO: 根据输入数组的类型推导元素类型
        // 这里需要分析arguments中的数组表达式
        
        Column valueColumn = new Column("value", elementType);
        columns.add(valueColumn);
        columnMap.put("value", valueColumn);
    }
    
    /**
     * 推导JSON_TABLE函数的列
     */
    private void deriveJsonTableColumns() throws SemanticException {
        // JSON_TABLE函数需要解析COLUMNS子句
        // 这里简化处理，假设返回通用的JSON列
        if (arguments.size() < 2) {
            throw new SemanticException("JSON_TABLE requires at least 2 arguments");
        }
        
        // 简化实现：返回固定的列结构
        Column pathColumn = new Column("path", "VARCHAR");
        Column valueColumn = new Column("value", "VARCHAR");
        
        columns.add(pathColumn);
        columns.add(valueColumn);
        columnMap.put("path", pathColumn);
        columnMap.put("value", valueColumn);
    }
    
    /**
     * 推导STRING_SPLIT函数的列
     */
    private void deriveStringSplitColumns() {
        Column valueColumn = new Column("value", "VARCHAR");
        columns.add(valueColumn);
        columnMap.put("value", valueColumn);
    }
    
    /**
     * 使用默认的列定义
     */
    private void deriveDefaultColumns(TableFunctionSignature signature) {
        for (int i = 0; i < signature.getDefaultColumnCount(); i++) {
            String columnName = "column_" + (i + 1);
            String columnType = signature.getDefaultColumnType();
            
            Column column = new Column(columnName, columnType);
            columns.add(column);
            columnMap.put(columnName.toLowerCase(), column);
        }
    }
    
    /**
     * 获取函数名
     */
    public String getFunctionName() {
        return functionName;
    }
    
    /**
     * 获取参数列表
     */
    public List<Expression> getArguments() {
        return arguments;
    }
    
    /**
     * 获取别名
     */
    public String getAlias() {
        return alias;
    }
    
    /**
     * 初始化支持的表值函数
     */
    private static Map<String, TableFunctionSignature> initSupportedFunctions() {
        Map<String, TableFunctionSignature> functions = new HashMap<>();
        
        // GENERATE_SERIES(start, end [, step])
        functions.put("GENERATE_SERIES", new TableFunctionSignature(
            "GENERATE_SERIES", 2, 3, 1, "BIGINT"
        ));
        
        // UNNEST(array)
        functions.put("UNNEST", new TableFunctionSignature(
            "UNNEST", 1, 1, 1, "VARCHAR"
        ));
        
        // JSON_TABLE(json_doc, path COLUMNS(...))
        functions.put("JSON_TABLE", new TableFunctionSignature(
            "JSON_TABLE", 2, Integer.MAX_VALUE, 2, "VARCHAR"
        ));
        
        // STRING_SPLIT(string, delimiter)
        functions.put("STRING_SPLIT", new TableFunctionSignature(
            "STRING_SPLIT", 2, 2, 1, "VARCHAR"
        ));
        
        return functions;
    }
    
    @Override
    public String toString() {
        return "TableFunctionNamespace{" +
                "functionName='" + functionName + '\'' +
                ", alias='" + alias + '\'' +
                ", argumentCount=" + arguments.size() +
                ", columnCount=" + getColumnCount() +
                '}';
    }
    
    /**
     * 表值函数签名
     */
    private static class TableFunctionSignature {
        private final String name;
        private final int minArgs;
        private final int maxArgs;
        private final int defaultColumnCount;
        private final String defaultColumnType;
        
        public TableFunctionSignature(String name, int minArgs, int maxArgs, 
                                    int defaultColumnCount, String defaultColumnType) {
            this.name = name;
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.defaultColumnCount = defaultColumnCount;
            this.defaultColumnType = defaultColumnType;
        }
        
        public String getName() { return name; }
        public int getMinArgs() { return minArgs; }
        public int getMaxArgs() { return maxArgs; }
        public int getDefaultColumnCount() { return defaultColumnCount; }
        public String getDefaultColumnType() { return defaultColumnType; }
    }
} 