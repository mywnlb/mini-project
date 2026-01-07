//package cn.zhangyis.minidb.storage.space;
//
//import cn.zhangyis.minidb.storage.BaseStorageTest;
//import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
//import cn.zhangyis.minidb.storage.page.Page;
//import cn.zhangyis.minidb.storage.page.PageId;
//import org.junit.jupiter.api.Test;
//
//import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * FlstBaseNode 单元测试
// *
// * <p>测试链表基节点的核心功能：
// * <ul>
// *   <li>构造函数和初始化</li>
// *   <li>链表长度管理</li>
// *   <li>首节点/尾节点的读写</li>
// *   <li>addFirst/addLast 操作</li>
// *   <li>removeFirst 操作</li>
// *   <li>空链表和多节点链表的边界条件</li>
// * </ul>
// * </p>
// *
// * @author MiniDB
// */
//class FlstBaseNodeTest extends BaseStorageTest {
//    // 继承了 BaseStorageTest，自动获得：
//    // - diskManager
//    // - bufferPool
//    // - SPACE_ID
//    // - 自动的 setup/cleanup
//
//    // ==================== 构造函数测试 ====================
//
//    @Test
//    void testConstructor_ValidArguments() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//
//            // 在 FIL Header 之后创建基节点（偏移 38）
//            FlstBaseNode baseNode = new FlstBaseNode(page, 38);
//
//            assertNotNull(baseNode);
//        }
//    }
//
//    @Test
//    void testConstructor_NullPage() {
//        assertThrows(IllegalArgumentException.class, () -> {
//            new FlstBaseNode(null, 38);
//        });
//    }
//
//    @Test
//    void testConstructor_InvalidOffset() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//
//            // 偏移太大，无法容纳 16 字节的 FLST_BASE_NODE
//            assertThrows(IllegalArgumentException.class, () -> {
//                new FlstBaseNode(page, PAGE_SIZE - FLST_BASE_NODE_SIZE + 1);
//            });
//        }
//    }
//
//    // ==================== 初始化测试 ====================
//
//    @Test
//    void testInitialize() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            FlstBaseNode baseNode = new FlstBaseNode(page, 38);
//
//            // 初始化为空链表
//            baseNode.initialize(mtr);
//
//            // 验证链表长度为 0
//            assertEquals(0, baseNode.getLength());
//            assertTrue(baseNode.isEmpty());
//
//            // 验证首尾节点都为 NULL
//            assertNull(baseNode.getFirstNode());
//            assertNull(baseNode.getLastNode());
//            assertEquals(-1, baseNode.getFirstNodeOffset());
//            assertEquals(-1, baseNode.getLastNodeOffset());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 长度管理测试 ====================
//
//    @Test
//    void testSetAndGetLength() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            FlstBaseNode baseNode = new FlstBaseNode(page, 38);
//
//            baseNode.initialize(mtr);
//            assertEquals(0, baseNode.getLength());
//
//            // 设置长度
//            baseNode.setLength(mtr, 5);
//            assertEquals(5, baseNode.getLength());
//            assertFalse(baseNode.isEmpty());
//
//            // 重置为 0
//            baseNode.setLength(mtr, 0);
//            assertEquals(0, baseNode.getLength());
//            assertTrue(baseNode.isEmpty());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 首节点/尾节点访问测试 ====================
//
//    @Test
//    void testSetAndGetFirstNode() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            FlstBaseNode baseNode = new FlstBaseNode(page, 38);
//
//            baseNode.initialize(mtr);
//            assertNull(baseNode.getFirstNode());
//
//            // 设置首节点
//            baseNode.setFirstNode(mtr, 10, 100);
//
//            // 验证首节点
//            PageId firstId = baseNode.getFirstNode();
//            assertNotNull(firstId);
//            assertEquals(SPACE_ID, firstId.getSpaceId());
//            assertEquals(10, firstId.getPageNo());
//            assertEquals(100, baseNode.getFirstNodeOffset());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testSetAndGetLastNode() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            FlstBaseNode baseNode = new FlstBaseNode(page, 38);
//
//            baseNode.initialize(mtr);
//            assertNull(baseNode.getLastNode());
//
//            // 设置尾节点
//            baseNode.setLastNode(mtr, 20, 200);
//
//            // 验证尾节点
//            PageId lastId = baseNode.getLastNode();
//            assertNotNull(lastId);
//            assertEquals(SPACE_ID, lastId.getSpaceId());
//            assertEquals(20, lastId.getPageNo());
//            assertEquals(200, baseNode.getLastNodeOffset());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== addFirst 测试 ====================
//
//    @Test
//    void testAddFirst_ToEmptyList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page nodePage = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//
//            // 添加第一个节点
//            int nodeOffset = 100;
//            baseNode.addFirst(mtr, nodePage, nodeOffset);
//
//            // 验证链表长度
//            assertEquals(1, baseNode.getLength());
//            assertFalse(baseNode.isEmpty());
//
//            // 验证首尾节点都指向同一个节点
//            assertEquals(nodePage.getPageNo(), baseNode.getFirstNode().getPageNo());
//            assertEquals(nodePage.getPageNo(), baseNode.getLastNode().getPageNo());
//            assertEquals(nodeOffset, baseNode.getFirstNodeOffset());
//            assertEquals(nodeOffset, baseNode.getLastNodeOffset());
//
//            // 验证节点本身是孤立的（无前驱无后继）
//            FlstNode node = new FlstNode(nodePage, nodeOffset);
//            assertTrue(node.isIsolated());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testAddFirst_ToNonEmptyList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page node1Page = mtr.newPage(SPACE_ID);
//            Page node2Page = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//
//            int offset1 = 100;
//            int offset2 = 200;
//
//            // 添加第一个节点
//            baseNode.addFirst(mtr, node1Page, offset1);
//            assertEquals(1, baseNode.getLength());
//
//            // 添加第二个节点到链表头
//            baseNode.addFirst(mtr, node2Page, offset2);
//            assertEquals(2, baseNode.getLength());
//
//            // 验证首节点是 node2
//            assertEquals(node2Page.getPageNo(), baseNode.getFirstNode().getPageNo());
//            assertEquals(offset2, baseNode.getFirstNodeOffset());
//
//            // 验证尾节点是 node1
//            assertEquals(node1Page.getPageNo(), baseNode.getLastNode().getPageNo());
//            assertEquals(offset1, baseNode.getLastNodeOffset());
//
//            // 验证 node2 的指针（新头节点）
//            FlstNode node2 = new FlstNode(node2Page, offset2);
//            assertFalse(node2.hasPrev());  // 头节点无前驱
//            assertTrue(node2.hasNext());
//            assertEquals(node1Page.getPageNo(), node2.getNextPageNo());
//            assertEquals(offset1, node2.getNextOffset());
//
//            // 验证 node1 的指针（原头节点变成尾节点）
//            FlstNode node1 = new FlstNode(node1Page, offset1);
//            assertTrue(node1.hasPrev());
//            assertEquals(node2Page.getPageNo(), node1.getPrevPageNo());
//            assertEquals(offset2, node1.getPrevOffset());
//            assertFalse(node1.hasNext());  // 尾节点无后继
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== addLast 测试 ====================
//
//    @Test
//    void testAddLast_ToEmptyList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page nodePage = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//
//            // 添加第一个节点
//            int nodeOffset = 100;
//            baseNode.addLast(mtr, nodePage, nodeOffset);
//
//            // 验证链表长度
//            assertEquals(1, baseNode.getLength());
//
//            // 验证首尾节点都指向同一个节点
//            assertEquals(nodePage.getPageNo(), baseNode.getFirstNode().getPageNo());
//            assertEquals(nodePage.getPageNo(), baseNode.getLastNode().getPageNo());
//
//            // 验证节点本身是孤立的
//            FlstNode node = new FlstNode(nodePage, nodeOffset);
//            assertTrue(node.isIsolated());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testAddLast_ToNonEmptyList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page node1Page = mtr.newPage(SPACE_ID);
//            Page node2Page = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//
//            int offset1 = 100;
//            int offset2 = 200;
//
//            // 添加第一个节点
//            baseNode.addLast(mtr, node1Page, offset1);
//            assertEquals(1, baseNode.getLength());
//
//            // 添加第二个节点到链表尾
//            baseNode.addLast(mtr, node2Page, offset2);
//            assertEquals(2, baseNode.getLength());
//
//            // 验证首节点是 node1
//            assertEquals(node1Page.getPageNo(), baseNode.getFirstNode().getPageNo());
//
//            // 验证尾节点是 node2
//            assertEquals(node2Page.getPageNo(), baseNode.getLastNode().getPageNo());
//
//            // 验证 node1 的指针（头节点）
//            FlstNode node1 = new FlstNode(node1Page, offset1);
//            assertFalse(node1.hasPrev());
//            assertTrue(node1.hasNext());
//            assertEquals(node2Page.getPageNo(), node1.getNextPageNo());
//
//            // 验证 node2 的指针（尾节点）
//            FlstNode node2 = new FlstNode(node2Page, offset2);
//            assertTrue(node2.hasPrev());
//            assertEquals(node1Page.getPageNo(), node2.getPrevPageNo());
//            assertFalse(node2.hasNext());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== removeFirst 测试 ====================
//
//    @Test
//    void testRemoveFirst_FromEmptyList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//
//            baseNode.initialize(mtr);
//
//            // 从空链表移除，应返回 null
//            PageId removed = baseNode.removeFirst(mtr);
//            assertNull(removed);
//            assertEquals(0, baseNode.getLength());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testRemoveFirst_FromSingleNodeList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page nodePage = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//
//            int nodeOffset = 100;
//            baseNode.addFirst(mtr, nodePage, nodeOffset);
//            assertEquals(1, baseNode.getLength());
//
//            // 移除唯一的节点
//            PageId removed = baseNode.removeFirst(mtr);
//
//            // 验证返回值
//            assertNotNull(removed);
//            assertEquals(nodePage.getPageNo(), removed.getPageNo());
//
//            // 验证链表变为空
//            assertEquals(0, baseNode.getLength());
//            assertTrue(baseNode.isEmpty());
//            assertNull(baseNode.getFirstNode());
//            assertNull(baseNode.getLastNode());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testRemoveFirst_FromTwoNodeList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page node1Page = mtr.newPage(SPACE_ID);
//            Page node2Page = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//
//            int offset1 = 100;
//            int offset2 = 200;
//
//            // 添加两个节点：node1 -> node2
//            baseNode.addLast(mtr, node1Page, offset1);
//            baseNode.addLast(mtr, node2Page, offset2);
//            assertEquals(2, baseNode.getLength());
//
//            // 移除首节点（node1）
//            PageId removed = baseNode.removeFirst(mtr);
//
//            // 验证返回值
//            assertNotNull(removed);
//            assertEquals(node1Page.getPageNo(), removed.getPageNo());
//
//            // 验证链表长度
//            assertEquals(1, baseNode.getLength());
//
//            // 验证首尾节点都是 node2
//            assertEquals(node2Page.getPageNo(), baseNode.getFirstNode().getPageNo());
//            assertEquals(node2Page.getPageNo(), baseNode.getLastNode().getPageNo());
//
//            // 验证 node2 变成孤立节点（无前驱无后继）
//            FlstNode node2 = new FlstNode(node2Page, offset2);
//            assertFalse(node2.hasPrev());
//            assertFalse(node2.hasNext());
//
//            // 验证 node1 被隔离（前驱后继都清除）
//            FlstNode node1 = new FlstNode(node1Page, offset1);
//            assertTrue(node1.isIsolated());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testRemoveFirst_FromThreeNodeList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page node1Page = mtr.newPage(SPACE_ID);
//            Page node2Page = mtr.newPage(SPACE_ID);
//            Page node3Page = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//
//            int offset1 = 100;
//            int offset2 = 200;
//            int offset3 = 300;
//
//            // 添加三个节点：node1 -> node2 -> node3
//            baseNode.addLast(mtr, node1Page, offset1);
//            baseNode.addLast(mtr, node2Page, offset2);
//            baseNode.addLast(mtr, node3Page, offset3);
//            assertEquals(3, baseNode.getLength());
//
//            // 移除首节点（node1）
//            PageId removed = baseNode.removeFirst(mtr);
//            assertEquals(node1Page.getPageNo(), removed.getPageNo());
//
//            // 验证链表长度
//            assertEquals(2, baseNode.getLength());
//
//            // 验证首节点是 node2
//            assertEquals(node2Page.getPageNo(), baseNode.getFirstNode().getPageNo());
//
//            // 验证尾节点是 node3
//            assertEquals(node3Page.getPageNo(), baseNode.getLastNode().getPageNo());
//
//            // 验证 node2 成为新的头节点
//            FlstNode node2 = new FlstNode(node2Page, offset2);
//            assertFalse(node2.hasPrev());  // 头节点无前驱
//            assertTrue(node2.hasNext());
//            assertEquals(node3Page.getPageNo(), node2.getNextPageNo());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 综合测试 ====================
//
//    @Test
//    void testMixedOperations() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//
//            baseNode.initialize(mtr);
//            assertTrue(baseNode.isEmpty());
//
//            // 创建5个节点页面
//            Page[] pages = new Page[5];
//            int[] offsets = {100, 200, 300, 400, 500};
//            for (int i = 0; i < 5; i++) {
//                pages[i] = mtr.newPage(SPACE_ID);
//            }
//
//            // addLast: 0 -> 1 -> 2
//            baseNode.addLast(mtr, pages[0], offsets[0]);
//            baseNode.addLast(mtr, pages[1], offsets[1]);
//            baseNode.addLast(mtr, pages[2], offsets[2]);
//            assertEquals(3, baseNode.getLength());
//
//            // addFirst: 3 -> 0 -> 1 -> 2
//            baseNode.addFirst(mtr, pages[3], offsets[3]);
//            assertEquals(4, baseNode.getLength());
//            assertEquals(pages[3].getPageNo(), baseNode.getFirstNode().getPageNo());
//
//            // removeFirst: 0 -> 1 -> 2
//            PageId removed = baseNode.removeFirst(mtr);
//            assertEquals(pages[3].getPageNo(), removed.getPageNo());
//            assertEquals(3, baseNode.getLength());
//
//            // addLast: 0 -> 1 -> 2 -> 4
//            baseNode.addLast(mtr, pages[4], offsets[4]);
//            assertEquals(4, baseNode.getLength());
//            assertEquals(pages[4].getPageNo(), baseNode.getLastNode().getPageNo());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== toString 测试 ====================
//
//    @Test
//    void testToString() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page headerPage = mtr.newPage(SPACE_ID);
//            Page nodePage = mtr.newPage(SPACE_ID);
//
//            FlstBaseNode baseNode = new FlstBaseNode(headerPage, 38);
//            baseNode.initialize(mtr);
//            baseNode.addFirst(mtr, nodePage, 100);
//
//            String str = baseNode.toString();
//            assertNotNull(str);
//            assertTrue(str.contains("1"));    // length
//            assertTrue(str.contains("100"));  // offset
//
//            mtr.commit();
//        }
//    }
//}
