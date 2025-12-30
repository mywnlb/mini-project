package cn.zhangyis.minidb.common.exception;

/**
 * 存储层异常基类
 *
 * <p>所有与存储引擎相关的异常的基类，包括磁盘 I/O、页面损坏、空间不足等。</p>
 *
 * <h2>错误码范围</h2>
 * <p>10xxxx - 存储层错误</p>
 * <ul>
 *   <li>1001xx - 磁盘 I/O 错误</li>
 *   <li>1002xx - 页面错误</li>
 *   <li>1003xx - 空间管理错误</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class StorageException extends MiniDbException {

    /** 模块代码：存储层 */
    public static final int MODULE_CODE = 10;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(int errorCode, String message) {
        super(errorCode, message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
