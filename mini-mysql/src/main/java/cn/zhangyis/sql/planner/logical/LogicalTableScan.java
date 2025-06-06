package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.Column;
import java.util.ArrayList;
import java.util.List;

/**
 * 表扫描节点
 * 表示对表的扫描操作
 */
public class LogicalTableScan implements RelNode {
    private final String tableName;
    private final List<Column> outputColumns;
    private RelTraitSet traitSet;
    
    public LogicalTableScan(String tableName, List<Column> outputColumns) {
        this.tableName = tableName;
        this.outputColumns = new ArrayList<>(outputColumns);
        this.traitSet = new RelTraitSet();
    }
    
    @Override
    public List<Column> getOutputColumns() {
        return outputColumns;
    }
    
    @Override
    public List<RelNode> getInputs() {
        return new ArrayList<>();
    }
    
    @Override
    public void setInputs(List<RelNode> inputs) {
        // 表扫描节点没有输入
    }
    
    @Override
    public RelNodeType getType() {
        return RelNodeType.TABLE_SCAN;
    }
    
    @Override
    public RelTraitSet getTraitSet() {
        return traitSet;
    }
    
    @Override
    public void setTraitSet(RelTraitSet traitSet) {
        this.traitSet = traitSet;
    }
    
    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new LogicalTableScan(tableName, outputColumns);
    }
    
    public String getTableName() {
        return tableName;
    }
} 