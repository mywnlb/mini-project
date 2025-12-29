package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.Expression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 关系代数节点抽象基类
 * 提供RelNode接口的默认实现和通用功能
 */
public abstract class AbstractRelNode implements RelNode {
    protected List<RelNode> inputs;
    protected List<Expression> outputExpressions;
    protected List<String> outputNames;
    protected String alias;

    public AbstractRelNode() {
        this.inputs = new ArrayList<>();
        this.outputExpressions = new ArrayList<>();
        this.outputNames = new ArrayList<>();
    }

    @Override
    public List<RelNode> getInputs() {
        return Collections.unmodifiableList(inputs);
    }

    @Override
    public List<Expression> getOutputExpressions() {
        return Collections.unmodifiableList(outputExpressions);
    }

    @Override
    public List<String> getOutputNames() {
        return Collections.unmodifiableList(outputNames);
    }

    @Override
    public void setAlias(String alias) {
        this.alias = alias;
    }

    @Override
    public String getAlias() {
        return alias;
    }

    /**
     * 添加输入节点
     */
    protected void addInput(RelNode input) {
        if (input != null) {
            inputs.add(input);
        }
    }

    /**
     * 派生输出表达式和名称
     * 子类应该覆盖此方法以提供特定的输出派生逻辑
     */
    protected abstract void deriveOutput();

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getType());
        if (alias != null) {
            sb.append(" AS ").append(alias);
        }
        if (!inputs.isEmpty()) {
            sb.append(", inputs=[");
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(inputs.get(i).getType());
                if (inputs.get(i).getAlias() != null) {
                    sb.append(" AS ").append(inputs.get(i).getAlias());
                }
            }
            sb.append("]");
        }
        return sb.toString();
    }
}