package cn.zhangyis.minidb.sql.exec;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 模拟数据源：提供表的行数据，支持读写
 */
public class MockDataSource {
    private static final Map<String, List<Row>> DATA = new HashMap<>();

    static {
        reset();
    }

    public static void reset() {
        DATA.clear();

        // users 表
        List<Row> users = new ArrayList<>();
        users.add(row("users.id", 1, "users.name", "alice"));
        users.add(row("users.id", 2, "users.name", "bob"));
        users.add(row("users.id", 3, "users.name", "charlie"));
        users.add(row("users.id", 4, "users.name", "alice"));
        users.add(row("users.id", 5, "users.name", "dave"));
        DATA.put("USERS", users);

        // orders 表
        List<Row> orders = new ArrayList<>();
        orders.add(row("orders.order_id", 101, "orders.user_id", 1, "orders.amount", 250));
        orders.add(row("orders.order_id", 102, "orders.user_id", 2, "orders.amount", 130));
        orders.add(row("orders.order_id", 103, "orders.user_id", 1, "orders.amount", 80));
        orders.add(row("orders.order_id", 104, "orders.user_id", 3, "orders.amount", 500));
        orders.add(row("orders.order_id", 105, "orders.user_id", 5, "orders.amount", 60));
        DATA.put("ORDERS", orders);
    }

    public static List<Row> getTableData(String tableName) {
        return DATA.getOrDefault(tableName.toUpperCase(), new ArrayList<>());
    }

    public static void insertRow(String tableName, Row row) {
        DATA.computeIfAbsent(tableName.toUpperCase(), k -> new ArrayList<>()).add(row);
    }

    public static int deleteRows(String tableName, Predicate<Row> condition) {
        List<Row> rows = DATA.get(tableName.toUpperCase());
        if (rows == null) return 0;
        int before = rows.size();
        rows.removeIf(condition);
        return before - rows.size();
    }

    public static int updateRows(String tableName, Predicate<Row> condition, Consumer<Row> updater) {
        List<Row> rows = DATA.get(tableName.toUpperCase());
        if (rows == null) return 0;
        int count = 0;
        for (Row row : rows) {
            if (condition.test(row)) {
                updater.accept(row);
                count++;
            }
        }
        return count;
    }

    private static Row row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return new Row(map);
    }

    private static Row row(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return new Row(map);
    }
}
