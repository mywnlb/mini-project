package cn.zhangyis.minidb.storage.disk;

import cn.zhangyis.minidb.common.exception.DiskIOException;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryHelper 单元测试
 *
 * @author MiniDB
 * @version 1.0
 */
class RetryHelperTest {

    @Test
    void testExecuteWithRetry_SuccessFirstAttempt() throws DiskIOException {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        AtomicInteger attempts = new AtomicInteger(0);

        String result = RetryHelper.executeWithRetry(
            () -> {
                attempts.incrementAndGet();
                return "success";
            },
            policy,
            "testOp",
            "context1"
        );

        assertEquals("success", result);
        assertEquals(1, attempts.get()); // 仅尝试一次
    }

    @Test
    void testExecuteWithRetry_SuccessAfterRetries() throws DiskIOException {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(10))
            .build();

        AtomicInteger attempts = new AtomicInteger(0);

        String result = RetryHelper.executeWithRetry(
            () -> {
                int attempt = attempts.incrementAndGet();
                if (attempt < 3) {
                    throw new IOException("Transient error " + attempt);
                }
                return "success";
            },
            policy,
            "testOp",
            "context1"
        );

        assertEquals("success", result);
        assertEquals(3, attempts.get()); // 前2次失败，第3次成功
    }

    @Test
    void testExecuteWithRetry_PermanentError_FileNotFound() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        AtomicInteger attempts = new AtomicInteger(0);

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new FileNotFoundException("file.txt not found");
                },
                policy,
                "testOp",
                "context1"
            )
        );

        // 永久错误不重试
        assertEquals(1, attempts.get());
        assertTrue(exception.getMessage().contains("1 attempts"));
        assertEquals(DiskIOException.ERR_FILE_OPEN, exception.getErrorCode());
    }

    @Test
    void testExecuteWithRetry_PermanentError_NoSuchFile() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        AtomicInteger attempts = new AtomicInteger(0);

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new NoSuchFileException("path/to/file");
                },
                policy,
                "testOp",
                "context1"
            )
        );

        // 永久错误不重试
        assertEquals(1, attempts.get());
        assertEquals(DiskIOException.ERR_FILE_OPEN, exception.getErrorCode());
    }

    @Test
    void testExecuteWithRetry_PermanentError_AccessDenied() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        AtomicInteger attempts = new AtomicInteger(0);

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new AccessDeniedException("permission denied");
                },
                policy,
                "testOp",
                "context1"
            )
        );

        // 永久错误不重试
        assertEquals(1, attempts.get());
    }

    @Test
    void testExecuteWithRetry_TransientError_RetriesExhausted() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(10))
            .maxDelay(Duration.ofMillis(50))
            .build();

        AtomicInteger attempts = new AtomicInteger(0);

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new IOException("Transient error");
                },
                policy,
                "testOp",
                "context1"
            )
        );

        // 重试3次全部失败
        assertEquals(3, attempts.get());
        assertTrue(exception.getMessage().contains("3 attempts"));
    }

    @Test
    void testExecuteWithRetry_TransientError_ClosedChannel() throws DiskIOException {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(2)
            .initialDelay(Duration.ofMillis(10))
            .build();

        AtomicInteger attempts = new AtomicInteger(0);

        String result = RetryHelper.executeWithRetry(
            () -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    throw new ClosedChannelException(); // 瞬态错误，可重试
                }
                return "recovered";
            },
            policy,
            "testOp",
            "context1"
        );

        assertEquals("recovered", result);
        assertEquals(2, attempts.get()); // 第1次失败，第2次成功
    }

    @Test
    void testExecuteWithRetry_Timeout() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(10)
            .initialDelay(Duration.ofMillis(100))
            .timeout(Duration.ofMillis(150)) // 很短的超时
            .build();

        AtomicInteger attempts = new AtomicInteger(0);

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new IOException("Slow operation");
                },
                policy,
                "testOp",
                "context1"
            )
        );

        // 由于超时，重试次数应该少于 maxAttempts
        assertTrue(attempts.get() < 10);
        assertTrue(exception.getMessage().contains("attempts"));
    }

    @Test
    void testExecuteWithRetry_UnexpectedException() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        AtomicInteger attempts = new AtomicInteger(0);

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("Unexpected runtime exception");
                },
                policy,
                "testOp",
                "context1"
            )
        );

        // 非 IOException 不重试
        assertEquals(1, attempts.get());
        assertTrue(exception.getMessage().contains("Unexpected error"));
        assertEquals(DiskIOException.ERR_UNKNOWN, exception.getErrorCode());
    }

    @Test
    void testExecuteWithRetry_VoidOperation() throws DiskIOException {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(10))
            .build();

        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        RetryHelper.executeWithRetry(
            () -> {
                int attempt = attempts.incrementAndGet();
                if (attempt < 2) {
                    throw new IOException("Transient error");
                }
                successCount.incrementAndGet();
                return null; // Void operation
            },
            policy,
            "voidOp",
            "context1"
        );

        assertEquals(2, attempts.get()); // 第1次失败，第2次成功
        assertEquals(1, successCount.get());
    }

    @Test
    void testExecuteWithRetry_ErrorCodeMapping_Read() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    throw new IOException("Failed to read data");
                },
                policy,
                "readOp",
                "pageId=123"
            )
        );

        assertEquals(DiskIOException.ERR_READ_EOF, exception.getErrorCode());
    }

    @Test
    void testExecuteWithRetry_ErrorCodeMapping_Write() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    throw new IOException("Failed to write data");
                },
                policy,
                "writeOp",
                "pageId=456"
            )
        );

        assertEquals(DiskIOException.ERR_WRITE_FAILED, exception.getErrorCode());
    }

    @Test
    void testExecuteWithRetry_ContextInErrorMessage() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        DiskIOException exception = assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    throw new IOException("Error");
                },
                policy,
                "myOperation",
                "myContext=abc"
            )
        );

        assertTrue(exception.getMessage().contains("myOperation"));
        assertTrue(exception.getMessage().contains("myContext=abc"));
    }

    @Test
    void testExecuteWithRetry_RetryDelay() {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(50))
            .jitterEnabled(false)
            .build();

        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        assertThrows(DiskIOException.class, () ->
            RetryHelper.executeWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new IOException("Always fail");
                },
                policy,
                "testOp",
                "context1"
            )
        );

        long elapsedTime = System.currentTimeMillis() - startTime;

        // 预期延迟: 100ms (第1次重试) + 200ms (第2次重试) = 300ms
        // 允许一定误差 (±100ms)
        assertTrue(elapsedTime >= 200, "elapsed=" + elapsedTime);
        assertTrue(elapsedTime <= 500, "elapsed=" + elapsedTime);
    }
}
