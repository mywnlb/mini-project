package cn.zhangyis.sql.planner.semantic;



import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;

import java.util.*;

/**
 * SQL验证作用域
 * 负责管理SQL验证过程中的作用域信息
 * 参考 Apache Calcite 的 SqlValidatorScope 设计
 */
public class SqlValidatorScope {
    private final SqlValidatorScope parent; // 父作用域
    private final Map<String, SqlValidatorNamespace> namespaces; // 命名空间映射
    private final Map<String, String> aliases; // 别名映射 (alias -> realName)
    private final Map<String, Column> resolvedColumns; // 已解析的列缓存
    private final ScopeType scopeType; // 作用域类型
    
    public enum ScopeType {
        TOP_LEVEL,      // 顶级作用域
        SELECT,         // SELECT 作用域
        FROM,           // FROM 作用域  
        WHERE,          // WHERE 作用域
        GROUP_BY,       // GROUP BY 作用域
        HAVING,         // HAVING 作用域
        ORDER_BY,       // ORDER BY 作用域
        SUBQUERY        // 子查询作用域
    }
    
    public SqlValidatorScope() {
        this(null, ScopeType.TOP_LEVEL);
    }
    
    public SqlValidatorScope(SqlValidatorScope parent) {
        this(parent, ScopeType.SELECT);
    }
    
    public SqlValidatorScope(SqlValidatorScope parent, ScopeType scopeType) {
        this.parent = parent;
        this.scopeType = scopeType;
        this.namespaces = new HashMap<>();
        this.aliases = new HashMap<>();
        this.resolvedColumns = new HashMap<>();
    }
    
    /**
     * 添加命名空间到作用域
     */
    public void addNamespace(String name, SqlValidatorNamespace namespace) {
        namespaces.put(name, namespace);
    }
    
    /**
     * 添加表到作用域（创建对应的命名空间）
     */
    public void addTable(String name, Table table) {
        addTable(name, table, null);
    }
    
    /**
     * 添加表到作用域，支持别名
     */
    public void addTable(String name, Table table, String alias) {
        SqlValidatorNamespace namespace = new TableNamespace(table);
        namespaces.put(name, namespace);
        
        if (alias != null && !alias.equals(name)) {
            aliases.put(alias, name);
            namespaces.put(alias, namespace);
        }
    }
    
    /**
     * 查找列，支持限定名和非限定名
     */
    public Column findColumn(String tableName, String columnName) {
        String key = (tableName != null) ? tableName + "." + columnName : columnName;
        
        // 先查缓存
        Column cached = resolvedColumns.get(key);
        if (cached != null) {
            return cached;
        }
        
        Column result = null;
        
        if (tableName != null) {
            // 限定名查找
            result = findQualifiedColumn(tableName, columnName);
        } else {
            // 非限定名查找
            result = findUnqualifiedColumn(columnName);
        }
        
        // 如果当前作用域找不到，查找父作用域
        if (result == null && parent != null) {
            result = parent.findColumn(tableName, columnName);
        }
        
        // 缓存结果
        if (result != null) {
            resolvedColumns.put(key, result);
        }
        
        return result;
    }
    
    /**
     * 查找限定列名
     */
    private Column findQualifiedColumn(String tableName, String columnName) {
        // 处理别名
        String realTableName = aliases.getOrDefault(tableName, tableName);
        
        SqlValidatorNamespace namespace = namespaces.get(realTableName);
        if (namespace != null) {
            return namespace.findColumn(columnName);
        }
        
        return null;
    }
    
    /**
     * 查找非限定列名
     */
    private Column findUnqualifiedColumn(String columnName) {
        List<Column> candidates = new ArrayList<>();
        
        // 在所有命名空间中查找
        for (SqlValidatorNamespace namespace : namespaces.values()) {
            Column column = namespace.findColumn(columnName);
            if (column != null) {
                candidates.add(column);
            }
        }
        
        if (candidates.isEmpty()) {
            return null;
        } else if (candidates.size() == 1) {
            return candidates.get(0);
        } else {
            // 多个候选，需要报告歧义错误
            throw new SemanticException("Column '" + columnName + "' is ambiguous");
        }
    }
    
    /**
     * 获取列的类型
     */
    public String getColumnType(String tableName, String columnName) {
        Column column = findColumn(tableName, columnName);
        return column != null ? column.getType() : null;
    }
    
    /**
     * 创建子作用域
     */
    public SqlValidatorScope createChildScope() {
        return createChildScope(ScopeType.SELECT);
    }
    
    /**
     * 创建指定类型的子作用域
     */
    public SqlValidatorScope createChildScope(ScopeType scopeType) {
        return new SqlValidatorScope(this, scopeType);
    }
    
    /**
     * 获取父作用域
     */
    public SqlValidatorScope getParent() {
        return parent;
    }
    
    /**
     * 获取作用域类型
     */
    public ScopeType getScopeType() {
        return scopeType;
    }
    
    /**
     * 获取所有命名空间
     */
    public Map<String, SqlValidatorNamespace> getNamespaces() {
        return Collections.unmodifiableMap(namespaces);
    }
    
    /**
     * 获取所有别名映射
     */
    public Map<String, String> getAliases() {
        return Collections.unmodifiableMap(aliases);
    }
    
    /**
     * 检查是否可以访问指定的表
     */
    public boolean canAccess(String tableName) {
        String realName = aliases.getOrDefault(tableName, tableName);
        return namespaces.containsKey(realName) || 
               (parent != null && parent.canAccess(tableName));
    }
    
    /**
     * 获取所有可见的表名
     */
    public Set<String> getVisibleTableNames() {
        Set<String> result = new HashSet<>(namespaces.keySet());
        if (parent != null) {
            result.addAll(parent.getVisibleTableNames());
        }
        return result;
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        resolvedColumns.clear();
    }
    
    @Override
    public String toString() {
        return "SqlValidatorScope{" +
                "scopeType=" + scopeType +
                ", namespaces=" + namespaces.keySet() +
                ", aliases=" + aliases +
                '}';
    }
} 