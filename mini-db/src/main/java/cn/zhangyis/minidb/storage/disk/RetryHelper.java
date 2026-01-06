package cn.zhangyis.minidb.storage.disk;

import cn.zhangyis.minidb.common.exception.DiskIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * I/O 操作重试辅助工具
 *
 * <p>提供统一的 I/O 重试封装，自动处理瞬态错误、延迟退避和日志记录。
 * 用于提升磁盘 I/O 在网络存储、云盘等不稳定环境下的可靠性。</p>
 *
 * <h2>设计原理</h2>
 * <pre>
 * 1. 瞬态错误 (Transient): 重试可能成功
 *    - IOException (通用 I/O 错误)
 *    - ClosedChannelException (通道意外关闭)
 *    - SocketTimeoutException (网络存储超时)
 *
 * 2. 永久错误 (Permanent): 重试无意义，立即失败
 *    - FileNotFoundException (文件不存在)
 *    - AccessDeniedException (权限不足)
 *    - NoSuchFileException (路径无效)
 * </pre>
 *
 * <h2>重试流程</h2>
 * <pre>
 * 1. 执行操作
 * 2. 捕获 IOException
 * 3. 判断是否为瞬态错误
 *    - 永久错误 → 立即抛出
 *    - 瞬态错误 → 检查重试条件
 * 4. 记录 WARN 日志
 * 5. 计算退避延迟
 * 6. Sleep
 * 7. 重试 → 回到步骤 1
 * 8. 达到最大重试 → 抛出异常
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 示例 1: 重试读取操作
 * ByteBuffer data = RetryHelper.executeWithRetry(
 *     () -> tsFile.readPage(pageNo),
 *     RetryPolicy.defaultPolicy(),
 *     "readPage",
 *     pageId
 * );
 *
 * // 示例 2: 重试写入操作
 * RetryHelper.executeWithRetry(
 *     () -> {
 *         tsFile.writePage(pageNo, data);
 *         return null;
 *     },
 *     policy,
 *     "writePage",
 *     pageId
 * );
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RetryHelper {

    private static final Logger logger = LoggerFactory.getLogger(RetryHelper.class);

    /**
     * 执行可能失败的 I/O 操作，失败时自动重试
     *
     * <p>重试条件：</p>
     * <ul>
     *   <li>捕获 IOException (瞬态错误)</li>
     *   <li>未达到最大重试次数</li>
     *   <li>未超过总超时</li>
     * </ul>
     *
     * @param operation    要执行的操作
     * @param policy       重试策略
     * @param operationName 操作名称 (用于日志)
     * @param context      操作上下文 (用于日志，如 pageId)
     * @param <T>          返回类型
     * @return 操作结果
     * @throws DiskIOException 如果所有重试都失败
     */
    public static <T> T executeWithRetry(
        Callable<T> operation,
        RetryPolicy policy,
        String operationName,
        Object context
    ) throws DiskIOException {
        long startTime = System.nanoTime();
        int attemptNumber = 0;
        IOException lastException = null;

        while (true) {
            attemptNumber++;

            try {
                // 尝试执行操作
                T result = operation.call();

                // 成功: 如果之前失败过，记录恢复日志
                if (attemptNumber > 1) {
                    logger.info("I/O operation succeeded after {} attempts: operation={}, context={}, totalTime={}ms",
                        attemptNumber, operationName, context, elapsedMillis(startTime));
                }

                return result;

            } catch (IOException e) {
                lastException = e;

                // 检查是否为永久错误 (不重试)
                if (isPermanentError(e)) {
                    logger.error("I/O operation failed with permanent error: operation={}, context={}, error={}",
                        operationName, context, e.getMessage());
                    throw wrapException(operationName, context, e, attemptNumber);
                }

                // 检查是否应该重试
                Duration elapsedTime = Duration.ofNanos(System.nanoTime() - startTime);
                if (!policy.shouldRetry(attemptNumber, elapsedTime)) {
                    // 达到最大重试次数或超时
                    logger.error("I/O operation exhausted all retries: operation={}, context={}, attempts={}, totalTime={}ms, lastError={}",
                        operationName, context, attemptNumber, elapsedTime.toMillis(), e.getMessage());
                    throw wrapException(operationName, context, e, attemptNumber);
                }

                // 记录重试日志
                Duration delay = policy.calculateDelay(attemptNumber);
                logger.warn("I/O operation failed (will retry): operation={}, context={}, attempt={}/{}, nextRetryIn={}ms, error={}",
                    operationName, context, attemptNumber, policy.getMaxAttempts(), delay.toMillis(), e.getMessage());

                // 退避延迟
                sleep(delay);

            } catch (Exception e) {
                // 非 IOException: 不重试，直接包装抛出
                logger.error("I/O operation failed with unexpected exception: operation={}, context={}, error={}",
                    operationName, context, e.getMessage(), e);
                throw new DiskIOException(DiskIOException.ERR_READ_EOF,
                    "Unexpected error during " + operationName + ": " + context, e);
            }
        }
    }

    /**
     * 检查是否为永久错误 (不可重试)
     *
     * <p>永久错误类型：</p>
     * <ul>
     *   <li>FileNotFoundException: 文件不存在</li>
     *   <li>NoSuchFileException: 路径无效</li>
     *   <li>AccessDeniedException: 权限不足</li>
     * </ul>
     *
     * @param e IOException 实例
     * @return 如果是永久错误返回 true
     */
    private static boolean isPermanentError(IOException e) {
        // 文件不存在: 重试无意义
        if (e instanceof FileNotFoundException || e instanceof NoSuchFileException) {
            return true;
        }

        // 权限错误: 重试无意义
        if (e instanceof AccessDeniedException) {
            return true;
        }

        // 其他 IOException: 视为瞬态错误，可以重试
        return false;
    }

    /**
     * 包装 IOException 为 DiskIOException
     *
     * @param operationName 操作名称
     * @param context       操作上下文
     * @param cause         原始异常
     * @param attempts      尝试次数
     * @return DiskIOException
     */
    private static DiskIOException wrapException(
        String operationName,
        Object context,
        IOException cause,
        int attempts
    ) {
        String message = String.format(
            "I/O operation failed after %d attempts: operation=%s, context=%s",
            attempts, operationName, context
        );

        // 根据异常类型选择错误码
        int errorCode;
        if (cause instanceof FileNotFoundException || cause instanceof NoSuchFileException) {
            errorCode = DiskIOException.ERR_FILE_OPEN;
        } else if (cause.getMessage() != null && cause.getMessage().contains("read")) {
            errorCode = DiskIOException.ERR_READ_EOF;
        } else if (cause.getMessage() != null && cause.getMessage().contains("write")) {
            errorCode = DiskIOException.ERR_WRITE_FAILED;
        } else {
            errorCode = DiskIOException.ERR_READ_EOF;
        }

        return new DiskIOException(errorCode, message, cause);
    }

    /**
     * 安全的 sleep (忽略中断)
     *
     * @param duration 睡眠时长
     */
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            // 恢复中断状态，但不抛出异常 (重试逻辑会继续)
            Thread.currentThread().interrupt();
            logger.debug("Sleep interrupted during I/O retry backoff");
        }
    }

    /**
     * 计算自起始时间以来的毫秒数
     *
     * @param startNanos 起始时间 (纳秒)
     * @return 毫秒数
     */
    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
