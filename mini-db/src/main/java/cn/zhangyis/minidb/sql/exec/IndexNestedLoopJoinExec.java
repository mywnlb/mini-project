package cn.zhangyis.minidb.sql.exec;

import java.util.Collections;
import java.util.Iterator;

/**
 * 真实 lookup join：外表逐行 probe，内表按主键点查。
 */
public class IndexNestedLoopJoinExec implements ExecNode {
    private final ExecNode outer;
    private final String outerKey;
    private final String innerTableName;
    private final String innerOutputName;
    private final String innerLookupColumn;
    private final DataSourceSpi dataSource;
    private final boolean outerIsLeft;

    private Row currentOuter;
    private Iterator<Row> matches = Collections.emptyIterator();

    public IndexNestedLoopJoinExec(ExecNode outer, String outerKey,
                                   String innerTableName, String innerOutputName,
                                   String innerLookupColumn, DataSourceSpi dataSource,
                                   boolean outerIsLeft) {
        this.outer = outer;
        this.outerKey = outerKey;
        this.innerTableName = innerTableName;
        this.innerOutputName = innerOutputName;
        this.innerLookupColumn = innerLookupColumn;
        this.dataSource = dataSource;
        this.outerIsLeft = outerIsLeft;
    }

    @Override
    public void open() {
        outer.open();
    }

    @Override
    public Row next() {
        while (true) {
            while (matches.hasNext()) {
                Row innerRow = matches.next();
                return outerIsLeft ? currentOuter.merge(innerRow) : innerRow.merge(currentOuter);
            }

            currentOuter = outer.next();
            if (currentOuter == null) {
                return null;
            }

            Object lookupValue = currentOuter.get(outerKey);
            matches = dataSource.lookup(
                innerTableName,
                innerOutputName,
                innerLookupColumn,
                lookupValue
            );
        }
    }

    @Override
    public void close() {
        outer.close();
        matches = Collections.emptyIterator();
        currentOuter = null;
    }
}
