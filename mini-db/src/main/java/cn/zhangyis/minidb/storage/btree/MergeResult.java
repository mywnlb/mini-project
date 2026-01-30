package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * 页面合并结果
 *
 * <p>表示页面合并操作的结果。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class MergeResult {

    /** 合并类型 */
    public enum MergeType {
        /** 无需合并 */
        NONE,
        /** 与左兄弟合并 */
        MERGE_LEFT,
        /** 与右兄弟合并 */
        MERGE_RIGHT,
        /** 从左兄弟借记录 */
        REDISTRIBUTE_LEFT,
        /** 从右兄弟借记录 */
        REDISTRIBUTE_RIGHT
    }

    /** 合并类型 */
    private final MergeType type;

    /** 被删除的页面 ID（合并后） */
    private final PageId deletedPageId;

    /** 需要从父节点删除的键 */
    private final byte[] deletedKey;

    /** 新的分隔键（重分布时） */
    private final byte[] newSeparatorKey;

    /**
     * 构造无需合并的结果
     */
    public static MergeResult none() {
        return new MergeResult(MergeType.NONE, null, null, null);
    }

    /**
     * 构造合并结果
     */
    public static MergeResult merge(MergeType type, PageId deletedPageId, byte[] deletedKey) {
        return new MergeResult(type, deletedPageId, deletedKey, null);
    }

    /**
     * 构造重分布结果
     */
    public static MergeResult redistribute(MergeType type, byte[] newSeparatorKey) {
        return new MergeResult(type, null, null, newSeparatorKey);
    }

    private MergeResult(MergeType type, PageId deletedPageId, byte[] deletedKey, byte[] newSeparatorKey) {
        this.type = type;
        this.deletedPageId = deletedPageId;
        this.deletedKey = deletedKey;
        this.newSeparatorKey = newSeparatorKey;
    }

    public MergeType getType() {
        return type;
    }

    public PageId getDeletedPageId() {
        return deletedPageId;
    }

    public byte[] getDeletedKey() {
        return deletedKey;
    }

    public byte[] getNewSeparatorKey() {
        return newSeparatorKey;
    }

    public boolean needsMerge() {
        return type == MergeType.MERGE_LEFT || type == MergeType.MERGE_RIGHT;
    }

    public boolean needsRedistribute() {
        return type == MergeType.REDISTRIBUTE_LEFT || type == MergeType.REDISTRIBUTE_RIGHT;
    }

    public boolean needsParentUpdate() {
        return type != MergeType.NONE;
    }

    @Override
    public String toString() {
        return String.format("MergeResult{type=%s}", type);
    }
}
