package cn.zhangyis.minidb.sql.exec;

/**
 * Decorator 模式包装 ExecNode，收集运行时统计信息。
 */
public class InstrumentedExecNode implements ExecNode {
    private final ExecNode delegate;
    private long rowCount;
    private long openTimeNanos;
    private long totalNextTimeNanos;

    public InstrumentedExecNode(ExecNode delegate) {
        this.delegate = delegate;
    }

    @Override
    public void open() {
        long start = System.nanoTime();
        delegate.open();
        openTimeNanos = System.nanoTime() - start;
    }

    @Override
    public Row next() {
        long start = System.nanoTime();
        Row row = delegate.next();
        totalNextTimeNanos += System.nanoTime() - start;
        if (row != null) {
            rowCount++;
        }
        return row;
    }

    @Override
    public void close() {
        delegate.close();
    }

    public ExecNode delegate() {
        return delegate;
    }

    public long rowCount() {
        return rowCount;
    }

    public double totalTimeMs() {
        return (openTimeNanos + totalNextTimeNanos) / 1_000_000.0;
    }
}
