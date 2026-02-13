package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.common.exception.MiniDbException;

/**
 * 锁异常基类
 *
 * <p>所有锁相关异常的基类。错误码模块前缀: 40 (Transaction System)。</p>
 *
 * @see DeadlockException
 * @see LockWaitTimeoutException
 */
public class LockException extends MiniDbException {

    public LockException(int errorCode, String message) {
        super(errorCode, message);
    }

    public LockException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
