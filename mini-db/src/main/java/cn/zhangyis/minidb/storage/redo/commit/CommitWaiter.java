package cn.zhangyis.minidb.storage.redo.commit;

/**
 * Commit Waiter - 提交等待者
 *
 * <p>表示一个正在等待 redo log fsync 完成的提交线程。
 * 用于 Group Commit 机制中的精准唤醒。</p>
 *
 * <h2>使用场景</h2>
 * <pre>
 * T1: commit(sn=100) → 加入队列 → 等待唤醒
 * T2: commit(sn=200) → 加入队列 → 等待唤醒
 * Leader: fsync() → 唤醒 sn <= flushedSn 的所有 waiter
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CommitWaiter {

    /** 提交的 SN (redo log 结束位置) */
    private final long commitSn;

    /** 等待的线程 */
    private final Thread thread;

    /** 是否已完成 (用于避免 spurious wakeup) */
    private volatile boolean completed;

    /**
     * 创建 CommitWaiter
     *
     * @param commitSn 提交的 SN
     * @param thread   等待的线程
     */
    public CommitWaiter(long commitSn, Thread thread) {
        this.commitSn = commitSn;
        this.thread = thread;
        this.completed = false;
    }

    /**
     * 获取提交的 SN
     */
    public long getCommitSn() {
        return commitSn;
    }

    /**
     * 获取等待的线程
     */
    public Thread getThread() {
        return thread;
    }

    /**
     * 检查是否已完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 标记为已完成
     */
    public void markCompleted() {
        this.completed = true;
    }

    @Override
    public String toString() {
        return String.format("CommitWaiter{sn=%d, thread=%s, completed=%s}",
                commitSn, thread.getName(), completed);
    }
}
