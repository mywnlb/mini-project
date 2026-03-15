package cn.zhangyis.minidb.sql.types;

/**
 * 集中化的类型转换和校验工具类。
 *
 * <p>所有执行器通过此类进行值的类型转换和校验，
 * 而非各自实现转换逻辑。</p>
 */
public final class TypeCoercion {

    private TypeCoercion() {}

    /**
     * 将值强制转换为目标 SqlType 对应的 Java 类型。
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>NULL → null（任何类型都接受 NULL）</li>
     *   <li>INT32: String→Integer, Number→Integer</li>
     *   <li>BIGINT: String→Long, Number→Long</li>
     *   <li>DECIMAL: String→Double, Number→Double</li>
     *   <li>VARCHAR: 任意→String</li>
     *   <li>DATETIME: String→String（暂不做格式校验）</li>
     * </ul>
     *
     * @param value      原始值
     * @param targetType 目标列类型
     * @param columnName 列名（用于错误信息）
     * @return 转换后的值
     * @throws TypeMismatchException 如果转换失败
     */
    public static Object coerce(Object value, SqlType targetType, String columnName) {
        if (value == null) return null;

        return switch (targetType) {
            case INT32 -> coerceToInt(value, columnName);
            case BIGINT -> coerceToBigint(value, columnName);
            case DECIMAL -> coerceToDecimal(value, columnName);
            case VARCHAR -> coerceToVarchar(value);
            case DATETIME -> coerceToDatetime(value, columnName);
        };
    }

    /**
     * 检查值是否与目标类型兼容（不做转换）。
     */
    public static boolean isCompatible(Object value, SqlType targetType) {
        if (value == null) return true;
        try {
            coerce(value, targetType, "");
            return true;
        } catch (TypeMismatchException e) {
            return false;
        }
    }

    private static Object coerceToInt(Object value, String columnName) {
        if (value instanceof Integer) return value;
        if (value instanceof Long l) {
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l.intValue();
            throw new TypeMismatchException(columnName, SqlType.INT32, value);
        }
        if (value instanceof Number n) {
            // Double/Float → INT 不允许（精度丢失）
            double d = n.doubleValue();
            if (d != Math.floor(d)) throw new TypeMismatchException(columnName, SqlType.INT32, value);
            long l = n.longValue();
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            throw new TypeMismatchException(columnName, SqlType.INT32, value);
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new TypeMismatchException(columnName, SqlType.INT32, value);
            }
        }
        throw new TypeMismatchException(columnName, SqlType.INT32, value);
    }

    private static Object coerceToBigint(Object value, String columnName) {
        if (value instanceof Long) return value;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.floor(d)) throw new TypeMismatchException(columnName, SqlType.BIGINT, value);
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new TypeMismatchException(columnName, SqlType.BIGINT, value);
            }
        }
        throw new TypeMismatchException(columnName, SqlType.BIGINT, value);
    }

    private static Object coerceToDecimal(Object value, String columnName) {
        if (value instanceof Double) return value;
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new TypeMismatchException(columnName, SqlType.DECIMAL, value);
            }
        }
        throw new TypeMismatchException(columnName, SqlType.DECIMAL, value);
    }

    private static Object coerceToVarchar(Object value) {
        if (value instanceof String) return value;
        return String.valueOf(value);
    }

    private static Object coerceToDatetime(Object value, String columnName) {
        if (value instanceof String) return value;
        throw new TypeMismatchException(columnName, SqlType.DATETIME, value);
    }
}
