package cn.zhangyis.minidb.common.exception;

/**
 * 磁盘 I/O 异常
 *
 * <p>表示磁盘读写操作失败，如文件不存在、权限不足、磁盘满、I/O 错误等。</p>
 *
 * <h2>错误码</h2>
 * <ul>
 *   <li>100101 - 读取页面失败（EOF）</li>
 *   <li>100102 - 写入页面失败</li>
 *   <li>100103 - 文件打开失败</li>
 *   <li>100104 - 文件同步失败</li>
 *   <li>100105 - 文件创建失败</li>
 *   <li>100106 - 文件删除失败</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 读取失败
 * throw new DiskIOException(100101,
 *     "Unexpected EOF reading page: " + pageNo);
 *
 * // 写入失败
 * throw new DiskIOException(100102,
 *     "Failed to write page: " + pageId, ioException);
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class DiskIOException extends StorageException {

    /** 错误码：读取页面失败（EOF） */
    public static final int ERR_READ_EOF = 100101;

    /** 错误码：写入页面失败 */
    public static final int ERR_WRITE_FAILED = 100102;

    /** 错误码：文件打开失败 */
    public static final int ERR_FILE_OPEN = 100103;

    /** 错误码：文件同步失败 */
    public static final int ERR_FILE_SYNC = 100104;

    /** 错误码：文件创建失败 */
    public static final int ERR_FILE_CREATE = 100105;

    /** 错误码：文件删除失败 */
    public static final int ERR_FILE_DELETE = 100106;

    /** 错误码：未知错误 */
    public static final int ERR_UNKNOWN = 100107;

    public DiskIOException(String message) {
        super(message);
    }

    public DiskIOException(int errorCode, String message) {
        super(errorCode, message);
    }

    public DiskIOException(String message, Throwable cause) {
        super(message, cause);
    }

    public DiskIOException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * 创建读取 EOF 异常
     *
     * @param pageNo 页号
     * @return DiskIOException
     */
    public static DiskIOException readEOF(int pageNo) {
        return new DiskIOException(ERR_READ_EOF,
            "Unexpected EOF reading page: " + pageNo);
    }

    /**
     * 创建写入失败异常
     *
     * @param pageNo 页号
     * @param cause  原始异常
     * @return DiskIOException
     */
    public static DiskIOException writeFailed(int pageNo, Throwable cause) {
        return new DiskIOException(ERR_WRITE_FAILED,
            "Failed to write page: " + pageNo, cause);
    }

    /**
     * 创建文件打开失败异常
     *
     * @param fileName 文件名
     * @param cause    原始异常
     * @return DiskIOException
     */
    public static DiskIOException fileOpenFailed(String fileName, Throwable cause) {
        return new DiskIOException(ERR_FILE_OPEN,
            "Failed to open file: " + fileName, cause);
    }
}
