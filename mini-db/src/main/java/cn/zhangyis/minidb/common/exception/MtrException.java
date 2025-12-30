package cn.zhangyis.minidb.common.exception;

/**
 * Mini-Transaction 异常基类
 *
 * <p>所有与 MTR 相关的异常的基类。</p>
 *
 * <h2>错误码范围</h2>
 * <p>30xxxx - MTR 错误</p>
 * <ul>
 *   <li>3001xx - MTR 状态错误</li>
 *   <li>3002xx - 页面管理错误</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class MtrException extends MiniDbException {

    /** 模块代码：Mini-Transaction */
    public static final int MODULE_CODE = 30;

    public MtrException(String message) {
        super(message);
    }

    public MtrException(int errorCode, String message) {
        super(errorCode, message);
    }

    public MtrException(String message, Throwable cause) {
        super(message, cause);
    }

    public MtrException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
