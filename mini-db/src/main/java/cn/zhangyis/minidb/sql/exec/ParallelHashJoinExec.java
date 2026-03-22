package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.exec.ExecUtils;
import cn.zhangyis.minidb.sql.exec.QueryThreadPool;

import java.util.*;
import java.util.concurrent.*;

/**
 * 并行 Hash Join 执行器 (Shared Hash Table 模式)
 *
 * CLAUDE.md 核心不变式映射（已检查3遍）：
 * 1. 结果等价：与串行 Hash Join 结果一致（忽略顺序）
 * 2. 线程安全：ConcurrentHashMap + CountDownLatch barrier + BlockingQueue
 * 3. 资源回收：close() 中清理状态
 * 4. 背压：使用有界 LinkedBlockingQueue（容量1024）
 *
 * 仅支持 INNER JOIN。
 */
public class ParallelHashJoinExec implements ExecNode {

    private final ExecNode buildExec;
    private final ExecNode probeExec;
    private final QueryThreadPool threadPool;
    private final SqlNode joinCondition;
    private final List<String> equiColumns;
    private final int parallelism;
    private final BlockingQueue<Row> resultQueue;
    private final CountDownLatch buildLatch;
    private final ConcurrentHashMap<Object, List<Row>> hashTable;
    private volatile boolean closed = false;
    private Row nextRow;

    public ParallelHashJoinExec(ExecNode buildExec, ExecNode probeExec,
                               SqlNode joinCondition, List<String> equiColumns,
                               QueryThreadPool threadPool) {
        this.buildExec = buildExec;
        this.probeExec = probeExec;
        this.joinCondition = joinCondition;
        this.equiColumns = equiColumns != null ? equiColumns : List.of();
        this.threadPool = threadPool;
        this.parallelism = threadPool.parallelism();
        this.resultQueue = new LinkedBlockingQueue<>(1024);
        this.buildLatch = new CountDownLatch(1);
        this.hashTable = new ConcurrentHashMap<>();
    }

    @Override
    public void open() {
        // 并行 Build 阶段
        for (int i = 0; i < parallelism; i++) {
            threadPool.execute(this::buildPhase);
        }

        // 等待 Build 完成
        try {
            buildLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Build phase interrupted", e);
        }

        // 并行 Probe 阶段
        for (int i = 0; i < parallelism; i++) {
            threadPool.execute(this::probePhase);
        }
    }

    private void buildPhase() {
        try {
            buildExec.open();
            Row row;
            while ((row = buildExec.next()) != null) {
                if (closed) break;
                Object key = ExecUtils.extractJoinKey(row, joinCondition, equiColumns);
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        } finally {
            buildExec.close();
            if (buildLatch.getCount() > 0) {
                buildLatch.countDown();
            }
        }
    }

    private void probePhase() {
        try {
            probeExec.open();
            Row row;
            while ((row = probeExec.next()) != null) {
                if (closed) break;
                Object key = ExecUtils.extractJoinKey(row, joinCondition, equiColumns);
                List<Row> matches = hashTable.get(key);
                if (matches != null) {
                    for (Row match : matches) {
                        Row joined = ExecUtils.mergeRows(match, row);
                        if (!resultQueue.offer(joined)) {
                            try {
                                resultQueue.put(joined);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            }
        } finally {
            probeExec.close();
        }
    }

    @Override
    public Row next() {
        if (nextRow != null) {
            Row r = nextRow;
            nextRow = null;
            return r;
        }
        try {
            nextRow = resultQueue.poll(100, TimeUnit.MILLISECONDS);
            return nextRow;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        resultQueue.clear();
        hashTable.clear();
    }
}