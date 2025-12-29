package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.Expression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 逻辑排序节点
 * 表示ORDER BY, LIMIT, OFFSET操作
 */
public class LogicalSort extends AbstractRelNode {
    private final List<Expression> sortExpressions;
    private final List<Boolean> ascendingFlags; // true表示升序，false表示降序
    private final Integer limit;
    private final Integer offset;

    /**
     * 创建完整排序节点（带ORDER BY, LIMIT和OFFSET）
     */
    public LogicalSort(RelNode input, List<Expression> sortExpressions, List<Boolean> ascendingFlags, Integer limit,
            Integer offset) {
        super();
        this.sortExpressions = new ArrayList<>(sortExpressions != null ? sortExpressions : Collections.emptyList());
        this.ascendingFlags = new ArrayList<>(ascendingFlags != null ? ascendingFlags : Collections.emptyList());
        this.limit = limit;
        this.offset = offset;
        addInput(input);
        deriveOutput();
    }

    /**
     * 创建只有ORDER BY的排序节点
     */
    public LogicalSort(RelNode input, List<Expression> sortExpressions, List<Boolean> ascendingFlags) {
        this(input, sortExpressions, ascendingFlags, -1, 0);
    }

    /**
     * 创建只有LIMIT的排序节点
     */
    public LogicalSort(RelNode input, Integer limit, Integer offset) {
        this(input, Collections.emptyList(), Collections.emptyList(), limit, offset);
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.SORT;
    }

    @Override
    protected void deriveOutput() {
        // 排序不改变输出结构
        RelNode input = inputs.get(0);
        outputExpressions.addAll(input.getOutputExpressions());
        outputNames.addAll(input.getOutputNames());
    }

    /**
     * 获取排序表达式
     */
    public List<Expression> getSortExpressions() {
        return Collections.unmodifiableList(sortExpressions);
    }

    /**
     * 获取排序方向标志
     */
    public List<Boolean> getAscendingFlags() {
        return Collections.unmodifiableList(ascendingFlags);
    }

    /**
     * 获取LIMIT值
     *
     * @return LIMIT值，-1表示无限制
     */
    public Integer getLimit() {
        return limit;
    }

    /**
     * 获取OFFSET值
     */
    public Integer getOffset() {
        return offset;
    }

    /**
     * 是否有排序条件
     */
    public boolean hasSort() {
        return !sortExpressions.isEmpty();
    }

    /**
     * 是否有LIMIT
     */
    public boolean hasLimit() {
        return Objects.nonNull(limit) && limit >= 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LogicalSort(");

        if (hasSort()) {
            sb.append("sort=[");
            for (int i = 0; i < sortExpressions.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(sortExpressions.get(i));
                sb.append(" ");
                sb.append(ascendingFlags.get(i) ? "ASC" : "DESC");
            }
            sb.append("]");
        }

        if (hasLimit()) {
            if (hasSort()) {
                sb.append(", ");
            }
            sb.append("limit=").append(limit);

            if (Objects.nonNull(offset) && offset > 0) {
                sb.append(", offset=").append(offset);
            }
        }

        sb.append(")");
        return sb.toString();
    }
}