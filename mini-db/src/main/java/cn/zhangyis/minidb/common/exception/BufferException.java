package cn.zhangyis.minidb.common.exception;

/**
 * Buffer Pool 异常基类
 *
 * <p>所有与 Buffer Pool 相关的异常的基类。</p>
 *
 * <h2>错误码范围</h2>
 * <p>20xxxx - Buffer Pool 错误</p>
 * <ul>
 *   <li>2001xx - 缓冲池耗尽</li>
 *   <li>2002xx - 页面固定/释放错误</li>
 *   <li>2003xx - 页面查找错误</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BufferException extends MiniDbException {

    /** 模块代码：Buffer Pool */
    public static final int MODULE_CODE = 20;

    public BufferException(String message) {
        super(message);
    }

    public BufferException(int errorCode, String message) {
        super(errorCode, message);
    }

    public BufferException(String message, Throwable cause) {
        super(message, cause);
    }

    public BufferException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
