package cn.zhangyis.minidb.sql.optimize.cost;

public class CostModel {
    private final Map<String, Long> tableStats = Map.of(
        "users", 10000L,
        "orders", 50000L
    );

    public double fullScanCost(String tableName) {
        return tableStats.getOrDefault(tableName, 100000L) * 0.01;
    }

    public double indexScanCost(String tableName, SqlNode condition) {
        // MVP: id条件成本低
        return condition.toString().contains("id") ? 10.0 : 100.0;
    }

    public double filterCost(double inputRows, SqlNode condition) {
        return inputRows * 0.3;  // 选择性30%
    }
}