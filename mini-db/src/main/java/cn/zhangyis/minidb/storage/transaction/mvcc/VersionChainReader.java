package cn.zhangyis.minidb.storage.transaction.mvcc;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.DeleteUndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.InsertUndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 版本链读取器
 *
 * <p>沿着 ROLL_PTR 遍历 Undo Log 中的版本链，找到对 ReadView 可见的版本。</p>
 *
 * <h2>版本链结构</h2>
 * <pre>
 * 当前记录 (TRX_ID=100, ROLL_PTR=ptr1)
 *     │
 *     ▼ ptr1
 * UpdateUndo (TRX_ID=80, prev_undo=ptr2, old_values={...})
 *     │
 *     ▼ ptr2
 * UpdateUndo (TRX_ID=50, prev_undo=ptr3, old_values={...})
 *     │
 *     ▼ ptr3
 * InsertUndo (TRX_ID=30, prev_undo=NULL)  ← 版本链终点
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T5</b>: ROLL_PTR.is_insert 必须正确标记 INSERT Undo</li>
 *   <li><b>T6</b>: 版本链必须终止于 INSERT Undo 或 NULL</li>
 *   <li><b>V1</b>: 版本链遍历必须在 page latch 保护下</li>
 *   <li><b>V2</b>: 遇到 NULL 指针或 INSERT Undo 时必须停止</li>
 * </ul>
 *
 * <h2>遍历限制</h2>
 * <p>为防止错误的版本链导致无限循环，设置最大遍历深度限制。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see RollbackPointer
 * @see UndoRecord
 * @see ReadView
 */
public class VersionChainReader {

    // ==================== 常量 ====================

    /**
     * 默认最大遍历深度
     *
     * <p>防止错误的版本链导致无限循环。
     * 正常情况下版本链不会太深，因为 Purge 线程会清理不需要的旧版本。</p>
     */
    public static final int DEFAULT_MAX_DEPTH = 1000;

    // ==================== 字段 ====================

    /**
     * Undo 记录读取器
     */
    private final UndoRecordReader undoReader;

    /**
     * 最大遍历深度
     */
    private final int maxDepth;

    // ==================== 构造函数 ====================

    /**
     * 创建版本链读取器
     *
     * @param undoReader Undo 记录读取器
     */
    public VersionChainReader(UndoRecordReader undoReader) {
        this(undoReader, DEFAULT_MAX_DEPTH);
    }

    /**
     * 创建版本链读取器 (自定义最大深度)
     *
     * @param undoReader Undo 记录读取器
     * @param maxDepth   最大遍历深度
     */
    public VersionChainReader(UndoRecordReader undoReader, int maxDepth) {
        if (undoReader == null) {
            throw new NullPointerException("undoReader cannot be null");
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive");
        }
        this.undoReader = undoReader;
        this.maxDepth = maxDepth;
    }

    // ==================== 核心方法 ====================

    /**
     * 查找对 ReadView 可见的版本
     *
     * <h3>算法</h3>
     * <ol>
     *   <li>从 rollPtr 指向的 Undo 记录开始</li>
     *   <li>检查该版本的 trxId 是否可见</li>
     *   <li>如果可见，返回该版本</li>
     *   <li>如果不可见，沿 prev_undo 继续遍历</li>
     *   <li>遇到 INSERT Undo 或 NULL 时停止</li>
     * </ol>
     *
     * @param startPtr 起始回滚指针
     * @param readView 读视图
     * @return 可见版本，如果没有可见版本返回 empty
     * @throws VersionChainException 如果版本链损坏或超过最大深度
     */
    public Optional<RecordVersion> findVisibleVersion(RollbackPointer startPtr,
                                                      ReadView readView) {
        if (startPtr == null || startPtr.isNull()) {
            return Optional.empty();
        }
        if (readView == null) {
            throw new NullPointerException("readView cannot be null");
        }

        RollbackPointer currentPtr = startPtr;
        int depth = 0;

        while (!currentPtr.isNull() && depth < maxDepth) {
            depth++;

            // 读取 Undo 记录
            UndoRecord undoRecord = undoReader.read(currentPtr);
            if (undoRecord == null) {
                // Undo 记录可能已被 purge
                return Optional.empty();
            }

            // 检查可见性
            TransactionId recordTrxId = undoRecord.getTrxId();
            if (VisibilityChecker.isVisible(recordTrxId, readView)) {
                // 找到可见版本
                return Optional.of(createVersion(undoRecord));
            }

            // 检查是否是 INSERT Undo (版本链终点)
            if (undoRecord instanceof InsertUndoRecord) {
                // 到达版本链起点，记录在此之前不存在
                // 如果 INSERT 不可见，说明记录对当前事务完全不存在
                return Optional.empty();
            }

            // 继续遍历
            currentPtr = undoRecord.getPrevUndoPtr();
        }

        if (depth >= maxDepth) {
            throw new VersionChainException(
                    "Version chain exceeds maximum depth: " + maxDepth);
        }

        return Optional.empty();
    }

