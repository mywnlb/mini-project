package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.RelScan;
import cn.zhangyis.minidb.sql.rel.RelFilter;
import cn.zhangyis.minidb.sql.rel.RelUpdate;
import cn.zhangyis.minidb.sql.types.TypeCoercion;

import java.util.Map;

/**
 * UPDATE 执行器：扫描满足 WHERE 条件的行，执行 SET 赋值
 */
public class UpdateExec implements ExecNode {
    private final RelUpdate relUpdate;
    private final DataSourceSpi dataSource;
    private final CatalogSpi catalog;
    private int affectedRows;
    private boolean returned;

    public UpdateExec(RelUpdate relUpdate) {
        this(relUpdate, MockDataSourceAdapter.INSTANCE, null);
    }

    public UpdateExec(RelUpdate relUpdate, DataSourceSpi dataSource) {
        this(relUpdate, dataSource, null);
    }

    public UpdateExec(RelUpdate relUpdate, DataSourceSpi dataSource, CatalogSpi catalog) {
        this.relUpdate = relUpdate;
        this.dataSource = dataSource;
        this.catalog = catalog;
    }

    @Override
    public void open() {
        // 提取表名和 WHERE 条件
        String tableName = extractTableName(relUpdate.input());
        SqlNode whereCondition = extractCondition(relUpdate.input());

        // 获取表元数据用于类型校验
        TableMeta meta = catalog != null ? catalog.getTable(tableName) : null;

        affectedRows = dataSource.updateRows(tableName,
            row -> whereCondition == null || FilterExec.evaluate(whereCondition, row),
            row -> {
                for (SqlNode node : relUpdate.assignments().nodes()) {
                    SqlAssignment assign = (SqlAssignment) node;
                    Object value = FilterExec.resolveValue(assign.value(), row);

                    // 类型校验和转换
                    if (meta != null) {
                        String colName = assign.column().name();
                        ColumnMeta colMeta = findColumn(meta, colName);
                        if (colMeta != null) {
                            value = TypeCoercion.coerce(value, colMeta.type(), colName);
                        }
                    }
                    row.put(assign.column().name(), value);
                }
            }
        );
        returned = false;
    }

    @Override
    public Row next() {
        if (!returned) {
            returned = true;
            return new Row(Map.of("affected_rows", affectedRows));
        }
        return null;
    }

    @Override
    public void close() {}

    private String extractTableName(cn.zhangyis.minidb.sql.rel.RelNode input) {
        if (input instanceof RelScan scan) return scan.tableName();
        if (input instanceof RelFilter filter) return extractTableName(filter.input());
        throw new IllegalStateException("Cannot extract table name from: " + input.getClass().getSimpleName());
    }

    private SqlNode extractCondition(cn.zhangyis.minidb.sql.rel.RelNode input) {
        if (input instanceof RelFilter filter) return filter.condition();
        return null;
    }

    private ColumnMeta findColumn(TableMeta meta, String name) {
        return meta.columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }
}
