package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引元数据页面
 *
 * <p>用于持久化索引描述符的专用页面。</p>
 *
 * <h2>页面布局</h2>
 * <pre>
 * +------------------+
 * | Page Header (38) |
 * +------------------+
 * | Meta Header (16) |
 * |  - magic (4)     |
 * |  - version (4)   |
 * |  - count (4)     |
 * |  - nextPage (4)  |
 * +------------------+
 * | Index Entry 1    |
 * +------------------+
 * | Index Entry 2    |
 * +------------------+
 * | ...              |
 * +------------------+
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class IndexMetaPage {

    /** 页面大小 */
    private static final int PAGE_SIZE = 16 * 1024;

    /** 魔数 */
    private static final int MAGIC = 0x494E4458; // "INDX"

    /** 版本号 */
    private static final int VERSION = 1;

    /** 页面头部大小 */
    private static final int PAGE_HEADER_SIZE = 38;

    /** 元数据头部偏移 */
    private static final int META_HEADER_OFFSET = PAGE_HEADER_SIZE;

    /** 元数据头部大小 */
    private static final int META_HEADER_SIZE = 16;

    /** 数据区偏移 */
    private static final int DATA_OFFSET = META_HEADER_OFFSET + META_HEADER_SIZE;

    /** 可用数据空间 */
    private static final int DATA_SPACE = PAGE_SIZE - DATA_OFFSET;

    /**
     * 初始化索引元数据页面
     *
     * @param frame Buffer Frame
     * @param mtr   Mini-Transaction
     */
    public static void initPage(BufferFrame frame, MiniTransaction mtr) {
        ByteBuffer buf = frame.buffer();

        // 写入元数据头部
        buf.putInt(META_HEADER_OFFSET, MAGIC);
        buf.putInt(META_HEADER_OFFSET + 4, VERSION);
        buf.putInt(META_HEADER_OFFSET + 8, 0); // count
        buf.putInt(META_HEADER_OFFSET + 12, 0); // nextPage

        frame.setDirty(true);
    }

    /**
     * 检查是否为有效的索引元数据页面
     *
     * @param frame Buffer Frame
     * @return 如果有效返回 true
     */
    public static boolean isValid(BufferFrame frame) {
        ByteBuffer buf = frame.buffer();
        int magic = buf.getInt(META_HEADER_OFFSET);
        return magic == MAGIC;
    }

    /**
     * 读取索引数量
     *
     * @param frame Buffer Frame
     * @return 索引数量
     */
    public static int readIndexCount(BufferFrame frame) {
        return frame.buffer().getInt(META_HEADER_OFFSET + 8);
    }

    /**
     * 写入索引数量
     *
     * @param frame Buffer Frame
     * @param count 索引数量
     */
    public static void writeIndexCount(BufferFrame frame, int count) {
        frame.buffer().putInt(META_HEADER_OFFSET + 8, count);
        frame.setDirty(true);
    }

    /**
     * 读取下一页页号
     *
     * @param frame Buffer Frame
     * @return 下一页页号（0 表示没有）
     */
    public static int readNextPage(BufferFrame frame) {
        return frame.buffer().getInt(META_HEADER_OFFSET + 12);
    }

    /**
     * 写入下一页页号
     *
     * @param frame    Buffer Frame
     * @param nextPage 下一页页号
     */
    public static void writeNextPage(BufferFrame frame, int nextPage) {
        frame.buffer().putInt(META_HEADER_OFFSET + 12, nextPage);
        frame.setDirty(true);
    }

    /**
     * 读取所有索引描述符
     *
     * @param frame Buffer Frame
     * @return 索引描述符列表
     */
    public static List<IndexDescriptor> readAllDescriptors(BufferFrame frame) {
        List<IndexDescriptor> descriptors = new ArrayList<>();
        ByteBuffer buf = frame.buffer();

        int count = readIndexCount(frame);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            // 读取条目长度
            int entryLen = buf.getInt(offset);
            offset += 4;

            // 读取条目数据
            byte[] entryData = new byte[entryLen];
            int oldPos = buf.position();
            buf.position(offset);
            buf.get(entryData);
            buf.position(oldPos);

            IndexDescriptor desc = IndexDescriptor.deserialize(entryData);
            if (!desc.isDeleted()) {
                descriptors.add(desc);
            }

            offset += entryLen;
        }

        return descriptors;
    }

    /**
     * 写入索引描述符
     *
     * @param frame      Buffer Frame
     * @param descriptor 索引描述符
     * @return 如果成功写入返回 true，空间不足返回 false
     */
    public static boolean writeDescriptor(BufferFrame frame, IndexDescriptor descriptor) {
        ByteBuffer buf = frame.buffer();

        byte[] data = descriptor.serialize();
        int entrySize = 4 + data.length; // 长度前缀 + 数据

        // 计算当前使用的空间
        int currentOffset = calculateCurrentOffset(frame);

        // 检查空间是否足够
        if (currentOffset + entrySize > PAGE_SIZE) {
            return false;
        }

        // 写入条目
        buf.putInt(currentOffset, data.length);
        int oldPos = buf.position();
        buf.position(currentOffset + 4);
        buf.put(data);
        buf.position(oldPos);

        // 更新计数
        int count = readIndexCount(frame);
        writeIndexCount(frame, count + 1);

        frame.setDirty(true);
        return true;
    }

    /**
     * 更新索引描述符
     *
     * @param frame      Buffer Frame
     * @param descriptor 索引描述符
     * @return 如果成功更新返回 true
     */
    public static boolean updateDescriptor(BufferFrame frame, IndexDescriptor descriptor) {
        ByteBuffer buf = frame.buffer();

        int count = readIndexCount(frame);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            int entryLen = buf.getInt(offset);
            offset += 4;

            // 读取条目检查 ID
            byte[] entryData = new byte[entryLen];
            int oldPos = buf.position();
            buf.position(offset);
            buf.get(entryData);
            buf.position(oldPos);

            IndexDescriptor existing = IndexDescriptor.deserialize(entryData);

            if (existing.getIndexId() == descriptor.getIndexId()) {
                // 找到了，更新
                byte[] newData = descriptor.serialize();

                if (newData.length != entryLen) {
                    // 大小变化，需要重写整个页面（简化处理）
                    return rewritePage(frame, descriptor);
                }

                // 大小相同，直接覆盖
                buf.position(offset);
                buf.put(newData);
                buf.position(oldPos);

                frame.setDirty(true);
                return true;
            }

            offset += entryLen;
        }

        return false;
    }

    /**
     * 删除索引描述符（标记删除）
     *
     * @param frame   Buffer Frame
     * @param indexId 索引 ID
     * @return 如果成功删除返回 true
     */
    public static boolean deleteDescriptor(BufferFrame frame, long indexId) {
        ByteBuffer buf = frame.buffer();

        int count = readIndexCount(frame);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            int entryLen = buf.getInt(offset);
            offset += 4;

            byte[] entryData = new byte[entryLen];
            int oldPos = buf.position();
            buf.position(offset);
            buf.get(entryData);
            buf.position(oldPos);

            IndexDescriptor existing = IndexDescriptor.deserialize(entryData);

            if (existing.getIndexId() == indexId) {
                // 标记删除
                existing.setDeleted(true);
                byte[] newData = existing.serialize();

                buf.position(offset);
                buf.put(newData);
                buf.position(oldPos);

                frame.setDirty(true);
                return true;
            }

            offset += entryLen;
        }

        return false;
    }

    /**
     * 查找索引描述符
     *
     * @param frame   Buffer Frame
     * @param indexId 索引 ID
     * @return 索引描述符，未找到返回 null
     */
    public static IndexDescriptor findDescriptor(BufferFrame frame, long indexId) {
        ByteBuffer buf = frame.buffer();

        int count = readIndexCount(frame);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            int entryLen = buf.getInt(offset);
            offset += 4;

            byte[] entryData = new byte[entryLen];
            int oldPos = buf.position();
            buf.position(offset);
            buf.get(entryData);
            buf.position(oldPos);

            IndexDescriptor desc = IndexDescriptor.deserialize(entryData);

            if (desc.getIndexId() == indexId && !desc.isDeleted()) {
                return desc;
            }

            offset += entryLen;
        }

        return null;
    }

    /**
     * 计算当前数据偏移
     */
    private static int calculateCurrentOffset(BufferFrame frame) {
        ByteBuffer buf = frame.buffer();

        int count = readIndexCount(frame);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            int entryLen = buf.getInt(offset);
            offset += 4 + entryLen;
        }

        return offset;
    }

    /**
     * 重写页面（用于更新大小变化的条目）
     */
    private static boolean rewritePage(BufferFrame frame, IndexDescriptor updatedDesc) {
        // 读取所有描述符
        List<IndexDescriptor> descriptors = new ArrayList<>();
        ByteBuffer buf = frame.buffer();

        int count = readIndexCount(frame);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            int entryLen = buf.getInt(offset);
            offset += 4;

            byte[] entryData = new byte[entryLen];
            int oldPos = buf.position();
            buf.position(offset);
            buf.get(entryData);
            buf.position(oldPos);

            IndexDescriptor desc = IndexDescriptor.deserialize(entryData);

            if (desc.getIndexId() == updatedDesc.getIndexId()) {
                descriptors.add(updatedDesc);
            } else {
                descriptors.add(desc);
            }

            offset += entryLen;
        }

        // 清空并重写
        writeIndexCount(frame, 0);

        for (IndexDescriptor desc : descriptors) {
            if (!writeDescriptor(frame, desc)) {
                return false; // 空间不足
            }
        }

        return true;
    }

    // 禁止实例化
    private IndexMetaPage() {
        throw new UnsupportedOperationException("IndexMetaPage is a utility class");
    }
}
