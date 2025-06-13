package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.*;
import cn.zhangyis.sql.parser.expression.ColumnExpression;
import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL语句重写器
 * 
 * 专门负责SQL语句的无条件重写 (Unconditional Rewrites)
 * 严格遵循 Apache Calcite 的 SqlValidatorImpl.performUnconditionalRewrites() 方法
 * 
 * 职责（严格按照Calcite标准）：
 * 1. UPDATE语句转SELECT语句
 * 2. DELETE语句转SELECT语句  
 * 3. MERGE语句处理（如果支持）
 * 4. 语句级别的结构重写
 * 
 * 注意：表达式级别的重写（常量折叠、函数重写等）在Calcite中
 * 通常在后续的RexSimplify和优化规则阶段进行，不在无条件重写阶段
 * 
 * 设计原则：
 * - 单一职责：专注于语句级重写，不涉及表达式重写
 * - Calcite兼容：严格遵循Calcite的performUnconditionalRewrites范围
 * - 充分利用现有AST基础设施
 * - 线程安全设计
 */
public class SqlRewriter {
    
    private final CatalogManager catalogManager;
    private final RewriteConfiguration config;
    
    /**
     * 构造函数
     */
    public SqlRewriter(CatalogManager catalogManager) {
        this(catalogManager, RewriteConfiguration.createDefault());
    }
    
    /**
     * 构造函数（带配置）
     */
    public SqlRewriter(CatalogManager catalogManager, RewriteConfiguration config) {
        this.catalogManager = catalogManager;
        this.config = config;
    }
    
