package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.functions.FunctionRegistry;
import cn.zhangyis.minidb.sql.functions.ScalarFunction;
import cn.zhangyis.minidb.sql.types.SqlType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;

/**
 * 过滤执行器：逐行判断条件（SQL 三值逻辑）
 *
 * <p>SQL 三值逻辑规则：
 * <ul>
 *   <li>NULL 与任何值比较 → UNKNOWN</li>
 *   <li>TRUE AND UNKNOWN → UNKNOWN, FALSE AND UNKNOWN → FALSE</li>
 *   <li>TRUE OR UNKNOWN → TRUE, FALSE OR UNKNOWN → UNKNOWN</li>
 *   <li>NOT UNKNOWN → UNKNOWN</li>
 *   <li>只有 TRUE 才通过过滤，FALSE 和 UNKNOWN 都被过滤掉</li>
 * </ul>
 */
public class FilterExec implements ExecNode {
    private final ExecNode input;
    private final SqlNode condition;
    private final SubqueryEvaluator subqueryEvaluator;

    /**
     * 标量子查询求值器：将 SqlSubquery 执行并返回单行单列结果
     */
    @FunctionalInterface
    public interface SubqueryEvaluator {
        Object evaluate(SqlSubquery subquery);
    }

    /**
     * 通用子查询执行器：将 SqlSelect 编译为 ExecNode，供 EXISTS/IN 子查询使用
     * 支持相关子查询，通过 outerRow 传递外层行数据
     */
    @FunctionalInterface
    public interface SubqueryExecutor {
        ExecNode execute(SqlSelect select, Row outerRow);
    }

    /**
     * 相关子查询行合并执行器：将外层行的列合并到子查询的每一行中，
     * 使子查询的 WHERE 条件能够访问外层表的列（如 users.id）
     */
    static class CorrelatedRowExec implements ExecNode {
        private final ExecNode inner;
        private final Row outerRow;

        public CorrelatedRowExec(ExecNode inner, Row outerRow) {
            this.inner = inner;
            this.outerRow = outerRow;
        }

        @Override
        public void open() { inner.open(); }

        @Override
        public Row next() {
            Row r = inner.next();
            if (r == null) return null;
            return r.merge(outerRow);
        }

        @Override
        public void close() { inner.close(); }
    }

    private final SubqueryExecutor subqueryExecutor;

    public FilterExec(ExecNode input, SqlNode condition) {
        this(input, condition, null, null);
    }

    public FilterExec(ExecNode input, SqlNode condition, SubqueryEvaluator subqueryEvaluator) {
        this(input, condition, subqueryEvaluator, null);
    }

    public FilterExec(ExecNode input, SqlNode condition,
                       SubqueryEvaluator subqueryEvaluator, SubqueryExecutor subqueryExecutor) {
        this.input = input;
        this.condition = condition;
        this.subqueryEvaluator = subqueryEvaluator;
        this.subqueryExecutor = subqueryExecutor;
    }

    @Override
    public void open() { input.open(); }

    @Override
    public Row next() {
        Row row;
        while ((row = input.next()) != null) {
            if (evaluate(condition, row, subqueryEvaluator, subqueryExecutor)) {
                return row;
            }
        }
        return null;
    }

    @Override
    public void close() { input.close(); }

    /**
     * 外部入口：只有 TRUE 才通过（FALSE 和 UNKNOWN 都不通过）
     */
    static boolean evaluate(SqlNode condition, Row row) {
        return evaluate(condition, row, null);
    }

    static boolean evaluate(SqlNode condition, Row row, SubqueryEvaluator evaluator) {
        return evaluate(condition, row, evaluator, null);
    }

