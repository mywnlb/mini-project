package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.RecordVersion;
import cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader;
import cn.zhangyis.minidb.storage.transaction.mvcc.VisibilityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * MVCC 感知的 B+Tree 范围扫描器
 *
 * <p>在 B+Tree 范围扫描的基础上，添加 MVCC 可见性过滤。
 * 自动过滤不可见的记录，支持版本链遍历。</p>
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>从 B+Tree 范围扫描获取物理记录</li>
 *   <li>检查记录的可见性（基于 ReadView）</li>
 *   <li>如果不可见，遍历版本链查找可见版本</li>
 *   <li>检查删除标记，跳过已删除的记录</li>
 *   <li>返回可见的记录</li>
 * </ol>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>M1</b>: ReadView 不可变，遍历过程中不改变</li>
 *   <li><b>M2</b>: 版本链完整性必须保证</li>
 *   <li><b>M3</b>: 可见性判断必须严格遵循算法</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 * @see ReadView
 * @see VisibilityChecker
 * @see VersionChainReader
 * @see BTreeRangeScanner
 */
public class MvccBTreeRangeScanner implements Iterable<BTreeRangeScanner.ScanEntry>, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MvccBTreeRangeScanner.class);

    // ==================== 字段 ====================

    /**
     * 底层 B+Tree 范围扫描器
     */
    private final BTreeRangeScanner btreeScanner;

    /**
     * 读视图（快照隔离）
     */
    private final ReadView readView;

    /**
     * Mini-Transaction
     */
    private final MiniTransaction mtr;

    /**
     * Buffer Pool
     */
    private final BufferPool bufferPool;

    /**
     * 版本链读取器
     */
    private final VersionChainReader versionChainReader;

    /**
     * 记录读取器（用于读取 RecordVersion）
     */
    private final RecordVersionReader recordVersionReader;

    /**
     * 是否已关闭
     */
    private boolean closed;

    // ==================== 构造函数 ====================

    /**
     * 创建 MVCC 感知的 B+Tree 范围扫描器
     *
     * @param btreeScanner          底层 B+Tree 范围扫描器
     * @param readView              读视图
     * @param mtr                   Mini-Transaction
     * @param bufferPool            Buffer Pool
     * @param versionChainReader    版本链读取器
     * @param recordVersionReader   记录读取器
     */
    public MvccBTreeRangeScanner(BTreeRangeScanner btreeScanner,
                                 ReadView readView,
                                 MiniTransaction mtr,
                                 BufferPool bufferPool,
                                 VersionChainReader versionChainReader,
                                 RecordVersionReader recordVersionReader) {
        this.btreeScanner = btreeScanner;
        this.readView = readView;
        this.mtr = mtr;
        this.bufferPool = bufferPool;
        this.versionChainReader = versionChainReader;
        this.recordVersionReader = recordVersionReader;
        this.closed = false;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建全表扫描器（MVCC 感知）
     */
    public static MvccBTreeRangeScanner fullScan(BTree btree,
                                                 BufferPool bufferPool,
                                                 RecordComparator comparator,
                                                 MiniTransaction mtr,
                                                 ReadView readView,
                                                 VersionChainReader versionChainReader,
                                                 RecordVersionReader recordVersionReader) {
        BTreeRangeScanner btreeScanner = BTreeRangeScanner.fullScan(btree, bufferPool, comparator, mtr);
        return new MvccBTreeRangeScanner(btreeScanner, readView, mtr, bufferPool,
                versionChainReader, recordVersionReader);
    }

    /**
     * 创建范围扫描器（MVCC 感知）
     */
    public static MvccBTreeRangeScanner range(BTree btree,
                                              BufferPool bufferPool,
                                              RecordComparator comparator,
                                              MiniTransaction mtr,
                                              RangeBound lowerBound,
                                              RangeBound upperBound,
                                              ReadView readView,
                                              VersionChainReader versionChainReader,
                                              RecordVersionReader recordVersionReader) {
        BTreeRangeScanner btreeScanner = new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                lowerBound, upperBound);
        return new MvccBTreeRangeScanner(btreeScanner, readView, mtr, bufferPool,
                versionChainReader, recordVersionReader);
    }

    // ==================== Iterable 实现 ====================

    @Override
    public Iterator<BTreeRangeScanner.ScanEntry> iterator() {
        return new MvccScanIterator(1); // 默认值长度为 1
    }

    /**
     * 获取指定值长度的迭代器
     *
     * @param valueLength 值长度
     * @return 迭代器
     */
    public Iterator<BTreeRangeScanner.ScanEntry> iterator(int valueLength) {
        return new MvccScanIterator(valueLength);
    }

    // ==================== 资源管理 ====================

    @Override
    public void close() {
        if (!closed) {
            btreeScanner.close();
            closed = true;
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 检查记录是否对 ReadView 可见
     *
     * @param recordVersion 记录版本
     * @return true 如果可见
     */
    private boolean isVisible(RecordVersion recordVersion) {
        // 如果没有设置 ReadView，则所有记录都可见（向后兼容）
        if (readView == null) {
            return true;
        }

        // 检查当前版本可见性
        if (VisibilityChecker.isVisible(recordVersion.getTrxId(), readView)) {
            return true;
        }

        // 当前版本不可见，遍历版本链
        if (versionChainReader == null) {
            return false;
        }

        Optional<RecordVersion> visibleVersion = versionChainReader.findVisibleVersion(
            recordVersion.getRollPtr(), readView);

        return visibleVersion.isPresent();
    }

    /**
     * 检查记录是否被删除
     *
     * @param recordVersion 记录版本
     * @return true 如果被删除
     */
    private boolean isDeleted(RecordVersion recordVersion) {
        if (recordVersion.isDeleteMarked()) {
            return true;
        }

        // 如果当前版本不可见，检查可见版本是否被删除
        if (readView != null && !VisibilityChecker.isVisible(recordVersion.getTrxId(), readView)) {
            if (versionChainReader != null) {
                Optional<RecordVersion> visibleVersion = versionChainReader.findVisibleVersion(
                    recordVersion.getRollPtr(), readView);

                if (visibleVersion.isPresent()) {
                    return visibleVersion.get().isDeleteMarked();
                }
            }
        }

        return false;
    }

    // ==================== 内部类 ====================

    /**
     * MVCC 感知的扫描迭代器
     */
    private class MvccScanIterator implements Iterator<BTreeRangeScanner.ScanEntry> {
        private final Iterator<BTreeRangeScanner.ScanEntry> btreeIterator;
        private final int valueLength;
        private BTreeRangeScanner.ScanEntry nextEntry;
        private boolean hasNextComputed;

        MvccScanIterator(int valueLength) {
            this.btreeIterator = btreeScanner.iterator(valueLength);
            this.valueLength = valueLength;
            this.nextEntry = null;
            this.hasNextComputed = false;
        }

        @Override
        public boolean hasNext() {
            if (hasNextComputed) {
                return nextEntry != null;
            }

            // 查找下一个可见的未删除记录
            while (btreeIterator.hasNext()) {
                try {
                    BTreeRangeScanner.ScanEntry entry = btreeIterator.next();

                    // 从 ScanEntry 中读取记录版本信息
                    RecordVersion recordVersion = recordVersionReader.readRecordVersion(
                        entry.getKey(), entry.getValue());

                    // 检查可见性
                    if (!isVisible(recordVersion)) {
                        continue;
                    }

                    // 检查删除标记
                    if (isDeleted(recordVersion)) {
                        continue;
                    }

                    // 找到可见的未删除记录
                    nextEntry = entry;
                    hasNextComputed = true;
                    return true;

                } catch (Exception e) {
                    logger.warn("Error reading record from B+Tree", e);
                    // 继续下一条记录
                }
            }

            hasNextComputed = true;
            nextEntry = null;
            return false;
        }

        @Override
        public BTreeRangeScanner.ScanEntry next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more visible records");
            }

            BTreeRangeScanner.ScanEntry result = nextEntry;
            hasNextComputed = false;
            nextEntry = null;
            return result;
        }
    }

    /**
     * 记录版本读取器接口
     *
     * <p>用于从 B+Tree 的 ScanEntry 中读取 RecordVersion 信息。</p>
     */
    public interface RecordVersionReader {
        /**
         * 从键值对中读取记录版本
         *
         * @param key   记录键
         * @param value 记录值
         * @return RecordVersion 对象
         * @throws MiniDbException 如果读取失败
         */
        RecordVersion readRecordVersion(byte[] key, byte[] value) throws MiniDbException;
    }
}
