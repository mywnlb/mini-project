package cn.zhangyis.sql.planner.logical;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合节点
 * 表示对输入进行分组聚合操作
 */
public class LogicalAggregate implements RelNode {
    private final List<Column> groupByColumns;
    private final List<Column> outputColumns;
    private final List<RelNode> inputs;
    private RelTraitSet traitSet;
    
    public LogicalAggregate(List<Column> groupByColumns, List<Column> outputColumns, RelNode input) {
        this.groupByColumns = new ArrayList<>(groupByColumns);
        this.outputColumns = new ArrayList<>(outputColumns);
        this.inputs = new ArrayList<>();
        this.inputs.add(input);
        this.traitSet = new RelTraitSet();
    }
    
    @Override
    public List<Column> getOutputColumns() {
        return outputColumns;
    }
    
    @Override
    public List<RelNode> getInputs() {
        return inputs;
    }
    
    @Override
    public void setInputs(List<RelNode> inputs) {
        this.inputs.clear();
        this.inputs.addAll(inputs);
    }
    
    @Override
    public RelNodeType getType() {
        return RelNodeType.AGGREGATE;
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
        return new LogicalAggregate(groupByColumns, outputColumns, inputs.get(0));
    }
    
    public List<Column> getGroupByColumns() {
        return groupByColumns;
    }
} 