    static boolean evaluate(SqlNode condition, Row row, SubqueryEvaluator evaluator, SubqueryExecutor executor) {
        Boolean result = evaluate3VL(condition, row, evaluator, executor);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 三值逻辑求值：返回 Boolean.TRUE / Boolean.FALSE / null(UNKNOWN)
     */
    static Boolean evaluate3VL(SqlNode condition, Row row) {
        return evaluate3VL(condition, row, null);
    }

    static Boolean evaluate3VL(SqlNode condition, Row row, SubqueryEvaluator evaluator) {
        return evaluate3VL(condition, row, evaluator, null);
    }

    static Boolean evaluate3VL(SqlNode condition, Row row, SubqueryEvaluator evaluator, SubqueryExecutor executor) {
        if (condition instanceof SqlBinaryOp binOp) {
            return switch (binOp.kind()) {
                case AND -> and3VL(evaluate3VL(binOp.left(), row, evaluator, executor), evaluate3VL(binOp.right(), row, evaluator, executor));
                case OR -> or3VL(evaluate3VL(binOp.left(), row, evaluator, executor), evaluate3VL(binOp.right(), row, evaluator, executor));
                case BINARY_EQ -> nullSafeCompare(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator), c -> c == 0);
                case BINARY_LT -> nullSafeCompare(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator), c -> c < 0);
                case BINARY_GT -> nullSafeCompare(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator), c -> c > 0);
                case BINARY_LE -> nullSafeCompare(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator), c -> c <= 0);
                case BINARY_GE -> nullSafeCompare(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator), c -> c >= 0);
                case BINARY_NE -> nullSafeCompare(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator), c -> c != 0);
                case LIKE -> evaluateLike3VL(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator));
                case NOT_LIKE -> not3VL(evaluateLike3VL(resolveValue(binOp.left(), row, evaluator), resolveValue(binOp.right(), row, evaluator)));
                case IS_NULL -> resolveValue(binOp.left(), row, evaluator) == null;
                case IS_NOT_NULL -> resolveValue(binOp.left(), row, evaluator) != null;
                case NOT_BETWEEN -> not3VL(evaluate3VL(binOp.right(), row, evaluator, executor));
                case NOT_IN -> not3VL(evaluate3VL(binOp.right(), row, evaluator, executor));
                default -> Boolean.FALSE;
            };
        }
        if (condition instanceof SqlBetween between) {
            Object val = resolveValue(between.expr(), row, evaluator);
            if (val == null) return null; // UNKNOWN
            Object low = resolveValue(between.low(), row, evaluator);
            Object high = resolveValue(between.high(), row, evaluator);
            Boolean geLow = nullSafeCompare(val, low, c -> c >= 0);
            Boolean leHigh = nullSafeCompare(val, high, c -> c <= 0);
            return and3VL(geLow, leHigh);
        }
        if (condition instanceof SqlInList inList) {
            return evaluateIn3VL(inList, row, evaluator);
        }
        // EXISTS (SELECT ...)
        if (condition instanceof SqlExists exists) {
            if (executor == null) {
                throw new IllegalStateException("EXISTS subquery encountered but no SubqueryExecutor configured");
            }
            Boolean result = evaluateExists(exists.select(), row, executor);
            return exists.negated() ? not3VL(result) : result;
        }
        // expr IN (SELECT ...)
        if (condition instanceof SqlInSubquery inSub) {
            if (executor == null) {
                throw new IllegalStateException("IN subquery encountered but no SubqueryExecutor configured");
            }
            Boolean result = evaluateInSubquery(resolveValue(inSub.expr(), row, evaluator), inSub.select(), row, executor);
            return inSub.negated() ? not3VL(result) : result;
        }
        return Boolean.FALSE;
    }

    // ==================== 三值逻辑运算 ====================

    /**
     * SQL AND 三值逻辑：
     * TRUE AND TRUE = TRUE, FALSE AND x = FALSE, UNKNOWN AND TRUE = UNKNOWN
     */
    private static Boolean and3VL(Boolean left, Boolean right) {
        if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) return Boolean.FALSE;
        if (left == null || right == null) return null; // UNKNOWN
        return Boolean.TRUE;
    }

    /**
     * SQL OR 三值逻辑：
     * TRUE OR x = TRUE, FALSE OR FALSE = FALSE, UNKNOWN OR FALSE = UNKNOWN
     */
    private static Boolean or3VL(Boolean left, Boolean right) {
        if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) return Boolean.TRUE;
        if (left == null || right == null) return null; // UNKNOWN
        return Boolean.FALSE;
    }

    /**
     * SQL NOT 三值逻辑：NOT UNKNOWN = UNKNOWN
     */
    private static Boolean not3VL(Boolean value) {
        if (value == null) return null;
        return !value;
    }

    /**
     * NULL 安全比较：任一操作数为 null → UNKNOWN
     */
    private static Boolean nullSafeCompare(Object left, Object right, java.util.function.IntPredicate test) {
        if (left == null || right == null) return null; // UNKNOWN
        return test.test(compareValues(left, right));
    }

    /**
     * SQL IN 三值逻辑：
     * - val 为 null → UNKNOWN
     * - 找到匹配 → TRUE
     * - 列表中有 null 且无匹配 → UNKNOWN
     * - 无 null 且无匹配 → FALSE
     */
    private static Boolean evaluateIn3VL(SqlInList inList, Row row, SubqueryEvaluator evaluator) {
        Object val = resolveValue(inList.expr(), row, evaluator);
        if (val == null) return null; // UNKNOWN
        boolean hasNull = false;
        for (SqlNode node : inList.values().nodes()) {
            Object item = resolveValue(node, row, evaluator);
            if (item == null) {
                hasNull = true;
                continue;
            }
            if (compareValues(val, item) == 0) return Boolean.TRUE;
        }
        return hasNull ? null : Boolean.FALSE; // UNKNOWN if null in list, else FALSE
    }

    /**
     * EXISTS 子查询求值：支持相关子查询（通过合并 outerRow 使条件可见外层列）
     */
    private static Boolean evaluateExists(SqlSelect select, Row outerRow, SubqueryExecutor executor) {
        ExecNode exec = executor.execute(select, outerRow);
        exec.open();
        try {
            return exec.next() != null;
        } finally {
            exec.close();
        }
    }

    /**
     * IN 子查询三值逻辑：
     * - val 为 null → UNKNOWN
     * - 子查询结果中找到匹配 → TRUE
     * - 子查询结果中有 null 且无匹配 → UNKNOWN
     * - 无 null 且无匹配 → FALSE
     */
    private static Boolean evaluateInSubquery(Object val, SqlSelect select, Row outerRow, SubqueryExecutor executor) {
        if (val == null) return null; // UNKNOWN
        ExecNode exec = executor.execute(select, outerRow);
        exec.open();
        try {
            boolean hasNull = false;
            Row row;
            while ((row = exec.next()) != null) {
                Object item = row.columns().values().iterator().next();
                if (item == null) {
                    hasNull = true;
                    continue;
                }
                if (compareValues(val, item) == 0) return Boolean.TRUE;
            }
            return hasNull ? null : Boolean.FALSE;
        } finally {
            exec.close();
        }
    }

    /**
     * SQL LIKE 三值逻辑：任一操作数为 null → UNKNOWN
     */
    private static Boolean evaluateLike3VL(Object value, Object pattern) {
        if (value == null || pattern == null) return null; // UNKNOWN
        String str = String.valueOf(value);
        String pat = String.valueOf(pattern);
        String regex = pat
            .replace(".", "\\.")
            .replace("%", ".*")
            .replace("_", ".");
        return str.matches("(?i)" + regex);
    }

    static Object resolveValue(SqlNode node, Row row) {
        return resolveValue(node, row, null);
    }

    static Object resolveValue(SqlNode node, Row row, SubqueryEvaluator evaluator) {
        if (node == null || node.kind() == SqlKind.NULL_LITERAL) return null;
        if (node instanceof SqlSubquery subquery) {
            if (evaluator == null) {
                throw new IllegalStateException("Scalar subquery encountered but no SubqueryEvaluator configured");
            }
            return evaluator.evaluate(subquery);
        }
        if (node instanceof SqlAlias alias) {
            return resolveValue(alias.expression(), row, evaluator);
        }
        if (node instanceof SqlIdentifier id) {
            return row.get(id.name());
        }
        if (node instanceof SqlAggCall agg) {
            return row.get(agg.funcName() + "(" + agg.arg() + ")");
        }
        if (node instanceof SqlLiteral lit) {
            return switch (lit.type()) {
                case TINYINT -> Byte.parseByte(lit.value());
                case SMALLINT -> Short.parseShort(lit.value());
                case INT32 -> Integer.parseInt(lit.value());
                case BIGINT -> Long.parseLong(lit.value());
                case DECIMAL -> new BigDecimal(lit.value());
                case DATE -> LocalDate.parse(lit.value());
                case TIME -> LocalTime.parse(normalizeTimeText(lit.value()));
                case DATETIME -> LocalDateTime.parse(lit.value().replace(' ', 'T'));
                case BLOB -> hexToBytes(lit.value());
                default -> lit.value();
            };
        }
        // 算术表达式求值
        if (node instanceof SqlBinaryOp binOp) {
            SqlKind k = binOp.kind();
            if (k == SqlKind.ADD || k == SqlKind.SUB || k == SqlKind.MUL || k == SqlKind.DIV) {
                Object lv = resolveValue(binOp.left(), row, evaluator);
                Object rv = resolveValue(binOp.right(), row, evaluator);
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

        // 标量函数支持 (UPPER 等)
        if (node instanceof SqlFunctionCall call) {
            ScalarFunction func = FunctionRegistry.get(call.functionName());
            if (func != null) {
                List<Object> args = new ArrayList<>();
                for (SqlNode arg : call.arguments().nodes()) {
                    args.add(resolveValue(arg, row, evaluator));
                }
                return func.evaluate(null, args);  // row 参数暂不使用
            }
        }

        // CAST(expr AS type) 支持
        if (node instanceof SqlCast cast) {
            Object val = resolveValue(cast.expr(), row, evaluator);
            return castValue(val, cast.targetType());
        }

        // CASE WHEN 支持
        if (node instanceof SqlCase caseExpr) {
            for (SqlCase.WhenThen wt : caseExpr.whenThens()) {
                Boolean cond = evaluate3VL(wt.condition(), row, evaluator);
                if (Boolean.TRUE.equals(cond)) {
                    return resolveValue(wt.result(), row, evaluator);
                }
            }
            if (caseExpr.elseExpr() != null) {
                return resolveValue(caseExpr.elseExpr(), row, evaluator);
            }
            return null; // no match, no ELSE -> NULL
        }

        return null;
    }

    /**
     * CAST 类型转换
     */
    static Object castValue(Object val, SqlType targetType) {
        if (val == null) return null;
        return switch (targetType) {
            case TINYINT -> {
                if (val instanceof Number n) yield n.byteValue();
                yield Byte.parseByte(String.valueOf(val).trim());
            }
            case SMALLINT -> {
                if (val instanceof Number n) yield n.shortValue();
                yield Short.parseShort(String.valueOf(val).trim());
            }
            case INT32 -> {
                if (val instanceof Number n) yield n.intValue();
                yield Integer.parseInt(String.valueOf(val).trim());
            }
            case BIGINT -> {
                if (val instanceof Number n) yield n.longValue();
                yield Long.parseLong(String.valueOf(val).trim());
            }
            case DECIMAL -> {
                if (val instanceof BigDecimal bd) yield bd;
                if (val instanceof Number n) yield BigDecimal.valueOf(n.doubleValue());
                yield new BigDecimal(String.valueOf(val).trim());
            }
            case CHAR, VARCHAR, TEXT, JSON -> String.valueOf(val);
            case BLOB -> val;
            case DATE -> {
                if (val instanceof LocalDate d) yield d;
                yield LocalDate.parse(String.valueOf(val).trim());
            }
            case TIME -> {
                if (val instanceof LocalTime t) yield t;
                yield LocalTime.parse(normalizeTimeText(String.valueOf(val).trim()));
            }
            case DATETIME -> {
                if (val instanceof LocalDateTime dt) yield dt;
                yield LocalDateTime.parse(String.valueOf(val).trim().replace(' ', 'T'));
            }
        };
    }

    /**
     * 比较两个值。null 被视为"最大"（排序时排最后）。
     * 注意：过滤条件中的 null 比较应通过 nullSafeCompare 走三值逻辑，
     * 此方法主要供 ORDER BY / MAX / MIN / JOIN 等非过滤场景使用。
     */
    @SuppressWarnings("unchecked")
    static int compareValues(Object left, Object right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;  // null 排最后
        if (right == null) return -1;
        // 数字比较
        if (left instanceof Number l && right instanceof Number r) {
            if (left instanceof BigDecimal || right instanceof BigDecimal) {
                return toBigDecimal(left).compareTo(toBigDecimal(right));
            }
            return Double.compare(l.doubleValue(), r.doubleValue());
        }
        if (left instanceof Comparable<?> && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) left;
            return comparable.compareTo(right);
        }
        // 字符串比较（大小写不敏感）
        return String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }

    private static String normalizeTimeText(String value) {
        int dot = value.indexOf('.');
        return dot >= 0 ? value.substring(0, dot) : value;
    }
}
