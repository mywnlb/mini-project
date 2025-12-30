package cn.zhangyis.minidb.common.exception;

/**
 * MiniDB 异常基类
 *
 * <p>所有 MiniDB 自定义异常的基类。提供统一的异常处理机制和错误码支持。</p>
 *
 * <h2>异常层次结构</h2>
 * <pre>
 * MiniDbException (基类)
 *   ├── StorageException (存储层异常)
 *   │   ├── DiskIOException
 *   │   ├── PageCorruptedException
 *   │   └── SpaceExhaustedException
 *   │
 *   ├── BufferException (Buffer Pool 异常)
 *   │   ├── BufferExhaustedException
 *   │   ├── PagePinnedException
 *   │   └── PageNotFoundException
 *   │
 *   ├── MtrException (Mini-Transaction 异常)
 *   │   ├── MtrStateException
 *   │   └── PageNotManagedByMtrException
 *   │
 *   ├── TransactionException (事务异常 - Future)
 *   │   ├── DeadlockException
 *   │   ├── LockTimeoutException
 *   │   └── TransactionAbortedException
 *   │
 *   └── SqlException (SQL 异常 - Future)
 *       ├── ParseException
 *       ├── SemanticException
 *       └── ExecutionException
 * </pre>
 *
 * <h2>错误码设计</h2>
 * <p>错误码采用分层设计，格式为：模块代码(2位) + 错误类型(2位) + 序号(2位)</p>
 * <pre>
 * 模块代码：
 *   10 - Storage Layer
 *   20 - Buffer Pool
 *   30 - Mini-Transaction
 *   40 - Transaction System
 *   50 - SQL Layer
 *   60 - Execution Engine
 *
 * 示例：
 *   101001 - Storage: Disk I/O error #1
 *   201001 - Buffer: Buffer pool exhausted #1
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 抛出异常
 * throw new DiskIOException("Failed to read page: " + pageNo, e);
 *
 * // 捕获异常
 * try {
 *     page = diskManager.readPage(pageId);
 * } catch (StorageException e) {
 *     log.error("Storage error: code={}, message={}", e.getErrorCode(), e.getMessage());
 *     // 处理存储层错误
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class MiniDbException extends Exception {

    /**
     * 错误码
     * <p>用于程序化处理异常和国际化支持</p>
     */
    private final int errorCode;

    /**
     * 创建异常（无错误码）
     *
     * @param message 错误消息
     */
    public MiniDbException(String message) {
        super(message);
        this.errorCode = 0;
    }

    /**
     * 创建异常（带错误码）
     *
     * @param errorCode 错误码
     * @param message   错误消息
     */
    public MiniDbException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 创建异常（带原因）
     *
     * @param message 错误消息
     * @param cause   原始异常
     */
    public MiniDbException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 0;
    }

    /**
     * 创建异常（带错误码和原因）
     *
     * @param errorCode 错误码
     * @param message   错误消息
     * @param cause     原始异常
     */
    public MiniDbException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码
     *
     * @return 错误码，0 表示未设置
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * 获取模块代码
     *
     * @return 模块代码（错误码的前两位）
     */
    public int getModuleCode() {
        return errorCode / 10000;
    }

    /**
     * 获取错误类型
     *
     * @return 错误类型（错误码的中间两位）
     */
    public int getErrorType() {
        return (errorCode / 100) % 100;
    }

    @Override
    public String toString() {
        if (errorCode != 0) {
            return String.format("[%06d] %s", errorCode, getMessage());
        }
        return super.toString();
    }
}
