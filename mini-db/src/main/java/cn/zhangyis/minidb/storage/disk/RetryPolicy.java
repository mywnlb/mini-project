package cn.zhangyis.minidb.storage.disk;

import java.time.Duration;

/**
 * I/O 操作重试策略
 *
 * <p>定义磁盘 I/O 操作失败时的重试行为，包括重试次数、延迟策略和超时控制。
 * 用于处理瞬态 I/O 错误，提升系统在网络存储、云盘等不稳定环境下的可靠性。</p>
 *
 * <h2>设计原理</h2>
 * <p>参考 AWS SDK 和 Google Cloud 的重试最佳实践：</p>
 * <pre>
 * 重试次数: 3-5次 (过多影响延迟，过少无法应对瞬态错误)
 * 延迟策略: 指数退避 + 抖动 (避免雷鸣效应)
 * 重试条件: 仅瞬态错误 (永久错误不重试)
 * 超时控制: 单次和总体超时
 * </pre>
 *
 * <h2>延迟计算公式</h2>
 * <pre>
 * baseDelay = initialDelayMs * (2 ^ attemptNumber)
 * actualDelay = baseDelay * (0.5 + random(0, 0.5))  // 抖动 50%-100%
 * cappedDelay = min(actualDelay, maxDelayMs)
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.builder()
 *     .maxAttempts(3)
 *     .initialDelay(Duration.ofMillis(100))
 *     .maxDelay(Duration.ofSeconds(5))
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RetryPolicy {

    /**
     * 默认最大重试次数
     * <p>参考 InnoDB: 磁盘 I/O 默认重试 3 次</p>
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * 默认初始延迟 (100ms)
     * <p>足够短以保持延迟，足够长以避免CPU空转</p>
     */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(100);

    /**
     * 默认最大延迟 (5s)
     * <p>避免单次重试等待过久</p>
     */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(5);

    /**
     * 默认总超时 (30s)
     * <p>参考 MySQL innodb_io_capacity timeout</p>
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    // ==================== 字段 ====================

    /**
     * 最大重试次数 (包含首次尝试)
     * <p>例如: maxAttempts=3 表示 1次首次尝试 + 2次重试</p>
     */
    private final int maxAttempts;

    /**
     * 初始延迟
     * <p>第一次重试的基础延迟时间</p>
     */
    private final Duration initialDelay;

    /**
     * 最大延迟
     * <p>指数退避的上限</p>
     */
    private final Duration maxDelay;

    /**
     * 总超时
     * <p>所有重试的总时间限制</p>
     */
    private final Duration timeout;

    /**
     * 是否启用抖动
     * <p>默认开启，避免雷鸣效应 (thundering herd)</p>
     */
    private final boolean jitterEnabled;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数 (使用 Builder 创建)
     */
    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.timeout = builder.timeout;
        this.jitterEnabled = builder.jitterEnabled;
    }

    /**
     * 创建默认重试策略
     *
     * <p>配置:
     * <ul>
     *   <li>maxAttempts: 3</li>
     *   <li>initialDelay: 100ms</li>
     *   <li>maxDelay: 5s</li>
     *   <li>timeout: 30s</li>
     *   <li>jitter: enabled</li>
     * </ul>
     * </p>
     *
     * @return 默认重试策略
     */
    public static RetryPolicy defaultPolicy() {
        return builder().build();
    }

    /**
     * 创建 Builder
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Getters ====================

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public boolean isJitterEnabled() {
        return jitterEnabled;
    }

    // ==================== 核心方法 ====================

    /**
     * 计算第 N 次重试的延迟时间
     *
     * <p>使用指数退避 + 抖动策略：</p>
     * <pre>
     * attempt=1: 100ms * (0.5-1.0 random) = 50-100ms
     * attempt=2: 200ms * (0.5-1.0 random) = 100-200ms
     * attempt=3: 400ms * (0.5-1.0 random) = 200-400ms
     * </pre>
     *
     * @param attemptNumber 重试次数 (从 1 开始)
     * @return 延迟时长
     */
    public Duration calculateDelay(int attemptNumber) {
        // 指数退避: initialDelay * (2 ^ attemptNumber)
        long baseDelayMs = initialDelay.toMillis() * (1L << attemptNumber);

        // 限制最大延迟
        long cappedDelayMs = Math.min(baseDelayMs, maxDelay.toMillis());

        // 添加抖动 (50%-100% 范围)
        if (jitterEnabled) {
            double jitterFactor = 0.5 + Math.random() * 0.5;
            cappedDelayMs = (long) (cappedDelayMs * jitterFactor);
        }

        return Duration.ofMillis(cappedDelayMs);
    }

    /**
     * 检查是否应该继续重试
     *
     * @param attemptNumber 当前尝试次数 (从 1 开始)
     * @param elapsedTime   已消耗的总时间
     * @return 如果应该重试返回 true
     */
    public boolean shouldRetry(int attemptNumber, Duration elapsedTime) {
        // 达到最大重试次数
        if (attemptNumber >= maxAttempts) {
            return false;
        }

        // 超过总超时
        if (elapsedTime.compareTo(timeout) >= 0) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return "RetryPolicy{" +
            "maxAttempts=" + maxAttempts +
            ", initialDelay=" + initialDelay.toMillis() + "ms" +
            ", maxDelay=" + maxDelay.toMillis() + "ms" +
            ", timeout=" + timeout.toMillis() + "ms" +
            ", jitter=" + jitterEnabled +
            '}';
    }

    // ==================== Builder ====================

    /**
     * RetryPolicy 构建器
     */
    public static class Builder {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration initialDelay = DEFAULT_INITIAL_DELAY;
        private Duration maxDelay = DEFAULT_MAX_DELAY;
        private Duration timeout = DEFAULT_TIMEOUT;
        private boolean jitterEnabled = true;

        /**
         * 设置最大重试次数
         *
         * @param maxAttempts 最大尝试次数 (≥ 1)
         * @return Builder
         */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * 设置初始延迟
         *
         * @param initialDelay 初始延迟 (> 0)
         * @return Builder
         */
        public Builder initialDelay(Duration initialDelay) {
            if (initialDelay.isNegative() || initialDelay.isZero()) {
                throw new IllegalArgumentException("initialDelay must be positive");
            }
            this.initialDelay = initialDelay;
            return this;
        }

        /**
         * 设置最大延迟
         *
         * @param maxDelay 最大延迟 (> 0)
         * @return Builder
         */
        public Builder maxDelay(Duration maxDelay) {
            if (maxDelay.isNegative() || maxDelay.isZero()) {
                throw new IllegalArgumentException("maxDelay must be positive");
            }
            this.maxDelay = maxDelay;
            return this;
        }

        /**
         * 设置总超时
         *
         * @param timeout 总超时 (> 0)
         * @return Builder
         */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * 设置是否启用抖动
         *
         * @param jitterEnabled 是否启用抖动
         * @return Builder
         */
        public Builder jitterEnabled(boolean jitterEnabled) {
            this.jitterEnabled = jitterEnabled;
            return this;
        }

        /**
         * 构建 RetryPolicy
         *
         * @return RetryPolicy 实例
         */
        public RetryPolicy build() {
            // 验证: maxDelay >= initialDelay
            if (maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("maxDelay must be >= initialDelay");
            }

            return new RetryPolicy(this);
        }
    }
}
