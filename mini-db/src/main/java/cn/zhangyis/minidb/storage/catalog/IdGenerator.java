package cn.zhangyis.minidb.storage.catalog;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局 ID 分配器
 *
 * <p>统一分配 tableId、columnId、indexId、databaseId。
 * 每个 ID 空间使用独立的 AtomicLong 保证线程安全。</p>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>I1: ID 值单调递增，永不回退</li>
 *   <li>I1: 分配后新计数器值必须立即持久化到 CatalogMetaPage（由调用方保证）</li>
 * </ul>
 *
 * <h3>持久化契约</h3>
 * <p>IdGenerator 本身不执行持久化。调用方（CatalogManager）必须在 allocateXxx() 返回后、
 * 对外可见前，将新的 nextXxxId 写入 CatalogMetaPage 并通过 MTR commit。</p>
 */
public class IdGenerator {

    private final AtomicLong nextTableId;
    private final AtomicLong nextColumnId;
    private final AtomicLong nextIndexId;
    private final AtomicLong nextDatabaseId;

    /**
     * 从持久化状态恢复
     *
     * @param nextTableId    下一个可分配的 tableId（从 CatalogMetaPage 读取）
     * @param nextColumnId   下一个可分配的 columnId
     * @param nextIndexId    下一个可分配的 indexId
     * @param nextDatabaseId 下一个可分配的 databaseId
     */
    public IdGenerator(long nextTableId, long nextColumnId,
                       long nextIndexId, long nextDatabaseId) {
        if (nextTableId < 1 || nextColumnId < 1 || nextIndexId < 1 || nextDatabaseId < 1) {
            throw new IllegalArgumentException(
                    "All next-ID values must be >= 1");
        }
        this.nextTableId = new AtomicLong(nextTableId);
        this.nextColumnId = new AtomicLong(nextColumnId);
        this.nextIndexId = new AtomicLong(nextIndexId);
        this.nextDatabaseId = new AtomicLong(nextDatabaseId);
    }

    /**
     * 首次初始化（所有计数器从 1 开始）
     */
    public IdGenerator() {
        this(1, 1, 1, 1);
    }

    /** 分配一个 tableId，返回值即为分配的 ID */
    public long allocateTableId() {
        return nextTableId.getAndIncrement();
    }

    /** 分配一个 columnId */
    public long allocateColumnId() {
        return nextColumnId.getAndIncrement();
    }

    /** 分配一个 indexId */
    public long allocateIndexId() {
        return nextIndexId.getAndIncrement();
    }

    /** 分配一个 databaseId */
    public long allocateDatabaseId() {
        return nextDatabaseId.getAndIncrement();
    }

    // ==================== 快照读取（用于持久化）====================

    /** 获取当前 nextTableId（用于写入 CatalogMetaPage） */
    public long getNextTableId() {
        return nextTableId.get();
    }

    public long getNextColumnId() {
        return nextColumnId.get();
    }

    public long getNextIndexId() {
        return nextIndexId.get();
    }

    public long getNextDatabaseId() {
        return nextDatabaseId.get();
    }

    /**
     * 用持久化快照重置所有计数器。
     */
    public void reset(long nextTableId, long nextColumnId,
                      long nextIndexId, long nextDatabaseId) {
        if (nextTableId < 1 || nextColumnId < 1 || nextIndexId < 1 || nextDatabaseId < 1) {
            throw new IllegalArgumentException(
                    "All next-ID values must be >= 1");
        }
        this.nextTableId.set(nextTableId);
        this.nextColumnId.set(nextColumnId);
        this.nextIndexId.set(nextIndexId);
        this.nextDatabaseId.set(nextDatabaseId);
    }

    public void resetFrom(IdGenerator source) {
        reset(source.getNextTableId(), source.getNextColumnId(),
                source.getNextIndexId(), source.getNextDatabaseId());
    }

    @Override
    public String toString() {
        return String.format("IdGenerator{table=%d, column=%d, index=%d, db=%d}",
                nextTableId.get(), nextColumnId.get(),
                nextIndexId.get(), nextDatabaseId.get());
    }
}
