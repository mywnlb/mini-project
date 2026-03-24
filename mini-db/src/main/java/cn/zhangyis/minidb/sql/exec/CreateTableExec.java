package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlCreateTable;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.rel.RelCreateTable;

import java.util.List;
import java.util.Map;

/**
 * CREATE TABLE 执行器
 */
public class CreateTableExec implements ExecNode {
    private final RelCreateTable relCreate;
    private String message;
    private boolean returned;

    public CreateTableExec(RelCreateTable relCreate) {
        this.relCreate = relCreate;
    }

    @Override
    public void open() {
        SqlCreateTable create = relCreate.createTable();
        CatalogSpi catalog = relCreate.catalog();
        String tableName = create.table().name();

        if (create.ifNotExists() && catalog.tableExists(tableName)) {
            message = "Table '" + tableName + "' already exists, skipped";
        } else {
            List<ColumnMeta> columns = create.columnDefs().stream()
                .map(def -> new ColumnMeta(def.name(), def.type(), def.primaryKey(), def.nullable(), null))
                .toList();
            catalog.createTable(TableMeta.of(tableName, columns, 0));
            message = "Table '" + tableName + "' created";
        }
        returned = false;
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
