package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.Column;
import cn.zhangyis.sql.expression.Expression;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接节点
 * 表示对两个输入进行连接操作
 */
public class LogicalJoin implements RelNode {
    private final JoinType joinType;
    private final Expression condition;
    private final List<RelNode> inputs;
    private RelTraitSet traitSet;
    
    public LogicalJoin(JoinType joinType, Expression condition, RelNode left, RelNode right) {
        this.joinType = joinType;
        this.condition = condition;
        this.inputs = new ArrayList<>();
        this.inputs.add(left);
        this.inputs.add(right);
        this.traitSet = new RelTraitSet();
    }
    
    @Override
    public List<Column> getOutputColumns() {
        List<Column> columns = new ArrayList<>();
        columns.addAll(inputs.get(0).getOutputColumns());
        columns.addAll(inputs.get(1).getOutputColumns());
        return columns;
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
        return RelNodeType.JOIN;
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
        return new LogicalJoin(joinType, condition, inputs.get(0), inputs.get(1));
    }
    
    public JoinType getJoinType() {
        return joinType;
    }
    
    public Expression getCondition() {
        return condition;
    }
    
    /**
     * 连接类型枚举
     */
    public enum JoinType {
        INNER,
        LEFT,
        RIGHT,
        FULL
    }
} 