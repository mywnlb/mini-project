package cn.zhangyis.minidb.sql.optimize.cost;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.Map;

public class CostModel {
    private final Map<String, Long> tableStats = Map.of(
        "users", 10000L,
        "orders", 50000L
    );

    public double fullScanCost(String tableName) {
        return tableStats.getOrDefault(tableName, 100000L) * 0.01;
    }

    public double indexScanCost(String tableName, SqlNode condition) {
        return condition.toString().contains("id") ? 10.0 : 100.0;
    }

    public double filterCost(double inputRows, SqlNode condition) {
        return inputRows * 0.3;
    }

    public double joinCost(RelNode left, RelNode right) {
        return estimateRows(left) * estimateRows(right) * 0.001;
    }

    public double aggregateCost(RelNode input) {
        return estimateRows(input) * 0.5;
    }

    public double sortCost(RelNode input) {
        double rows = estimateRows(input);
        return rows > 0 ? rows * Math.log(rows) * 0.01 : 0;
    }

    public double estimateRows(RelNode node) {
        if (node instanceof RelScan s) return s.tableMeta().rowCount();
        if (node instanceof RelIndexedScan) return 10;
        if (node instanceof RelFilter f) return estimateRows(f.input()) * 0.3;
        if (node instanceof RelJoin j) return estimateRows(j.left()) * estimateRows(j.right()) * 0.01;
        if (node instanceof RelAggregate a) return estimateRows(a.input()) * 0.1;
        if (node instanceof RelSort s) return estimateRows(s.input());
        if (node instanceof RelProject p) return estimateRows(p.input());
        return 10000;
    }
}
