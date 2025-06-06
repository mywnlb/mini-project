package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.storage.catalog.Table;
import cn.zhangyis.storage.catalog.Column;

import java.util.List;
import java.util.Collections;

/**
 * 逻辑表扫描节点
 * 表示对表的全表扫描操作
 */
public class LogicalScan implements RelNode {
    private final Table table;
    
    public LogicalScan(Table table) {
        this.table = table;
    }
    
    @Override
    public List<RelNode> getInputs() {
        return Collections.emptyList(); // 叶子节点，没有输入
    }
    
    @Override
    public RelNode copy(List<RelNode> newInputs) {
        if (!newInputs.isEmpty()) {
            throw new IllegalArgumentException("LogicalScan should not have inputs");
        }
        return new LogicalScan(table);
    }
    
    @Override
    public List<Column> getOutputColumns() {
        return table.getColumns();
    }
    
    @Override
    public double estimateRowCount() {
        // 返回表的估计行数，这里返回一个默认值
        return 1000.0; // TODO: 从统计信息获取真实的行数估计
    }
    
    @Override
    public double estimateCost() {
        // 表扫描的成本通常与行数成正比
        return estimateRowCount();
    }
    
    @Override
    public void accept(RelNodeVisitor visitor) {
        visitor.visit(this);
    }
    
    /**
     * 获取扫描的表
     */
    public Table getTable() {
        return table;
    }
    
    /**
     * 获取表名
     */
    public String getTableName() {
        return table.getName();
    }
    
    @Override
    public String toString() {
        return "LogicalScan{table=" + table.getName() + "}";
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        LogicalScan that = (LogicalScan) obj;
        return table.equals(that.table);
    }
    
    @Override
    public int hashCode() {
        return table.hashCode();
    }
} 