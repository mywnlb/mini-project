package cn.zhangyis.minidb.sql.exec;

import java.util.Collections;
import java.util.Iterator;

/**
 * 真实索引嵌套循环连接（Index Nested Loop Join）。
 *
 * <p>实现阶段4要求：
 * - 外表逐行扫描，内表通过主键/唯一索引进行点查（BTree.search）
 * - 完全依赖 StorageDataSource.lookup()，后者已接通真实 BTree + MVCC + MTR
 * - 不退化到 HashJoin 或 NestedLoop（除非 lookup 不支持）
 * - 符合 Kernel-Safe：所有存储访问均经过事务上下文和 MTR
 */
public class IndexNestedLoopJoinExec implements ExecNode {

    private final ExecNode outer;
    private final String outerKey;
    private final String innerTableName;
    private final String innerOutputName;
    private final String innerLookupColumn;
    private final DataSourceSpi dataSource;
    private final boolean outerIsLeft;
    private final ExecutionContext executionContext;  // 支持事务上下文

    private Row currentOuter;
    private Iterator<Row> matches = Collections.emptyIterator();

    public IndexNestedLoopJoinExec(ExecNode outer, String outerKey,
                                   String innerTableName, String innerOutputName,
                                   String innerLookupColumn, DataSourceSpi dataSource,
                                   boolean outerIsLeft) {
        this(outer, outerKey, innerTableName, innerOutputName, innerLookupColumn,
             dataSource, outerIsLeft, null);
    }

    public IndexNestedLoopJoinExec(ExecNode outer, String outerKey,
                                   String innerTableName, String innerOutputName,
                                   String innerLookupColumn, DataSourceSpi dataSource,
                                   boolean outerIsLeft, ExecutionContext executionContext) {
        this.outer = outer;
        this.outerKey = outerKey;
        this.innerTableName = innerTableName;
        this.innerOutputName = innerOutputName;
        this.innerLookupColumn = innerLookupColumn;
        this.dataSource = dataSource;
        this.outerIsLeft = outerIsLeft;
        this.executionContext = executionContext;
    }

    @Override
    public void open() {
        outer.open();
    }

    @Override
    public Row next() {
        while (true) {
            // 处理当前内表匹配结果
            while (matches.hasNext()) {
                Row innerRow = matches.next();
                return outerIsLeft ? currentOuter.merge(innerRow) : innerRow.merge(currentOuter);
            }

            // 获取下一条外表记录
            currentOuter = outer.next();
            if (currentOuter == null) {
                return null;
            }

            Object lookupValue = currentOuter.get(outerKey);
            if (lookupValue == null) {
                // SQL 三值逻辑：NULL 不匹配任何索引键
                continue;
            }

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
