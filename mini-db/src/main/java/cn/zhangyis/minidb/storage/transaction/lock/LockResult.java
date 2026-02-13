package cn.zhangyis.minidb.storage.transaction.lock;

/**
 * 锁操作结果
 *
 * <p>表示一次加锁操作的结果。</p>
 */
public enum LockResult {

    /** 锁已授予 */
    GRANTED,

    /** 需要等待（锁不兼容，已加入等待队列） */
    WAIT,

    /** 检测到死锁 */
    DEADLOCK;
}
