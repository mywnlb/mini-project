package cn.zhangyis.minidb.storage.record.format;

/**
 * 溢出策略
 *
 * <p>控制大字段的溢出存储行为。</p>
 *
 * <h2>策略类型</h2>
 * <ul>
 *   <li>NEVER: 永不溢出，字段过大时报错</li>
 *   <li>THRESHOLD: 超过阈值时溢出</li>
 *   <li>ALWAYS_EXTERNAL: 总是外部存储</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public sealed interface OverflowPolicy {

    /**
     * 永不溢出
     * <p>当前默认策略，直到外部页机制实现</p>
     */
    record Never() implements OverflowPolicy {
        @Override
        public boolean shouldOverflow(int fieldLength) {
            return false;
        }

        @Override
        public int getPrefixKeepBytes() {
            return 0;
        }
    }

    /**
     * 超过阈值则溢出
     *
     * @param maxInlineBytes   内联存储的最大字节数
     * @param prefixKeepBytes  溢出时保留在行内的前缀字节数
     */
    record Threshold(int maxInlineBytes, int prefixKeepBytes) implements OverflowPolicy {
        @Override
        public boolean shouldOverflow(int fieldLength) {
            return fieldLength > maxInlineBytes;
        }

        @Override
        public int getPrefixKeepBytes() {
            return prefixKeepBytes;
        }
    }

    /**
     * 总是外部存储
     *
     * @param prefixKeepBytes 保留在行内的前缀字节数
     */
    record AlwaysExternal(int prefixKeepBytes) implements OverflowPolicy {
        @Override
        public boolean shouldOverflow(int fieldLength) {
            return fieldLength > 0;
        }

        @Override
        public int getPrefixKeepBytes() {
            return prefixKeepBytes;
        }
    }

    /**
     * 判断是否应该溢出存储
     *
     * @param fieldLength 字段长度
     * @return true 如果应该溢出
     */
    boolean shouldOverflow(int fieldLength);

    /**
     * 获取溢出时保留在行内的前缀字节数
     */
    int getPrefixKeepBytes();

    /**
     * 获取默认策略（永不溢出）
     */
    static OverflowPolicy defaultPolicy() {
        return new Never();
    }

    /**
     * 创建阈值策略
     *
     * @param maxInlineBytes  内联最大字节数
     * @param prefixKeepBytes 前缀保留字节数
     */
    static OverflowPolicy threshold(int maxInlineBytes, int prefixKeepBytes) {
        return new Threshold(maxInlineBytes, prefixKeepBytes);
    }
}
