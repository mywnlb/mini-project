package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.types.SqlType;

import java.util.List;

/**
 * 常量折叠规则（Constant Folding）
 *
 * 功能：
 * - WHERE 1 = 1 → 消除 Filter 节点
 * - WHERE 1 = 0 → 返回空结果集（RelEmpty）
 * - 基础的 AND 常量传播优化
 *
 * 这是一个独立的优化规则。
 */
public class ConstantFoldingRule extends RelOptRule {

    public static final ConstantFoldingRule INSTANCE = new ConstantFoldingRule();

    @Override
    public boolean matches(RelNode node) {
        return node instanceof RelFilter;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelFilter filter = (RelFilter) node;
        SqlNode optimizedCondition = foldConstants(filter.condition());

        if (optimizedCondition == null || isAlwaysTrue(optimizedCondition)) {
            // 常量折叠后条件恒为 TRUE，消除 Filter
            return filter.input();
        }

        if (isAlwaysFalse(optimizedCondition)) {
            // 常量折叠后条件恒为 FALSE，返回空结果集
            return new RelEmpty();
        }

        if (optimizedCondition != filter.condition()) {
            return new RelFilter(filter.input(), optimizedCondition);
        }

        return node; // 无变化
    }

    /**
     * 对条件进行常量折叠
     */
    private SqlNode foldConstants(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp) {
            SqlNode left = foldConstants(binOp.left());
            SqlNode right = foldConstants(binOp.right());

            // 如果左右子节点都是字面量，进行计算
            if (left instanceof SqlLiteral leftLit && right instanceof SqlLiteral rightLit) {
                return evaluateBinaryOp(binOp.kind(), leftLit, rightLit);
            }

            // AND 常量传播
            if (binOp.kind() == SqlKind.AND) {
                if (isAlwaysTrue(left)) return right;
                if (isAlwaysTrue(right)) return left;
                if (isAlwaysFalse(left) || isAlwaysFalse(right)) {
                    return new SqlLiteral("0", SqlType.INT32);
                }
            }

            if (left != binOp.left() || right != binOp.right()) {
                return new SqlBinaryOp(binOp.kind(), left, right);
            }
        }

        return condition;
    }

    /**
     * 计算二元字面量操作
     */
    private SqlNode evaluateBinaryOp(SqlKind op, SqlLiteral left, SqlLiteral right) {
        try {
            String leftVal = left.value();
            String rightVal = right.value();

            // 处理布尔常量
            if ("1".equals(leftVal) && "1".equals(rightVal)) {
                boolean result = switch (op) {
                    case BINARY_EQ -> true;
                    case BINARY_NE -> false;
                    default -> false;
                };
                return new SqlLiteral(result ? "1" : "0", SqlType.INT32);
            }

            int l = Integer.parseInt(leftVal);
            int r = Integer.parseInt(rightVal);

            boolean result = switch (op) {
                case BINARY_EQ -> l == r;
                case BINARY_NE -> l != r;
                case BINARY_LT -> l < r;
                case BINARY_LE -> l <= r;
                case BINARY_GT -> l > r;
                case BINARY_GE -> l >= r;
                default -> false;
            };

            return new SqlLiteral(result ? "1" : "0", SqlType.INT32);
        } catch (Exception e) {
            return new SqlBinaryOp(op, left, right);
        }
    }

    private boolean isAlwaysTrue(SqlNode node) {
        if (node instanceof SqlLiteral lit) {
            String v = lit.value();
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        }
        return false;
    }

    private boolean isAlwaysFalse(SqlNode node) {
        if (node instanceof SqlLiteral lit) {
            String v = lit.value();
            return "0".equals(v) || "false".equalsIgnoreCase(v);
        }
        return false;
    }
}

/**
 * 空结果集节点
 */
class RelEmpty extends RelNode {
    @Override
    public RelNode copy(List<RelNode> inputs) {
        return this;
    }

    @Override
    public String explain() {
        return "RelEmpty()";
    }
}
