package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * B+Tree 搜索路径
 *
 * <p>记录从根节点到目标节点的搜索路径，用于：</p>
 * <ul>
 *   <li>插入时向上传播分裂</li>
 *   <li>删除时向上传播合并</li>
 *   <li>调试和诊断</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreePath {

    /** 路径节点列表（从根到叶） */
    private final List<PathNode> nodes;

    /**
     * 构造空路径
     */
    public BTreePath() {
        this.nodes = new ArrayList<>();
    }

    /**
     * 添加路径节点
     *
     * @param pageId       页面 ID
     * @param level        页面层级
     * @param recordOffset 搜索到的记录偏移（用于定位子节点）
     */
    public void addNode(PageId pageId, int level, int recordOffset) {
        nodes.add(new PathNode(pageId, level, recordOffset));
    }

    /**
     * 获取路径长度
     *
     * @return 路径中的节点数
     */
    public int length() {
        return nodes.size();
    }

    /**
     * 获取指定位置的节点
     *
     * @param index 索引（0 = 根节点）
     * @return 路径节点
     */
    public PathNode getNode(int index) {
        return nodes.get(index);
    }

    /**
     * 获取叶子节点（路径最后一个节点）
     *
     * @return 叶子节点，如果路径为空返回 null
     */
    public PathNode getLeafNode() {
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.get(nodes.size() - 1);
    }

    /**
     * 获取根节点（路径第一个节点）
     *
     * @return 根节点，如果路径为空返回 null
     */
    public PathNode getRootNode() {
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.get(0);
    }

    /**
     * 获取父节点
     *
     * @param index 当前节点索引
     * @return 父节点，如果是根节点返回 null
     */
    public PathNode getParent(int index) {
        if (index <= 0) {
            return null;
        }
        return nodes.get(index - 1);
    }

    /**
     * 检查路径是否为空
     *
     * @return 如果路径为空返回 true
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * 清空路径
     */
    public void clear() {
        nodes.clear();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BTreePath[\n");
        for (int i = 0; i < nodes.size(); i++) {
            sb.append("  ").append(i).append(": ").append(nodes.get(i)).append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 路径节点
     */
    public static class PathNode {
        private final PageId pageId;
        private final int level;
        private final int recordOffset;

        public PathNode(PageId pageId, int level, int recordOffset) {
            this.pageId = pageId;
            this.level = level;
            this.recordOffset = recordOffset;
        }

        public PageId getPageId() {
            return pageId;
        }

        public int getLevel() {
            return level;
        }

        public int getRecordOffset() {
            return recordOffset;
        }

        @Override
        public String toString() {
            return String.format("PathNode{pageId=%s, level=%d, recOffset=%d}",
                    pageId, level, recordOffset);
        }
    }
}
