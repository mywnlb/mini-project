package cn.zhangyis.minidb.storage.btree;

/**
 * 插入操作结果
 *
 * <p>表示插入操作的结果，包含记录偏移和可能的分裂信息。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class InsertResult {

    /** 新记录的偏移 */
    private final int recordOffset;

    /** 分裂结果（如果发生了分裂） */
    private final SplitResult splitResult;

    /**
     * 构造插入结果
     *
     * @param recordOffset 新记录偏移
     * @param splitResult  分裂结果，如果没有分裂则为 null
     */
    public InsertResult(int recordOffset, SplitResult splitResult) {
        this.recordOffset = recordOffset;
        this.splitResult = splitResult;
    }

    /**
     * 获取新记录的偏移
     *
     * @return 记录偏移
     */
    public int getRecordOffset() {
        return recordOffset;
    }

    /**
     * 获取分裂结果
     *
     * @return 分裂结果，如果没有分裂则为 null
     */
    public SplitResult getSplitResult() {
        return splitResult;
    }

    /**
     * 检查是否发生了分裂
     *
     * @return 如果发生了分裂返回 true
     */
    public boolean wasSplit() {
        return splitResult != null;
    }

    @Override
    public String toString() {
        if (splitResult != null) {
            return String.format("InsertResult{offset=%d, split=%s}", recordOffset, splitResult);
        } else {
            return String.format("InsertResult{offset=%d, noSplit}", recordOffset);
        }
    }
}
