package cn.zhangyis.sql.planner.semantic.scope;



import cn.zhangyis.enums.FiledType;
import cn.zhangyis.sql.parser.DeleteStatement;
import cn.zhangyis.sql.parser.InsertStatement;
import cn.zhangyis.sql.parser.SelectStatement;
import cn.zhangyis.sql.parser.UpdateStatement;
import cn.zhangyis.sql.planner.semantic.SemanticException;
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
    private final List<SqlValidatorScope> children; // 子作用域列表
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
        this.children = new ArrayList<>();
        
        // 如果有父作用域，将自己添加为父作用域的子作用域
        if (parent != null) {
            parent.addChild(this);
        }
    }
    
    /**
     * 添加子作用域
     */
    public void addChild(SqlValidatorScope child) {
        if (!children.contains(child)) {
            children.add(child);
        }
    }
    
    /**
     * 获取所有子作用域
     */
    public List<SqlValidatorScope> getChildren() {
        return Collections.unmodifiableList(children);
    }
    
    /**
     * 获取指定类型的子作用域
     */
    public List<SqlValidatorScope> getChildrenOfType(ScopeType scopeType) {
        List<SqlValidatorScope> result = new ArrayList<>();
        for (SqlValidatorScope child : children) {
            if (child.getScopeType() == scopeType) {
                result.add(child);
            }
        }
        return result;
    }
    
    /**
     * 添加命名空间到作用域
     */
    public void addNamespace(String name, SqlValidatorNamespace namespace) {
        if(namespaces.containsKey(name)) {
            throw new SemanticException("Namespace '" + name + "' already exists in this scope");
        }

        namespaces.put(name, namespace);

        if(namespace != null && namespace.getName() != null) {
            // 如果命名空间有别名，添加到别名映射
            String alias = namespace.getName();
            if (!aliases.containsKey(alias)) {
                aliases.put(alias, name);
            }
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
     * 查找命名空间
     */
    public SqlValidatorNamespace findNamespace(String name) {
        // 处理别名
        String realName = aliases.getOrDefault(name, name);
        
        SqlValidatorNamespace namespace = namespaces.get(realName);
        if (namespace != null) {
            return namespace;
        }
        
        // 如果当前作用域找不到，查找父作用域
        if (parent != null) {
            return parent.findNamespace(name);
        }
        
        return null;
    }
    
    /**
     * 获取列的类型
     */
    public FiledType getColumnType(String tableName, String columnName) {
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
        SqlValidatorScope childScope = new SqlValidatorScope(this, scopeType);
        return childScope;
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
     * 递归查找语句对应的命名空间
     * 遍历整个作用域层次结构
     */
    public <T> SqlValidatorNamespace findNamespaceByStatement(T statement, Class<? extends SqlValidatorNamespace> namespaceClass) {
        // 在当前作用域中查找
        for (SqlValidatorNamespace namespace : namespaces.values()) {
            if (namespaceClass.isInstance(namespace)) {
                if (namespaceClass == SelectNamespace.class && statement instanceof SelectStatement) {
                    SelectNamespace selectNamespace = (SelectNamespace) namespace;
                    if (selectNamespace.getSelectStatement() == statement) {
                        return namespace;
                    }
                } else if (namespaceClass == SubqueryNamespace.class && statement instanceof SelectStatement) {
                    SubqueryNamespace subqueryNamespace = (SubqueryNamespace) namespace;
                    if (subqueryNamespace.getSubquery() == statement) {
                        return namespace;
                    }
                } else if (namespaceClass == TableNamespace.class) {
                    // 对于TableNamespace，我们需要检查语句类型
                    TableNamespace tableNamespace = (TableNamespace) namespace;
                    if (statement instanceof DeleteStatement) {
                        DeleteStatement delete = (DeleteStatement) statement;
                        if (delete.getTableName().equals(tableNamespace.getTable().getName())) {
                            return namespace;
                        }
                    } else if (statement instanceof UpdateStatement) {
                        UpdateStatement update = (UpdateStatement) statement;
                        if (update.getTableName().equals(tableNamespace.getTable().getName())) {
                            return namespace;
                        }
                    } else if (statement instanceof InsertStatement) {
                        InsertStatement insert = (InsertStatement) statement;
                        if (insert.getTableName().equals(tableNamespace.getTable().getName())) {
                            return namespace;
                        }
                    }
                }
                // 可以添加其他类型的命名空间检查
            }
        }
        
        // 在子作用域中递归查找
        for (SqlValidatorScope child : children) {
            SqlValidatorNamespace found = child.findNamespaceByStatement(statement, namespaceClass);
            if (found != null) {
                return found;
            }
        }
        
        return null;
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
                ", childrenCount=" + children.size() +
                '}';
    }
} 