    /**
     * 读取版本链中的所有版本 (用于调试)
     *
     * @param startPtr 起始回滚指针
     * @return 版本列表 (从新到旧)
     */
    public List<RecordVersion> readAllVersions(RollbackPointer startPtr) {
        List<RecordVersion> versions = new ArrayList<>();

        if (startPtr == null || startPtr.isNull()) {
            return versions;
        }

        RollbackPointer currentPtr = startPtr;
        int depth = 0;

        while (!currentPtr.isNull() && depth < maxDepth) {
            depth++;

            UndoRecord undoRecord = undoReader.read(currentPtr);
            if (undoRecord == null) {
                break;
            }

            versions.add(createVersion(undoRecord));

            // INSERT Undo 是版本链终点
            if (undoRecord instanceof InsertUndoRecord) {
                break;
            }

            currentPtr = undoRecord.getPrevUndoPtr();
        }

        return versions;
    }

    /**
     * 获取版本链深度
     *
     * @param startPtr 起始回滚指针
     * @return 版本链深度
     */
    public int getChainDepth(RollbackPointer startPtr) {
        if (startPtr == null || startPtr.isNull()) {
            return 0;
        }

        RollbackPointer currentPtr = startPtr;
        int depth = 0;

        while (!currentPtr.isNull() && depth < maxDepth) {
            depth++;

            UndoRecord undoRecord = undoReader.read(currentPtr);
            if (undoRecord == null) {
                break;
            }

            if (undoRecord instanceof InsertUndoRecord) {
                break;
            }

            currentPtr = undoRecord.getPrevUndoPtr();
        }

        return depth;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 Undo 记录创建版本
     *
     * @param undoRecord Undo 记录
     * @return 记录版本
     */
    private RecordVersion createVersion(UndoRecord undoRecord) {
        if (undoRecord instanceof UpdateUndoRecord updateUndo) {
            return RecordVersion.fromUpdateUndo(updateUndo);
        } else if (undoRecord instanceof InsertUndoRecord insertUndo) {
            return RecordVersion.createInsertOrigin(
                    insertUndo.getTrxId(),
                    insertUndo.getTableId(),
                    insertUndo.getPrimaryKeyData());
        } else if (undoRecord instanceof DeleteUndoRecord deleteUndo) {
            return RecordVersion.createDeleteMarked(
                    deleteUndo.getTrxId(),
                    deleteUndo.getTableId(),
                    deleteUndo.getPrevUndoPtr(),
                    deleteUndo.getPrimaryKeyData());
        }

        throw new IllegalStateException("Unknown UndoRecord type: " + undoRecord.getClass());
    }

    // ==================== Undo 记录读取器接口 ====================

    /**
     * Undo 记录读取器接口
     *
     * <p>抽象 Undo 记录的读取操作，支持不同的实现：
     * <ul>
     *   <li>基于 BufferPool 的实现（生产环境）</li>
     *   <li>基于内存的实现（测试）</li>
     * </ul>
     * </p>
     */
    @FunctionalInterface
    public interface UndoRecordReader {
        /**
         * 读取指定位置的 Undo 记录
         *
         * @param rollPtr 回滚指针
         * @return Undo 记录，如果不存在返回 null
         */
        UndoRecord read(RollbackPointer rollPtr);
    }

    // ==================== 异常类 ====================

    /**
     * 版本链异常
     */
    public static class VersionChainException extends RuntimeException {
        public VersionChainException(String message) {
            super(message);
        }

        public VersionChainException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
