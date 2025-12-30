package cn.zhangyis.minidb.common.exception;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;

/**
 * MTR 状态异常
 *
 * <p>表示在错误的 MTR 状态下执行了操作。</p>
 *
 * <h2>错误码</h2>
 * <ul>
 *   <li>300101 - MTR 已提交，不能继续操作</li>
 *   <li>300102 - MTR 已中止，不能继续操作</li>
 *   <li>300103 - MTR 未激活</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * if (state != State.ACTIVE) {
 *     throw MtrStateException.notActive(state);
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class MtrStateException extends MtrException {

    /** 错误码：MTR 已提交 */
    public static final int ERR_ALREADY_COMMITTED = 300101;

    /** 错误码：MTR 已中止 */
    public static final int ERR_ALREADY_ABORTED = 300102;

    /** 错误码：MTR 未激活 */
    public static final int ERR_NOT_ACTIVE = 300103;

    public MtrStateException(String message) {
        super(message);
    }

    public MtrStateException(int errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * 创建 MTR 未激活异常
     *
     * @param currentState 当前状态
     * @return MtrStateException
     */
    public static MtrStateException notActive(MiniTransaction.State currentState) {
        int errorCode = switch (currentState) {
            case COMMITTED -> ERR_ALREADY_COMMITTED;
            case ABORTED -> ERR_ALREADY_ABORTED;
            default -> ERR_NOT_ACTIVE;
        };

        return new MtrStateException(errorCode,
            "MTR is in " + currentState + " state, cannot perform operations. " +
            "Create a new MTR for new operations.");
    }
}
