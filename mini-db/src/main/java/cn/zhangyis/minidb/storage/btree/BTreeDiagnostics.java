package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * B+Tree 诊断工具
 *
 * <p>提供 B+Tree 的诊断和验证功能。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeDiagnostics {

    /**
     * 验证 B+Tree 的完整性
     *
     * @param btree B+Tree
     * @param mtr   Mini-Transaction
     * @return 验证结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static ValidationResult validate(BTree btree, MiniTransaction mtr) throws MiniDbException {
        ValidationResult result = new ValidationResult();

        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        BTreeMetadata metadata = btree.getMetadata();

        // 验证根节点
        PageId rootPageId = metadata.getRootPageId();
        BufferFrame rootFrame = bufferPool.getPage(rootPageId, BufferPool.FetchMode.READ_EXISTING);
        rootFrame.readLock();
        try {
            ByteBuffer rootBuf = rootFrame.buffer();
            int rootLevel = IndexPageLayout.readLevel(rootBuf);

            if (rootLevel != metadata.getTreeHeight() - 1) {
                result.addError("Root level mismatch: expected " + (metadata.getTreeHeight() - 1) +
                        ", got " + rootLevel);
            }
        } finally {
            rootFrame.readUnlock();
        }

        // BFS 验证所有页面
        Queue<PageValidationContext> queue = new ArrayDeque<>();
        queue.add(new PageValidationContext(rootPageId, null, null, metadata.getTreeHeight() - 1));

        int pageCount = 0;
        long recordCount = 0;

        while (!queue.isEmpty()) {
            PageValidationContext ctx = queue.poll();
            pageCount++;

            BufferFrame frame = bufferPool.getPage(ctx.pageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();

            try {
                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);
                int pageRecordCount = IndexPageLayout.readRecordCount(buf);

                // 验证层级
                if (level != ctx.expectedLevel) {
                    result.addError(String.format("Page %s: level mismatch, expected %d, got %d",
                            ctx.pageId, ctx.expectedLevel, level));
                }

                // 验证记录顺序
                byte[] prevKey = null;
                int current = IndexPageLayout.readFirstUserRecordOffset(buf);

                while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
                    byte[] key = comparator.extractKey(buf, current);

                    // 检查键顺序
                    if (prevKey != null && compareKeys(key, prevKey) <= 0) {
                        result.addError(String.format("Page %s: keys not in order at offset %d",
                                ctx.pageId, current));
                    }

                    // 检查键范围
                    if (ctx.minKey != null && compareKeys(key, ctx.minKey) < 0) {
                        result.addError(String.format("Page %s: key %d below minimum %d",
                                ctx.pageId, IntKeyComparator.bytesToInt(key),
                                IntKeyComparator.bytesToInt(ctx.minKey)));
                    }
                    if (ctx.maxKey != null && compareKeys(key, ctx.maxKey) > 0) {
                        result.addError(String.format("Page %s: key %d above maximum %d",
                                ctx.pageId, IntKeyComparator.bytesToInt(key),
                                IntKeyComparator.bytesToInt(ctx.maxKey)));
                    }

                    // 如果是叶子节点，计数
                    if (level == 0) {
                        recordCount++;
                    }

                    // 如果是非叶子节点，将子页面加入队列
                    if (level > 0) {
                        int childPageNo = buf.getInt(current +
                                SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE);
                        PageId childPageId = new PageId(ctx.pageId.getSpaceId(), childPageNo);

                        // 计算子页面的键范围
                        byte[] childMinKey = prevKey;
                        int next = IndexPageLayout.readRecordNext(buf, current);
                        byte[] childMaxKey = (next != IndexPageLayout.SUPREMUM_OFFSET && next != 0)
                                ? comparator.extractKey(buf, next) : ctx.maxKey;

                        queue.add(new PageValidationContext(childPageId, childMinKey, childMaxKey, level - 1));
                    }

                    prevKey = key;
                    current = IndexPageLayout.readRecordNext(buf, current);
                }
            } finally {
                frame.readUnlock();
            }
        }

        // 验证记录总数
        if (recordCount != metadata.getRecordCount()) {
            result.addWarning(String.format("Record count mismatch: metadata says %d, counted %d",
                    metadata.getRecordCount(), recordCount));
        }

        result.setPageCount(pageCount);
        result.setRecordCount(recordCount);

        return result;
    }

    /**
     * 打印 B+Tree 结构
     *
     * @param btree B+Tree
     * @param mtr   Mini-Transaction
     * @return 树结构的字符串表示
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static String printTree(BTree btree, MiniTransaction mtr) throws MiniDbException {
        StringBuilder sb = new StringBuilder();
        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        BTreeMetadata metadata = btree.getMetadata();

        sb.append("B+Tree Structure\n");
        sb.append("================\n");
        sb.append(String.format("Index ID: %d\n", metadata.getIndexId()));
        sb.append(String.format("Height: %d\n", metadata.getTreeHeight()));
        sb.append(String.format("Record Count: %d\n", metadata.getRecordCount()));
        sb.append("\n");

        // BFS 打印每层
        Queue<PageId> currentLevel = new ArrayDeque<>();
        Queue<PageId> nextLevel = new ArrayDeque<>();
        currentLevel.add(metadata.getRootPageId());

        int levelNum = metadata.getTreeHeight() - 1;

        while (!currentLevel.isEmpty()) {
            sb.append(String.format("Level %d:\n", levelNum));

            while (!currentLevel.isEmpty()) {
                PageId pageId = currentLevel.poll();
                BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.readLock();

                try {
                    ByteBuffer buf = frame.buffer();
                    int level = IndexPageLayout.readLevel(buf);
                    int recordCount = IndexPageLayout.readRecordCount(buf);

                    sb.append(String.format("  [Page %d] records=%d keys=[",
                            pageId.getPageNo(), recordCount));

                    // 打印键
                    List<String> keys = new ArrayList<>();
                    int current = IndexPageLayout.readFirstUserRecordOffset(buf);
                    while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
                        byte[] key = comparator.extractKey(buf, current);
                        keys.add(String.valueOf(IntKeyComparator.bytesToInt(key)));

                        // 收集子页面
                        if (level > 0) {
                            int childPageNo = buf.getInt(current +
                                    SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE);
                            nextLevel.add(new PageId(pageId.getSpaceId(), childPageNo));
                        }

                        current = IndexPageLayout.readRecordNext(buf, current);
                    }

                    sb.append(String.join(", ", keys));
                    sb.append("]\n");
                } finally {
                    frame.readUnlock();
                }
            }

            // 移动到下一层
            Queue<PageId> temp = currentLevel;
            currentLevel = nextLevel;
            nextLevel = temp;
            levelNum--;
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取页面详细信息
     *
     * @param btree  B+Tree
     * @param pageNo 页面号
     * @param mtr    Mini-Transaction
     * @return 页面信息
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static PageInfo getPageInfo(BTree btree, int pageNo, MiniTransaction mtr)
            throws MiniDbException {
        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        PageId pageId = new PageId(btree.getMetadata().getSpaceId(), pageNo);

        BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();

        try {
            ByteBuffer buf = frame.buffer();

            PageInfo info = new PageInfo();
            info.pageNo = pageNo;
            info.level = IndexPageLayout.readLevel(buf);
            info.recordCount = IndexPageLayout.readRecordCount(buf);
            info.freeSpace = IndexPageLayout.freeSpace(buf);
            info.prevPage = IndexPageLayout.readPrevPage(buf);
            info.nextPage = IndexPageLayout.readNextPage(buf);

            // 收集键
            info.keys = new ArrayList<>();
            int current = IndexPageLayout.readFirstUserRecordOffset(buf);
            while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
                byte[] key = comparator.extractKey(buf, current);
                info.keys.add(IntKeyComparator.bytesToInt(key));
                current = IndexPageLayout.readRecordNext(buf, current);
            }

            return info;
        } finally {
            frame.readUnlock();
        }
    }

    /**
     * 比较两个键
     */
    private static int compareKeys(byte[] key1, byte[] key2) {
        int k1 = IntKeyComparator.bytesToInt(key1);
        int k2 = IntKeyComparator.bytesToInt(key2);
        return Integer.compare(k1, k2);
    }

    // ==================== 内部类 ====================

    /**
     * 页面验证上下文
     */
    private static class PageValidationContext {
        final PageId pageId;
        final byte[] minKey;
        final byte[] maxKey;
        final int expectedLevel;

        PageValidationContext(PageId pageId, byte[] minKey, byte[] maxKey, int expectedLevel) {
            this.pageId = pageId;
            this.minKey = minKey;
            this.maxKey = maxKey;
            this.expectedLevel = expectedLevel;
        }
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int pageCount;
        private long recordCount;

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public void setPageCount(int pageCount) {
            this.pageCount = pageCount;
        }

        public void setRecordCount(long recordCount) {
            this.recordCount = recordCount;
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public int getPageCount() {
            return pageCount;
        }

        public long getRecordCount() {
            return recordCount;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult {\n");
            sb.append(String.format("  valid: %s\n", isValid()));
            sb.append(String.format("  pageCount: %d\n", pageCount));
            sb.append(String.format("  recordCount: %d\n", recordCount));

            if (!errors.isEmpty()) {
                sb.append("  errors:\n");
                for (String error : errors) {
                    sb.append("    - ").append(error).append("\n");
                }
            }

            if (!warnings.isEmpty()) {
                sb.append("  warnings:\n");
                for (String warning : warnings) {
                    sb.append("    - ").append(warning).append("\n");
                }
            }

            sb.append("}");
            return sb.toString();
        }
    }

    /**
     * 页面信息
     */
    public static class PageInfo {
        public int pageNo;
        public int level;
        public int recordCount;
        public int freeSpace;
        public int prevPage;
        public int nextPage;
        public List<Integer> keys;

        @Override
        public String toString() {
            return String.format("PageInfo{pageNo=%d, level=%d, records=%d, free=%d, prev=%d, next=%d, keys=%s}",
                    pageNo, level, recordCount, freeSpace, prevPage, nextPage, keys);
        }
    }

    // 禁止实例化
    private BTreeDiagnostics() {
        throw new UnsupportedOperationException("BTreeDiagnostics is a utility class");
    }
}
