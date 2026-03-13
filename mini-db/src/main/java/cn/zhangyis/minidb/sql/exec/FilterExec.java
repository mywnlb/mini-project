package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

/**
 * 过滤执行器：逐行判断条件
 */
public class FilterExec implements ExecNode {
    private final ExecNode input;
    private final SqlNode condition;

    public FilterExec(ExecNode input, SqlNode condition) {
        this.input = input;
        this.condition = condition;
    }

    @Override
    public void open() { input.open(); }

    @Override
    public Row next() {
        Row row;
        while ((row = input.next()) != null) {
            if (evaluate(condition, row)) {
                return row;
            }
        }
        return null;
    }

    @Override
    public void close() { input.close(); }

    static boolean evaluate(SqlNode condition, Row row) {
        if (condition instanceof SqlBinaryOp binOp) {
            return switch (binOp.kind()) {
                case AND -> evaluate(binOp.left(), row) && evaluate(binOp.right(), row);
                case OR -> evaluate(binOp.left(), row) || evaluate(binOp.right(), row);
                case BINARY_EQ -> compareValues(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row)) == 0;
                case BINARY_LT -> compareValues(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row)) < 0;
                case BINARY_GT -> compareValues(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row)) > 0;
                case BINARY_LE -> compareValues(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row)) <= 0;
                case BINARY_GE -> compareValues(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row)) >= 0;
                case BINARY_NE -> compareValues(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row)) != 0;
                case LIKE -> evaluateLike(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row));
                case NOT_LIKE -> !evaluateLike(resolveValue(binOp.left(), row), resolveValue(binOp.right(), row));
                case IS_NULL -> resolveValue(binOp.left(), row) == null;
                case IS_NOT_NULL -> resolveValue(binOp.left(), row) != null;
                case NOT_BETWEEN -> !evaluate(binOp.right(), row); // right 是 SqlBetween
                case NOT_IN -> !evaluate(binOp.right(), row); // right 是 SqlInList
                default -> false;
            };
        }
        if (condition instanceof SqlBetween between) {
            Object val = resolveValue(between.expr(), row);
            if (val == null) return false;
            Object low = resolveValue(between.low(), row);
            Object high = resolveValue(between.high(), row);
            return compareValues(val, low) >= 0 && compareValues(val, high) <= 0;
        }
        if (condition instanceof SqlInList inList) {
            Object val = resolveValue(inList.expr(), row);
            if (val == null) return false;
            for (SqlNode node : inList.values().nodes()) {
                Object item = resolveValue(node, row);
                if (compareValues(val, item) == 0) return true;
            }
            return false;
        }
        return false;
    }

    /**
     * SQL LIKE 匹配：% 匹配任意字符序列，_ 匹配单个字符
     */
    private static boolean evaluateLike(Object value, Object pattern) {
        if (value == null || pattern == null) return false;
        String str = String.valueOf(value);
        String pat = String.valueOf(pattern);
        // 将 SQL LIKE 模式转为 Java 正则
        String regex = pat
            .replace(".", "\\.")
            .replace("%", ".*")
            .replace("_", ".");
        return str.matches("(?i)" + regex);
    }

    static Object resolveValue(SqlNode node, Row row) {
        if (node instanceof SqlIdentifier id) {
            return row.get(id.name());
        }
        if (node instanceof SqlLiteral lit) {
            return switch (lit.type()) {
                case INT32 -> Integer.parseInt(lit.value());
                case DECIMAL -> Double.parseDouble(lit.value());
                default -> lit.value();
            };
        }
        // 算术表达式求值
        if (node instanceof SqlBinaryOp binOp) {
            SqlKind k = binOp.kind();
            if (k == SqlKind.ADD || k == SqlKind.SUB || k == SqlKind.MUL || k == SqlKind.DIV) {
                Object lv = resolveValue(binOp.left(), row);
                Object rv = resolveValue(binOp.right(), row);
                if (lv instanceof Number l && rv instanceof Number r) {
                    return switch (k) {
                        case ADD -> l.doubleValue() + r.doubleValue();
                        case SUB -> l.doubleValue() - r.doubleValue();
                        case MUL -> l.doubleValue() * r.doubleValue();
                        case DIV -> r.doubleValue() == 0 ? null : l.doubleValue() / r.doubleValue();
                        default -> null;
                    };
                }
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static int compareValues(Object left, Object right) {
        if (left == null || right == null) return left == right ? 0 : -1;
        // 数字比较
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue());
        }
        // 字符串比较（大小写不敏感）
        return String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
    }
}
