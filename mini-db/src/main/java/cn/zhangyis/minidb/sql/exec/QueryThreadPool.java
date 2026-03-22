package cn.zhangyis.minidb.sql.exec;

import java.util.concurrent.*;

/**
 * 查询级共享线程池
 * 避免每个算子独立创建线程池导致线程爆炸
 */
public class QueryThreadPool implements AutoCloseable {

    private final ExecutorService executor;
    private final int parallelism;

    public QueryThreadPool(int parallelism) {
        this.parallelism = Math.max(1, parallelism);
        this.executor = Executors.newFixedThreadPool(this.parallelism, r -> {
            Thread t = new Thread(r, "mini-db-query-worker-" + System.nanoTime() % 10000);
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutorService executor() {
        return executor;
    }

    public int parallelism() {
        return parallelism;
    }

    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}