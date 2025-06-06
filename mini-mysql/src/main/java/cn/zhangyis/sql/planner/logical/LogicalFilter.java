package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.Column;
import cn.zhangyis.sql.expression.Expression;
import java.util.ArrayList;
import java.util.List;

/**
 * 过滤节点
 * 表示对输入进行条件过滤操作
 */
public class LogicalFilter implements RelNode {
    private final Expression condition;
    private final List<RelNode> inputs;
    private RelTraitSet traitSet;
    
    public LogicalFilter(Expression condition, RelNode input) {
        this.condition = condition;
        this.inputs = new ArrayList<>();
        this.inputs.add(input);
        this.traitSet = new RelTraitSet();
    }
    
    @Override
    public List<Column> getOutputColumns() {
        return inputs.get(0).getOutputColumns();
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
        return RelNodeType.FILTER;
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
        return new LogicalFilter(condition, inputs.get(0));
    }
    
    public Expression getCondition() {
        return condition;
    }
} 