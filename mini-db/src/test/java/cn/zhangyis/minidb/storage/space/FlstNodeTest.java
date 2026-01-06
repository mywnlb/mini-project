package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.Test;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FlstNode 单元测试
 *
 * <p>测试链表节点的基本功能：
 * <ul>
 *   <li>构造函数参数验证</li>
 *   <li>前驱/后继节点的读写</li>
 *   <li>节点初始化</li>
 *   <li>节点状态查询（isHead, isTail, isIsolated）</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 */
class FlstNodeTest extends BaseStorageTest {
    // 继承了 BaseStorageTest，自动获得：
    // - diskManager
    // - bufferPool
    // - SPACE_ID
    // - 自动的 setup/cleanup

    // ==================== 构造函数测试 ====================

    @Test
    void testConstructor_ValidArguments() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);

            // 在 FIL Header 之后创建节点（偏移 38）
            FlstNode node = new FlstNode(page, 38);

            assertNotNull(node);
            assertEquals(page, node.getPage());
            assertEquals(38, node.getOffset());
        }
    }

    @Test
    void testConstructor_NullPage() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FlstNode(null, 38);
        });
    }

    @Test
    void testConstructor_InvalidOffset_Negative() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);

            assertThrows(IllegalArgumentException.class, () -> {
                new FlstNode(page, -1);
            });
        }
    }

    @Test
    void testConstructor_InvalidOffset_TooLarge() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);

            // 偏移太大，无法容纳 12 字节的 FLST_NODE
            assertThrows(IllegalArgumentException.class, () -> {
                new FlstNode(page, PAGE_SIZE - FLST_NODE_SIZE + 1);
            });
        }
    }

    // ==================== 初始化测试 ====================

    @Test
    void testInitialize() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            // 初始化节点
            node.initialize(mtr);

            // 验证前驱和后继都为 FIL_NULL
            assertEquals(FIL_NULL, node.getPrevPageNo());
            assertEquals(0, node.getPrevOffset());
            assertEquals(FIL_NULL, node.getNextPageNo());
            assertEquals(0, node.getNextOffset());

            // 验证状态
            assertTrue(node.isIsolated());
            assertFalse(node.hasPrev());
            assertFalse(node.hasNext());

            mtr.commit();
        }
    }

    // ==================== 前驱节点测试 ====================

    @Test
    void testSetAndGetPrevNode() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            // 初始化
            node.initialize(mtr);
            assertNull(node.getPrevNode());

            // 设置前驱节点
            node.setPrevNode(mtr, 10, 100);

            // 验证前驱节点
            assertEquals(10, node.getPrevPageNo());
            assertEquals(100, node.getPrevOffset());

            PageId prevId = node.getPrevNode();
            assertNotNull(prevId);
            assertEquals(SPACE_ID, prevId.getSpaceId());
            assertEquals(10, prevId.getPageNo());

            assertTrue(node.hasPrev());
            assertFalse(node.isIsolated());

            mtr.commit();
        }
    }

    @Test
    void testSetPrevNode_ToNull() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            // 设置前驱节点
            node.setPrevNode(mtr, 10, 100);
            assertTrue(node.hasPrev());

            // 清除前驱节点
            node.setPrevNode(mtr, FIL_NULL, 0);

            assertNull(node.getPrevNode());
            assertFalse(node.hasPrev());

            mtr.commit();
        }
    }

    // ==================== 后继节点测试 ====================

    @Test
    void testSetAndGetNextNode() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            // 初始化
            node.initialize(mtr);
            assertNull(node.getNextNode());

            // 设置后继节点
            node.setNextNode(mtr, 20, 200);

            // 验证后继节点
            assertEquals(20, node.getNextPageNo());
            assertEquals(200, node.getNextOffset());

            PageId nextId = node.getNextNode();
            assertNotNull(nextId);
            assertEquals(SPACE_ID, nextId.getSpaceId());
            assertEquals(20, nextId.getPageNo());

            assertTrue(node.hasNext());
            assertFalse(node.isIsolated());

            mtr.commit();
        }
    }

    @Test
    void testSetNextNode_ToNull() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            // 设置后继节点
            node.setNextNode(mtr, 20, 200);
            assertTrue(node.hasNext());

            // 清除后继节点
            node.setNextNode(mtr, FIL_NULL, 0);

            assertNull(node.getNextNode());
            assertFalse(node.hasNext());

            mtr.commit();
        }
    }

    // ==================== 状态查询测试 ====================

    @Test
    void testIsHead() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            node.initialize(mtr);

            // 孤立节点：无前驱无后继
            assertTrue(node.isIsolated());

            // 设置后继，变成头节点
            node.setNextNode(mtr, 10, 100);
            assertFalse(node.hasPrev());  // 无前驱 = 头节点
            assertTrue(node.hasNext());
            assertFalse(node.isIsolated());

            mtr.commit();
        }
    }

    @Test
    void testIsTail() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            node.initialize(mtr);

            // 设置前驱，变成尾节点
            node.setPrevNode(mtr, 10, 100);
            assertTrue(node.hasPrev());
            assertFalse(node.hasNext());  // 无后继 = 尾节点
            assertFalse(node.isIsolated());

            mtr.commit();
        }
    }

    @Test
    void testIsIsolated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            node.initialize(mtr);
            assertTrue(node.isIsolated());

            // 设置前驱
            node.setPrevNode(mtr, 10, 100);
            assertFalse(node.isIsolated());

            // 设置后继
            node.setNextNode(mtr, 20, 200);
            assertFalse(node.isIsolated());

            // 清除前驱
            node.setPrevNode(mtr, FIL_NULL, 0);
            assertFalse(node.isIsolated());  // 仍有后继

            // 清除后继
            node.setNextNode(mtr, FIL_NULL, 0);
            assertTrue(node.isIsolated());  // 完全孤立

            mtr.commit();
        }
    }

    // ==================== 链表连接测试 ====================

    @Test
    void testTwoNodeChain() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page1 = mtr.newPage(SPACE_ID);
            Page page2 = mtr.newPage(SPACE_ID);

            FlstNode node1 = new FlstNode(page1, 38);
            FlstNode node2 = new FlstNode(page2, 38);

            // 初始化两个节点
            node1.initialize(mtr);
            node2.initialize(mtr);

            // 连接：node1 -> node2
            node1.setNextNode(mtr, page2.getPageNo(), 38);
            node2.setPrevNode(mtr, page1.getPageNo(), 38);

            // 验证 node1（头节点）
            assertFalse(node1.hasPrev());
            assertTrue(node1.hasNext());
            assertEquals(page2.getPageNo(), node1.getNextPageNo());
            assertEquals(38, node1.getNextOffset());

            // 验证 node2（尾节点）
            assertTrue(node2.hasPrev());
            assertFalse(node2.hasNext());
            assertEquals(page1.getPageNo(), node2.getPrevPageNo());
            assertEquals(38, node2.getPrevOffset());

            mtr.commit();
        }
    }

    // ==================== toString 测试 ====================

    @Test
    void testToString() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            FlstNode node = new FlstNode(page, 38);

            node.initialize(mtr);
            node.setPrevNode(mtr, 10, 100);
            node.setNextNode(mtr, 20, 200);

            String str = node.toString();
            assertNotNull(str);
            assertTrue(str.contains("10"));   // prev page
            assertTrue(str.contains("100"));  // prev offset
            assertTrue(str.contains("20"));   // next page
            assertTrue(str.contains("200"));  // next offset

            mtr.commit();
        }
    }
}
