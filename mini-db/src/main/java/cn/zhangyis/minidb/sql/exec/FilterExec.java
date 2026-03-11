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
                default -> false;
            };
        }
        return false;
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
