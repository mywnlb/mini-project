package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.types.SqlType;
import cn.zhangyis.minidb.sql.types.TypeCoercion;
import cn.zhangyis.minidb.sql.types.TypeMismatchException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TypeCoercion 类型转换和校验测试
 */
class TypeCoercionTest {

    // ========== INT32 ==========

    @Test
    void intPassThrough() {
        assertEquals(42, TypeCoercion.coerce(42, SqlType.INT32, "id"));
    }

    @Test
    void longToInt() {
        assertEquals(100, TypeCoercion.coerce(100L, SqlType.INT32, "id"));
    }

    @Test
    void stringToInt() {
        assertEquals(123, TypeCoercion.coerce("123", SqlType.INT32, "id"));
    }

    @Test
    void stringToIntFails() {
        assertThrows(TypeMismatchException.class,
            () -> TypeCoercion.coerce("abc", SqlType.INT32, "id"));
    }

    @Test
    void doubleToIntFails() {
        assertThrows(TypeMismatchException.class,
            () -> TypeCoercion.coerce(3.14, SqlType.INT32, "id"));
    }

    // ========== VARCHAR ==========

    @Test
    void stringPassThrough() {
        assertEquals("hello", TypeCoercion.coerce("hello", SqlType.VARCHAR, "name"));
    }

    @Test
    void intToString() {
        assertEquals("42", TypeCoercion.coerce(42, SqlType.VARCHAR, "name"));
    }

    // ========== DECIMAL ==========

    @Test
    void doublePassThrough() {
        assertEquals(3.14, TypeCoercion.coerce(3.14, SqlType.DECIMAL, "price"));
    }

    @Test
    void intToDecimal() {
        assertEquals(42.0, TypeCoercion.coerce(42, SqlType.DECIMAL, "price"));
    }

    @Test
    void stringToDecimal() {
        assertEquals(9.99, TypeCoercion.coerce("9.99", SqlType.DECIMAL, "price"));
    }

    @Test
    void stringToDecimalFails() {
        assertThrows(TypeMismatchException.class,
            () -> TypeCoercion.coerce("not_a_number", SqlType.DECIMAL, "price"));
    }

    // ========== NULL ==========

    @Test
    void nullPassesAnyType() {
        assertNull(TypeCoercion.coerce(null, SqlType.INT32, "id"));
        assertNull(TypeCoercion.coerce(null, SqlType.VARCHAR, "name"));
        assertNull(TypeCoercion.coerce(null, SqlType.DECIMAL, "price"));
    }

    // ========== INSERT 集成 ==========

    @Test
    void insertWithTypeCoercion() {
        // 模拟 INSERT INTO users (id, name) VALUES ('1', 'alice')
        // '1' 是 String，应该被转换为 INT32
        Object result = TypeCoercion.coerce("1", SqlType.INT32, "id");
        assertEquals(1, result);
        assertInstanceOf(Integer.class, result);
    }
}
