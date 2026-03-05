package cn.zhangyis.minidb.storage.transaction.core;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecord;

/**
 * Undo 记录应用器接口
 *
 * <p>TransactionManager 在回滚事务时，通过此接口将 Undo 记录应用到数据页。
 * 具体实现由上层（如 TransactionalDml）提供，因为 TransactionManager
 * 本身不持有 B+Tree 等数据访问组件的引用。</p>
 *
 * <h2>回滚语义</h2>
 * <ul>
 *   <li><b>INSERT Undo</b>: 从 B+Tree 物理删除记录</li>
 *   <li><b>UPDATE Undo</b>: 恢复旧列值、旧 TRX_ID / ROLL_PTR</li>
 *   <li><b>DELETE Undo</b>: 清除 delete_flag，恢复行可见性</li>
 * </ul>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>I-RB1</b>: 每个 applyUndo 调用内部使用独立 MTR，保证原子性</li>
 *   <li><b>I-RB2</b>: 调用者（TransactionManager）保证 Undo 按逆序传入</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@FunctionalInterface
public interface UndoApplier {

    /**
     * 应用单条 Undo 记录以回滚数据修改
     *
     * <p>实现必须在内部创建 MTR 并提交，保证操作的原子性。
     * 如果应用失败，抛出 MiniDbException。</p>
     *
     * @param trx        正在回滚的事务
     * @param undoRecord 要应用的 Undo 记录
     * @throws MiniDbException 如果应用失败
     */
    void applyUndo(Transaction trx, UndoRecord undoRecord) throws MiniDbException;
}
