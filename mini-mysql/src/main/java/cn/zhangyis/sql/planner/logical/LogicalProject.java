package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.Column;
import java.util.ArrayList;
import java.util.List;

/**
 * 投影节点
 * 表示对输入进行列投影操作
 */
public class LogicalProject implements RelNode {
    private final List<Column> outputColumns;
    private final List<RelNode> inputs;
    private RelTraitSet traitSet;
    
    public LogicalProject(List<Column> outputColumns, RelNode input) {
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
        return RelNodeType.PROJECT;
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
        return new LogicalProject(outputColumns, inputs.get(0));
    }
} 