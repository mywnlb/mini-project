package cn.zhangyis.minidb.sql.exec;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并行扫描执行器：将表按分区并行扫描，通过 BlockingQueue 汇聚结果
 */
public class ParallelScanExec implements ExecNode {
    private final String tableName;
    private final String outputName;
    private final DataSourceSpi dataSource;
    private final int parallelism;

    private BlockingQueue<Row> queue;
    private ExecutorService executor;
    private AtomicInteger finishedCount;
    private AtomicReference<Throwable> error;
    private volatile boolean closed;
    private int partitions;

    private static final Row POISON = new Row(Map.of());

    public ParallelScanExec(String tableName, String outputName, DataSourceSpi dataSource, int parallelism) {
        this.tableName = tableName;
        this.outputName = outputName;
        this.dataSource = dataSource;
        this.parallelism = parallelism;
    }

    @Override
    public void open() {
        partitions = Math.min(parallelism, dataSource.partitionCount(tableName));
        queue = new ArrayBlockingQueue<>(1024);
        finishedCount = new AtomicInteger(0);
        error = new AtomicReference<>();
        closed = false;
        executor = Executors.newFixedThreadPool(partitions);

        for (int i = 0; i < partitions; i++) {
            final int pid = i;
            executor.submit(() -> {
                try {
                    Iterator<Row> iter = dataSource.scanPartition(tableName, pid, partitions);
                    while (iter.hasNext() && !closed) {
                        queue.put(iter.next());
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    if (finishedCount.incrementAndGet() == partitions) {
                        try {
                            queue.put(POISON);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
        }
    }

    @Override
    public Row next() {
        Throwable err = error.get();
        if (err != null) throw new RuntimeException("Partition scan error", err);
        try {
            Row row = queue.take();
            if (row == POISON) return null;
            err = error.get();
            if (err != null) throw new RuntimeException("Partition scan error", err);
            return row;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during parallel scan", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
