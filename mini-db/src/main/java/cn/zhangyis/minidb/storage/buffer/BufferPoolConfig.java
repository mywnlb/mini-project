package cn.zhangyis.minidb.storage.buffer;

import cn.zhangyis.minidb.storage.disk.RetryPolicy;

import java.time.Duration;

/**
 * BufferPool 配置类
 *
 * <p>提供 BufferPool 所有可配置参数的集中管理，支持运行时调优和环境适配。</p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>不可变对象</b>: 配置创建后不可修改，保证线程安全</li>
 *   <li><b>Builder 模式</b>: 支持链式调用和部分配置</li>
 *   <li><b>验证逻辑</b>: 构建时检查参数合法性</li>
 *   <li><b>默认值</b>: 提供生产环境最佳实践默认值</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 使用默认配置
 * BufferPoolConfig config = BufferPoolConfig.defaultConfig(1000);
 *
 * // 自定义配置
 * BufferPoolConfig config = BufferPoolConfig.builder()
 *     .poolSize(2000)
 *     .segmentCount(128)
 *     .lruReorderInterval(Duration.ofMillis(50))
 *     .oldBlockRatio(0.4)
 *     .build();
 *
 * // 创建 BufferPool
 * BufferPool pool = new BufferPool(config, diskManager);
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BufferPoolConfig {

    // ==================== 核心参数 ====================

    /**
     * Buffer Pool 大小 (页数)
     *
     * <p>决定可缓存的数据页数量，直接影响缓存命中率。</p>
     *
     * <h3>调优建议</h3>
     * <ul>
     *   <li><b>开发环境</b>: 100-500 页 (~1.6-8MB)</li>
     *   <li><b>测试环境</b>: 1000-5000 页 (~16-80MB)</li>
     *   <li><b>生产环境</b>: 10000-100000 页 (~160MB-1.6GB)</li>
     * </ul>
     *
     * <h3>计算公式</h3>
     * <pre>
     * poolSize = 可用内存 * 0.7 / 16KB
     * 例如: 8GB 内存 → 8GB * 0.7 / 16KB ≈ 358400 页
     * </pre>
     */
    private final int poolSize;

    // ==================== Page Hash 分段锁配置 ====================

    /**
     * Page Hash 分段数
     *
     * <p>将全局 pageHash 分成多个段，每个段独立加锁，降低锁竞争。
     * 必须是 2 的幂次方。</p>
     *
     * <h3>调优建议</h3>
     * <ul>
     *   <li><b>低并发 (1-8核)</b>: 16 或 32</li>
     *   <li><b>中并发 (8-32核)</b>: 64 (默认)</li>
     *   <li><b>高并发 (32-128核)</b>: 128 或 256</li>
     * </ul>
     *
     * <h3>性能影响</h3>
     * <pre>
     * segmentCount = 64 → 64 个读请求可并发
     * segmentCount = 128 → 128 个读请求可并发
     * </pre>
     *
     * <p><b>注意</b>: 过多的分段会增加内存开销。</p>
     */
    private final int segmentCount;

    // ==================== LRU 配置 ====================

    /**
     * LRU 后台重排间隔
     *
     * <p>Lock-free LRU 优化中，后台线程定期整理 LRU 链表的时间间隔。</p>
     *
     * <h3>调优建议</h3>
     * <ul>
     *   <li><b>高精度</b> (LRU precision >0.95): 50-100ms (默认 100ms)</li>
     *   <li><b>低延迟</b> (减少后台开销): 200-500ms</li>
     *   <li><b>低负载</b>: 500-1000ms</li>
     * </ul>
     *
     * <h3>权衡</h3>
     * <pre>
     * 间隔越短 → LRU 精度越高，但 CPU 开销越大
     * 间隔越长 → CPU 开销越小，但 LRU 精度降低
     * </pre>
     */
    private final Duration lruReorderInterval;

    /**
     * LRU Old 区比例 (0.0 - 1.0)
     *
     * <p>LRU 链表分为 Young 区和 Old 区，新页面插入 Old 区，
     * 避免全表扫描污染缓存。</p>
     *
     * <h3>调优建议</h3>
     * <ul>
     *   <li><b>OLTP 场景</b> (随机访问为主): 0.375 (默认，3/8)</li>
     *   <li><b>OLAP 场景</b> (扫描为主): 0.5 (增大 Old 区)</li>
     *   <li><b>混合场景</b>: 0.4</li>
     * </ul>
     */
    private final double oldBlockRatio;

    /**
     * Old 区驻留时间 (毫秒)
     *
     * <p>页面在 Old 区停留多久后才能晋升到 Young 区。
     * 防止一次性扫描的页面进入 Young 区。</p>
     *
     * <h3>调优建议</h3>
     * <ul>
     *   <li><b>OLTP</b>: 1000ms (默认，InnoDB 默认值)</li>
     *   <li><b>频繁扫描</b>: 2000-5000ms (延长驻留时间)</li>
     *   <li><b>极少扫描</b>: 500ms</li>
     * </ul>
     */
    private final long oldBlockTimeMs;

    // ==================== I/O 重试配置 ====================

    /**
     * 磁盘 I/O 重试策略
     *
     * <p>用于处理瞬态 I/O 错误，提升系统稳定性。</p>
     *
     * <h3>调优建议</h3>
     * <ul>
     *   <li><b>本地 SSD</b>: maxAttempts=2, timeout=10s</li>
     *   <li><b>网络存储</b>: maxAttempts=5, timeout=60s (默认)</li>
     *   <li><b>云盘</b>: maxAttempts=3, timeout=30s</li>
     * </ul>
     */
    private final RetryPolicy retryPolicy;

    // ==================== 默认值常量 ====================

    /** 默认分段数 (2^6 = 64) */
    public static final int DEFAULT_SEGMENT_COUNT = 64;

    /** 默认 LRU 重排间隔 (100ms) */
    public static final Duration DEFAULT_LRU_REORDER_INTERVAL = Duration.ofMillis(100);

    /** 默认 Old 区比例 (3/8 = 0.375，InnoDB 默认值) */
    public static final double DEFAULT_OLD_BLOCK_RATIO = 0.375;

    /** 默认 Old 区驻留时间 (1000ms，InnoDB 默认值) */
    public static final long DEFAULT_OLD_BLOCK_TIME_MS = 1000;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数 (使用 Builder 创建)
     */
    private BufferPoolConfig(Builder builder) {
        this.poolSize = builder.poolSize;
        this.segmentCount = builder.segmentCount;
        this.lruReorderInterval = builder.lruReorderInterval;
        this.oldBlockRatio = builder.oldBlockRatio;
        this.oldBlockTimeMs = builder.oldBlockTimeMs;
        this.retryPolicy = builder.retryPolicy;

        // 验证配置
        validate();
    }

    /**
     * 创建默认配置
     *
     * <p>使用生产环境最佳实践默认值：
     * <ul>
     *   <li>segmentCount: 64</li>
     *   <li>lruReorderInterval: 100ms</li>
     *   <li>oldBlockRatio: 0.375</li>
     *   <li>oldBlockTimeMs: 1000ms</li>
     *   <li>retryPolicy: 默认策略 (3次重试, 30s超时)</li>
     * </ul>
     * </p>
     *
     * @param poolSize Buffer Pool 大小 (页数)
     * @return 默认配置实例
     */
    public static BufferPoolConfig defaultConfig(int poolSize) {
        return builder().poolSize(poolSize).build();
    }

    /**
     * 创建 Builder
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 配置验证 ====================

    /**
     * 验证配置参数合法性
     *
     * @throws IllegalArgumentException 如果参数非法
     */
    private void validate() {
        // 验证 poolSize
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive, got: " + poolSize);
        }
        if (poolSize > 10_000_000) {
            throw new IllegalArgumentException("poolSize too large (max 10M), got: " + poolSize);
        }

        // 验证 segmentCount (必须是 2 的幂次方)
        if (segmentCount <= 0 || (segmentCount & (segmentCount - 1)) != 0) {
            throw new IllegalArgumentException(
                "segmentCount must be a power of 2, got: " + segmentCount);
        }
        if (segmentCount > 1024) {
            throw new IllegalArgumentException("segmentCount too large (max 1024), got: " + segmentCount);
        }

        // 验证 lruReorderInterval
        if (lruReorderInterval.isNegative() || lruReorderInterval.isZero()) {
            throw new IllegalArgumentException("lruReorderInterval must be positive");
        }
        if (lruReorderInterval.toMillis() > 60_000) {
            throw new IllegalArgumentException(
                "lruReorderInterval too long (max 60s), got: " + lruReorderInterval.toMillis() + "ms");
        }

        // 验证 oldBlockRatio
        if (oldBlockRatio < 0.0 || oldBlockRatio > 1.0) {
            throw new IllegalArgumentException(
                "oldBlockRatio must be in [0.0, 1.0], got: " + oldBlockRatio);
        }

        // 验证 oldBlockTimeMs
        if (oldBlockTimeMs < 0) {
            throw new IllegalArgumentException("oldBlockTimeMs must be non-negative, got: " + oldBlockTimeMs);
        }
        if (oldBlockTimeMs > 3600_000) {
            throw new IllegalArgumentException(
                "oldBlockTimeMs too long (max 1h), got: " + oldBlockTimeMs + "ms");
        }

        // retryPolicy 由 RetryPolicy.Builder 自行验证
    }

    // ==================== Getters ====================

    public int getPoolSize() {
        return poolSize;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public Duration getLruReorderInterval() {
        return lruReorderInterval;
    }

    public double getOldBlockRatio() {
        return oldBlockRatio;
    }

    public long getOldBlockTimeMs() {
        return oldBlockTimeMs;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算分段掩码
     *
     * @return segmentCount - 1 (用于 hash & mask 运算)
     */
    public int getSegmentMask() {
        return segmentCount - 1;
    }

    /**
     * 计算每个分段的初始容量
     *
     * @return (poolSize / segmentCount) + 1
     */
    public int getSegmentCapacity() {
        return (poolSize / segmentCount) + 1;
    }

    @Override
    public String toString() {
        return "BufferPoolConfig{" +
            "poolSize=" + poolSize + " pages (" + (poolSize * 16 / 1024) + " MB), " +
            "segmentCount=" + segmentCount + ", " +
            "lruReorderInterval=" + lruReorderInterval.toMillis() + "ms, " +
            "oldBlockRatio=" + oldBlockRatio + ", " +
            "oldBlockTimeMs=" + oldBlockTimeMs + "ms, " +
            "retryPolicy=" + retryPolicy +
            '}';
    }

    // ==================== Builder ====================

    /**
     * BufferPoolConfig 构建器
     */
    public static class Builder {
        private int poolSize;
        private int segmentCount = DEFAULT_SEGMENT_COUNT;
        private Duration lruReorderInterval = DEFAULT_LRU_REORDER_INTERVAL;
        private double oldBlockRatio = DEFAULT_OLD_BLOCK_RATIO;
        private long oldBlockTimeMs = DEFAULT_OLD_BLOCK_TIME_MS;
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();

        /**
         * 设置 Buffer Pool 大小
         *
         * @param poolSize 页数 (必须 > 0)
         * @return Builder
         */
        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        /**
         * 设置分段数
         *
         * <p><b>要求</b>: 必须是 2 的幂次方 (16, 32, 64, 128, 256, ...)。</p>
         *
         * @param segmentCount 分段数
         * @return Builder
         */
        public Builder segmentCount(int segmentCount) {
            this.segmentCount = segmentCount;
            return this;
        }

        /**
         * 设置 LRU 重排间隔
         *
         * @param interval 时间间隔 (推荐 50-500ms)
         * @return Builder
         */
        public Builder lruReorderInterval(Duration interval) {
            this.lruReorderInterval = interval;
            return this;
        }

        /**
         * 设置 Old 区比例
         *
         * @param ratio 比例 (0.0 - 1.0，推荐 0.375)
         * @return Builder
         */
        public Builder oldBlockRatio(double ratio) {
            this.oldBlockRatio = ratio;
            return this;
        }

        /**
         * 设置 Old 区驻留时间
         *
         * @param timeMs 毫秒 (推荐 1000ms)
         * @return Builder
         */
        public Builder oldBlockTimeMs(long timeMs) {
            this.oldBlockTimeMs = timeMs;
            return this;
        }

        /**
         * 设置 I/O 重试策略
         *
         * @param policy 重试策略
         * @return Builder
         */
        public Builder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        /**
         * 构建 BufferPoolConfig
         *
         * @return BufferPoolConfig 实例
         * @throws IllegalArgumentException 如果参数非法
         */
        public BufferPoolConfig build() {
            if (poolSize == 0) {
                throw new IllegalArgumentException("poolSize is required");
            }
            return new BufferPoolConfig(this);
        }
    }
}