    /**
     * 执行SQL语句重写
     * 
     * 严格按照Apache Calcite的performUnconditionalRewrites范围：
     * - UPDATE转SELECT
     * - DELETE转SELECT
     * - 语句级结构调整
     * 
     * 不包括表达式级重写（这些在后续阶段处理）
     * 
     * @param statement 原始SQL语句AST
     * @return 重写后的SQL语句AST
     * @throws SemanticException 如果重写过程中发生错误
     */
    public SQLStatement rewrite(SQLStatement statement) throws SemanticException {
        if (statement == null) {
            throw new SemanticException("Statement cannot be null");
        }
        
        // 创建重写上下文
        RewriteContext context = new RewriteContext(config);
        
        try {
            // 根据语句类型分发重写逻辑
            // 严格按照Calcite的做法，只处理语句级重写
            switch (statement.getType()) {
                case UPDATE:
                    return rewriteUpdateStatement((UpdateStatement) statement, context);
                case DELETE:
                    return rewriteDeleteStatement((DeleteStatement) statement, context);
                case SELECT:
                    return rewriteSelectStatement((SelectStatement) statement, context);
                case INSERT:
                    return rewriteInsertStatement((InsertStatement) statement, context);
                default:
                    // 其他类型的语句暂时不需要重写
                    return statement;
            }
        } catch (Exception e) {
            throw new SemanticException("SQL rewrite failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 重写 SELECT 语句
     * 
     * 在Apache Calcite的performUnconditionalRewrites中，
     * SELECT语句通常不需要语句级重写，只是递归处理子查询
     */
    private SelectStatement rewriteSelectStatement(SelectStatement select, RewriteContext context) {
        // 按照Calcite标准，只处理子查询的语句级重写
        if (select.getFrom() != null && !select.getFrom().isEmpty()) {
            List<SelectStatement.TableReference> rewrittenFroms = new ArrayList<>();
            for (SelectStatement.TableReference tableRef : select.getFrom()) {
                rewrittenFroms.add(rewriteTableReference(tableRef, context));
            }
            select.setFromTables(rewrittenFroms);
        }
        
        // 处理JOIN子句中的子查询
        if (select.getJoins() != null && !select.getJoins().isEmpty()) {
            List<SelectStatement.JoinClause> rewrittenJoins = new ArrayList<>();
            for (SelectStatement.JoinClause join : select.getJoins()) {
                SelectStatement.TableReference rewrittenTable = rewriteTableReference(join.getJoinTable(), context);
                rewrittenJoins.add(new SelectStatement.JoinClause(join.getJoinType(), rewrittenTable, join.getJoinCondition()));
            }
            select.setJoins(rewrittenJoins);
        }
        
        return select;
    }
    
    /**
     * 重写表引用
     * 只处理子查询的递归重写（语句级）
     */
    private SelectStatement.TableReference rewriteTableReference(SelectStatement.TableReference tableRef, RewriteContext context) {
        if (tableRef.isSubquery()) {
            // 递归重写子查询（语句级重写）
            SelectStatement rewrittenSubquery = rewriteSelectStatement(tableRef.getSubquery(), context.createChildContext());
            return new SelectStatement.TableReference(rewrittenSubquery, tableRef.getAlias());
        }
        // 普通表引用不需要重写
        return tableRef;
    }
    
    /**
     * 重写 UPDATE 语句
     * 
     * 严格按照Apache Calcite的做法：
     * 1. 不进行表达式级重写
     * 2. 创建源SELECT语句用于后续验证
     */
    private UpdateStatement rewriteUpdateStatement(UpdateStatement update, RewriteContext context) {
        // 按照Calcite标准，UPDATE的无条件重写主要是创建源SELECT语句
        // 不在这个阶段进行表达式重写
        
        // 创建源SELECT语句（Apache Calcite风格）
        if (context.isUpdateToSelectRewriteEnabled()) {
            performRewriteUpdateToSelect(update, context);
        }
        
        return update;
    }
    
    /**
     * 重写 DELETE 语句  
     * 
     * 严格按照Apache Calcite的做法：
     * 1. 不进行表达式级重写
     * 2. 创建源SELECT语句用于后续验证
     */
    private DeleteStatement rewriteDeleteStatement(DeleteStatement delete, RewriteContext context) {
        // 按照Calcite标准，DELETE的无条件重写主要是创建源SELECT语句
        // 不在这个阶段进行表达式重写
        
        // 创建源SELECT语句（Apache Calcite风格）
        if (context.isDeleteToSelectRewriteEnabled()) {
            performRewriteDeleteToSelect(delete, context);
        }
        
        return delete;
    }
    
    /**
     * 重写 INSERT 语句
     * 
     * 在Apache Calcite中，INSERT语句的无条件重写主要处理：
     * 1. INSERT ... SELECT中的子查询重写
     * 2. 语句结构调整，不涉及表达式重写
     */
    private InsertStatement rewriteInsertStatement(InsertStatement insert, RewriteContext context) {
        // 只处理INSERT ... SELECT中的子查询重写（语句级）
        if (insert.getSelectStatement() != null) {
            SelectStatement rewrittenSelect = rewriteSelectStatement(insert.getSelectStatement(), context);
            insert.setSelectStatement(rewrittenSelect);
        }
        
        // 注意：不在这里进行VALUES表达式的重写
        // 表达式级重写在后续的语义分析阶段进行
        
        return insert;
    }
    
    /**
     * 处理 UPDATE 语句转换为 SELECT 语句的重写
     * 
     * 参考 Apache Calcite 的 SqlValidatorImpl.performUnconditionalRewrites() 中的逻辑：
     * ```java
     * SqlSelect select = createSourceSelectForUpdate(call);
     * call.setSourceSelect(select);
     * ```
     */
    private void performRewriteUpdateToSelect(UpdateStatement update, RewriteContext context) {
        SelectStatement sourceSelect = createSourceSelectForUpdate(update, context);
        update.setSelectStatement(sourceSelect);
        
        // 记录重写信息到上下文（用于调试和追踪）
        if (context.isPreserveOriginalStatementInfo()) {
            context.setProperty("update_to_select_rewrite", true);
            context.setProperty("original_update_table", update.getTableName());
            context.setProperty("update_assignments_count", update.getAssignments().size());
        }
    }
    
    /**
     * 处理 DELETE 语句转换为 SELECT 语句的重写
     * 
     * 参考 Apache Calcite 的 SqlValidatorImpl.performUnconditionalRewrites() 中的逻辑：
     * ```java
     * SqlSelect select = createSourceSelectForDelete(call);
     * call.setSourceSelect(select);
     * ```
     */
    private void performRewriteDeleteToSelect(DeleteStatement delete, RewriteContext context) {
        SelectStatement sourceSelect = createSourceSelectForDelete(delete, context);
        delete.setSelectStatement(sourceSelect);
        
        // 记录重写信息到上下文（用于调试和追踪）
        if (context.isPreserveOriginalStatementInfo()) {
            context.setProperty("delete_to_select_rewrite", true);
            context.setProperty("original_delete_table", delete.getTableName());
        }
    }
    
    /**
     * 为 UPDATE 语句创建源 SELECT 语句
     * 
     * 此方法将 UPDATE 操作转换为等价的 SELECT 操作，使得后续的语义分析可以
     * 统一处理查询逻辑，这是 Apache Calcite 的标准做法。
     * 
     * 例如：UPDATE users SET name = 'John', age = 25 WHERE id = 1
     * 转换为：SELECT 'John' as name, 25 as age FROM users WHERE id = 1
     * 
     * 注意：按照Calcite标准，这里不进行表达式重写，保持原始表达式
     */
    private SelectStatement createSourceSelectForUpdate(UpdateStatement update, RewriteContext context) {
        String tableName = update.getTableName();
        Expression whereCondition = update.getWhereCondition();
        List<UpdateStatement.Assignment> assignments = update.getAssignments();
        
        // 构建 SELECT 项：包含更新的列和它们的新值
        List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
        
        // 1. 添加被更新的列作为SELECT项，使用新的值表达式
        for (UpdateStatement.Assignment assignment : assignments) {
            String columnName = assignment.getColumnName();
            Expression newValue = assignment.getValueExpression();
            
            // 按照Calcite标准，不在这里重写表达式，保持原样
            selectItems.add(new SelectStatement.SelectItem(newValue, columnName));
        }
        
        // 2. 如果配置要求，添加表的所有其他列
        if (context.isIncludeAllColumnsInUpdateSelect()) {
            // 可以通过catalogManager获取表的所有列信息
            try {
                Table table = catalogManager.getTable(tableName);
                if (table != null) {
                    for (Column column : table.getColumns()) {
                        String colName = column.getName();
                        
                        // 检查是否已经在UPDATE的SET列表中
                        boolean isUpdatedColumn = assignments.stream()
                            .anyMatch(assignment -> assignment.getColumnName().equals(colName));
                        
                        if (!isUpdatedColumn) {
                            // 添加原始列值
                            ColumnExpression colExpr = new ColumnExpression(tableName, colName);
                            selectItems.add(new SelectStatement.SelectItem(colExpr, colName));
                        }
                    }
                }
            } catch (Exception e) {
                // 如果无法获取表信息，忽略错误，只包含UPDATE的列
                context.setProperty("table_metadata_error", e.getMessage());
            }
        }
        
        // 3. 构建FROM子句
        List<SelectStatement.TableReference> fromTables = new ArrayList<>();
        fromTables.add(new SelectStatement.TableReference(tableName, null));
        
        // 4. 按照Calcite标准，WHERE条件保持原样，不进行重写
        
        // 5. 创建SELECT语句
        return new SelectStatement(
            selectItems,           // SELECT items
            fromTables,           // FROM tables  
            null,                 // JOINs
            whereCondition,       // WHERE (保持原样)
            null,                 // GROUP BY
            null,                 // HAVING
            null,                 // ORDER BY
            null,                 // LIMIT
            null,                 // LIMIT
            false                 // DISTINCT
        );
    }
    
    /**
     * 为 DELETE 语句创建源 SELECT 语句
     * 
     * 此方法将 DELETE 操作转换为等价的 SELECT 操作，便于统一的语义分析。
     * 这完全遵循Apache Calcite的做法。
     * 
     * 例如：DELETE FROM users WHERE age > 65
     * 转换为：SELECT * FROM users WHERE age > 65
     * 
     * 注意：按照Calcite标准，这里不进行表达式重写，保持原始表达式
     */
    private SelectStatement createSourceSelectForDelete(DeleteStatement delete, RewriteContext context) {
        String tableName = delete.getTableName();
        Expression whereCondition = delete.getWhereCondition();
        
        // 构建 SELECT 项：DELETE通常选择所有列
        List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
        
        // 创建 SELECT * 
        Expression starExpression = new ColumnExpression(null, true); // 星号表达式
        selectItems.add(new SelectStatement.SelectItem(starExpression, null));
        
        // 构建FROM子句
        List<SelectStatement.TableReference> fromTables = new ArrayList<>();
        fromTables.add(new SelectStatement.TableReference(tableName, null));
        
        // 按照Calcite标准，WHERE条件保持原样，不进行重写
        
        // 创建SELECT语句
        return new SelectStatement(
            selectItems,           // SELECT items (*)
            fromTables,           // FROM tables
            null,                 // JOINs  
            whereCondition,       // WHERE (保持原样)
            null,                 // GROUP BY
            null,                 // HAVING
            null,                 // ORDER BY
            null,                 // LIMIT
            null,                 // offset
            false                 // DISTINCT
        );
    }
    
    /**
     * 重写配置类
     * 
     * 严格按照Apache Calcite的performUnconditionalRewrites范围进行配置
     */
    public static class RewriteConfiguration {
        // Apache Calcite标准的无条件重写配置
        private boolean enableUpdateToSelectRewrite = true;
        private boolean enableDeleteToSelectRewrite = true;
        private boolean includeAllColumnsInUpdateSelect = false;
        private boolean preserveOriginalStatementInfo = true;
        
        /**
         * 创建默认配置
         */
        public static RewriteConfiguration createDefault() {
            return new RewriteConfiguration();
        }
        
        /**
         * 创建保守配置（最小重写）
         */
        public static RewriteConfiguration createConservative() {
            RewriteConfiguration config = new RewriteConfiguration();
            config.enableUpdateToSelectRewrite = false;
            config.enableDeleteToSelectRewrite = false;
            config.preserveOriginalStatementInfo = false;
            return config;
        }
        
        /**
         * 创建Apache Calcite兼容配置
         */
        public static RewriteConfiguration createCalciteCompatible() {
            RewriteConfiguration config = new RewriteConfiguration();
            config.enableUpdateToSelectRewrite = true;
            config.enableDeleteToSelectRewrite = true;
            config.preserveOriginalStatementInfo = true;
            return config;
        }
        
        // Getters and Setters（只保留语句级重写相关的配置）
        public boolean isUpdateToSelectRewriteEnabled() { return enableUpdateToSelectRewrite; }
        public void setUpdateToSelectRewriteEnabled(boolean enabled) { this.enableUpdateToSelectRewrite = enabled; }
        
        public boolean isDeleteToSelectRewriteEnabled() { return enableDeleteToSelectRewrite; }
        public void setDeleteToSelectRewriteEnabled(boolean enabled) { this.enableDeleteToSelectRewrite = enabled; }
        
        public boolean isIncludeAllColumnsInUpdateSelect() { return includeAllColumnsInUpdateSelect; }
        public void setIncludeAllColumnsInUpdateSelect(boolean enabled) { this.includeAllColumnsInUpdateSelect = enabled; }
        
        public boolean isPreserveOriginalStatementInfo() { return preserveOriginalStatementInfo; }
        public void setPreserveOriginalStatementInfo(boolean enabled) { this.preserveOriginalStatementInfo = enabled; }
    }
    
    /**
     * 重写上下文类
     * 
     * 简化为只支持语句级重写的上下文
     */
    private static class RewriteContext {
        private final RewriteConfiguration config;
        private final Map<String, Object> properties = new HashMap<>();
        private int aliasCounter = 0;
        
        public RewriteContext(RewriteConfiguration config) {
            this.config = config;
        }
        
        // 配置代理方法（只保留语句级重写相关的）
        public boolean isUpdateToSelectRewriteEnabled() { return config.isUpdateToSelectRewriteEnabled(); }
        public boolean isDeleteToSelectRewriteEnabled() { return config.isDeleteToSelectRewriteEnabled(); }
        public boolean isIncludeAllColumnsInUpdateSelect() { return config.isIncludeAllColumnsInUpdateSelect(); }
        public boolean isPreserveOriginalStatementInfo() { return config.isPreserveOriginalStatementInfo(); }
        
        /**
         * 生成唯一的别名
         */
        public String generateAlias(String prefix) {
            return prefix + "_" + (++aliasCounter);
        }
        
        /**
         * 设置属性
         */
        public void setProperty(String key, Object value) {
            properties.put(key, value);
        }
        
        /**
         * 获取属性
         */
        @SuppressWarnings("unchecked")
        public <T> T getProperty(String key, Class<T> type) {
            return (T) properties.get(key);
        }
        
        /**
         * 获取属性（带默认值）
         */
        @SuppressWarnings("unchecked")
        public <T> T getProperty(String key, Class<T> type, T defaultValue) {
            Object value = properties.get(key);
            return value != null ? (T) value : defaultValue;
        }
        
        /**
         * 创建子上下文
         */
        public RewriteContext createChildContext() {
            RewriteContext child = new RewriteContext(this.config);
            child.aliasCounter = this.aliasCounter; // 共享别名计数器
            return child;
        }
        
        /**
         * 获取重写统计信息
         */
        public Map<String, Object> getRewriteStatistics() {
            Map<String, Object> stats = new HashMap<>();
            stats.put("total_properties", properties.size());
            stats.put("alias_counter", aliasCounter);
            
            // 统计各种重写操作
            stats.put("update_to_select_rewrites", 
                properties.containsKey("update_to_select_rewrite") ? 1 : 0);
            stats.put("delete_to_select_rewrites", 
                properties.containsKey("delete_to_select_rewrite") ? 1 : 0);
            
            return stats;
        }
        
        /**
         * 检查是否执行了Apache Calcite风格的重写
         */
        public boolean hasCalciteStyleRewrites() {
            return properties.containsKey("update_to_select_rewrite") || 
                   properties.containsKey("delete_to_select_rewrite");
        }
    }
} 