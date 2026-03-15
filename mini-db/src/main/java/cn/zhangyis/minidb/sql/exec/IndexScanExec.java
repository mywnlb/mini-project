package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlBinaryOp;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.rel.RelIndexedScan;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * 基于 DataSourceSpi.lookup 的真实点查执行器。
 */
public class IndexScanExec implements ExecNode {
    private final RelIndexedScan scan;
    private final DataSourceSpi dataSource;
    private Iterator<Row> iterator;

    public IndexScanExec(RelIndexedScan scan, DataSourceSpi dataSource) {
        this.scan = scan;
        this.dataSource = dataSource;
    }

    @Override
    public void open() {
        if (!(scan.indexCondition() instanceof SqlBinaryOp binOp) || binOp.kind() != SqlKind.BINARY_EQ) {
            throw new IllegalStateException("Indexed scan requires equality condition, got: " + scan.indexCondition());
        }

        SqlNode lookupSide = binOp.left();
        SqlNode valueSide = binOp.right();
        if (!(lookupSide instanceof SqlIdentifier)) {
            lookupSide = binOp.right();
            valueSide = binOp.left();
        }

        if (!(lookupSide instanceof SqlIdentifier id)) {
            throw new IllegalStateException("Indexed scan requires identifier lookup side");
        }

        Object lookupValue = FilterExec.resolveValue(valueSide, new Row(Map.of()));
        if (lookupValue == null) {
            iterator = Collections.emptyIterator();
            return;
        }
        String columnName = id.name().contains(".") ? id.name().split("\\.", 2)[1] : id.name();
        iterator = dataSource.lookup(scan.tableName(), scan.outputName(), columnName, lookupValue);
    }

    @Override
    public Row next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        iterator = null;
    }
}
