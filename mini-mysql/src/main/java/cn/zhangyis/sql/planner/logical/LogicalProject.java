package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.Expression;

import java.util.List;

/**
 * 逻辑投影节点
 * 表示SELECT子句的列投影操作
 */
public class LogicalProject extends AbstractRelNode {
    private final List<Expression> projectExpressions;
    private final List<String> projectNames;

    public LogicalProject(List<Expression> projectExpressions, List<String> projectNames, RelNode input) {
        super();
        this.projectExpressions = projectExpressions;
        this.projectNames = projectNames;
        addInput(input);
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.PROJECT;
    }

    @Override
    protected void deriveOutput() {
        // 投影操作完全替换输出结构
        outputExpressions.clear();
        outputNames.clear();

        outputExpressions.addAll(projectExpressions);
        outputNames.addAll(projectNames);
    }

    /**
     * 获取投影表达式列表
     */
    public List<Expression> getProjectExpressions() {
        return projectExpressions;
    }

    /**
     * 获取投影列名列表
     */
    public List<String> getProjectNames() {
        return projectNames;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LogicalProject(");

        for (int i = 0; i < projectExpressions.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(projectNames.get(i)).append("=").append(projectExpressions.get(i));
        }

        sb.append(")");
        return sb.toString();
    }
}