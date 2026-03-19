package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlAlterTable;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlLiteral;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.rel.RelAlterTable;

import java.util.Map;

/**
 * ALTER TABLE ADD COLUMN 执行器
 *
 * <p>构造包含 nullable/defaultValue 的 ColumnMeta，委托 CatalogSpi.addColumn()，
 * 成功后通过 DataSourceSpi.invalidateTable() 失效缓存（INV-5）。</p>
 */
public class AlterTableExec implements ExecNode {
    private final RelAlterTable relAlter;
    private final DataSourceSpi dataSource;
    private String message;
    private boolean returned;

    public AlterTableExec(RelAlterTable relAlter, DataSourceSpi dataSource) {
        this.relAlter = relAlter;
        this.dataSource = dataSource;
    }

    @Override
    public void open() {
        SqlAlterTable alter = relAlter.alterTable();
        CatalogSpi catalog = relAlter.catalog();
        String tableName = alter.table().name();

        // 提取默认值字面量
        Object defaultValue = extractDefaultValue(alter.defaultValue());

        // 构造包含 nullable/defaultValue 的 ColumnMeta
        ColumnMeta newCol = new ColumnMeta(
                alter.columnName(), alter.columnType(), false,
                alter.nullable(), defaultValue);

        catalog.addColumn(tableName, newCol);

        // INV-5: DDL 后缓存失效
        if (dataSource != null) {
            dataSource.invalidateTable(tableName);
        }

        message = "Column '" + alter.columnName() + "' added to table '" + tableName + "'";
        returned = false;
    }

    /**
     * 从 SqlNode 默认值提取 Java 对象。
     */
    private Object extractDefaultValue(SqlNode defaultNode) {
        if (defaultNode == null || defaultNode.kind() == SqlKind.NULL_LITERAL) {
            return null;
        }
        if (defaultNode instanceof SqlLiteral lit) {
            String val = lit.value();
            return switch (lit.type()) {
                case INT32 -> Integer.parseInt(val);
                case BIGINT -> Long.parseLong(val);
                case VARCHAR -> val;
                case DECIMAL -> Double.parseDouble(val);
                case DATETIME -> val;
            };
        }
        return null;
    }

    @Override
    public Row next() {
        if (!returned) {
            returned = true;
            return new Row(Map.of("result", message));
        }
        return null;
    }

    @Override
    public void close() {}
}
