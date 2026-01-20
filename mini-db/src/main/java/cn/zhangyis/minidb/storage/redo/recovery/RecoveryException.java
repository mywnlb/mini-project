package cn.zhangyis.minidb.storage.redo.recovery;

/**
 * Recovery Exception - 崩溃恢复异常
 *
 * <p>在崩溃恢复过程中发生的异常。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RecoveryException extends Exception {

    public RecoveryException(String message) {
        super(message);
    }

    public RecoveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public RecoveryException(Throwable cause) {
        super(cause);
    }
}
