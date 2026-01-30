package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 索引管理器测试
 *
 * @author MiniDB
 */
class IndexManagerTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private int metaPageNo;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(100, diskManager);

        // 分配元数据页面
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page metaPage = mtr.newPage(0);
            metaPageNo = metaPage.getPageId().getPageNo();
            mtr.commit();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
    }

    // ==================== IndexDescriptor 测试 ====================

    @Nested
    @DisplayName("IndexDescriptor 测试")
    class IndexDescriptorTests {

        @Test
        @DisplayName("创建索引描述符")
        void testCreateDescriptor() {
            List<IndexDescriptor.ColumnDescriptor> columns = List.of(
                    new IndexDescriptor.ColumnDescriptor("id", ColumnType.INT, 4, false, false)
            );

            IndexDescriptor desc = new IndexDescriptor(
                    1L, "pk_users", 100L, 0, 10, IndexType.PRIMARY, columns
            );

            assertEquals(1L, desc.getIndexId());
            assertEquals("pk_users", desc.getIndexName());
            assertEquals(100L, desc.getTableId());
            assertEquals(0, desc.getSpaceId());
            assertEquals(10, desc.getRootPageNo());
            assertEquals(IndexType.PRIMARY, desc.getIndexType());
            assertTrue(desc.isUnique());
            assertTrue(desc.isPrimary());
            assertFalse(desc.isDeleted());
        }

        @Test
        @DisplayName("序列化和反序列化")
        void testSerializeAndDeserialize() {
            List<IndexDescriptor.ColumnDescriptor> columns = List.of(
                    new IndexDescriptor.ColumnDescriptor("tenant_id", ColumnType.INT, 4, false, false),
                    new IndexDescriptor.ColumnDescriptor("user_id", ColumnType.BIGINT, 8, false, false),
                    new IndexDescriptor.ColumnDescriptor("name", ColumnType.VARCHAR, 100, true, false)
            );

            IndexDescriptor original = new IndexDescriptor(
                    42L, "idx_tenant_user", 10L, 0, 5, IndexType.UNIQUE, columns
            );
            original.setTreeHeight(3);
            original.setRecordCount(1000);

            byte[] serialized = original.serialize();
            IndexDescriptor restored = IndexDescriptor.deserialize(serialized);

            assertEquals(original.getIndexId(), restored.getIndexId());
            assertEquals(original.getIndexName(), restored.getIndexName());
            assertEquals(original.getTableId(), restored.getTableId());
            assertEquals(original.getRootPageNo(), restored.getRootPageNo());
            assertEquals(original.getIndexType(), restored.getIndexType());
            assertEquals(original.getTreeHeight(), restored.getTreeHeight());
            assertEquals(original.getRecordCount(), restored.getRecordCount());
            assertEquals(original.getColumns().size(), restored.getColumns().size());
        }

        @Test
        @DisplayName("转换为 BTreeMetadata")
        void testToBTreeMetadata() {
            IndexDescriptor desc = IndexDescriptor.createIntIndex(
                    1L, "pk_id", 10L, 0, 5, IndexType.PRIMARY, "id"
            );
            desc.setTreeHeight(2);
            desc.setRecordCount(500);

            BTreeMetadata metadata = desc.toBTreeMetadata();

            assertEquals(1L, metadata.getIndexId());
            assertEquals(0, metadata.getSpaceId());
            assertEquals(5, metadata.getRootPageNo());
            assertEquals(2, metadata.getTreeHeight());
            assertEquals(500, metadata.getRecordCount());
        }

        @Test
        @DisplayName("转换为 CompositeKeyDef")
        void testToCompositeKeyDef() {
            List<IndexDescriptor.ColumnDescriptor> columns = List.of(
                    new IndexDescriptor.ColumnDescriptor("a", ColumnType.INT, 4, false, false),
                    new IndexDescriptor.ColumnDescriptor("b", ColumnType.INT, 4, false, false)
            );

            IndexDescriptor desc = new IndexDescriptor(
                    1L, "idx_ab", 10L, 0, 5, IndexType.SECONDARY, columns
            );

            CompositeKeyDef keyDef = desc.toCompositeKeyDef();

            assertEquals(2, keyDef.getColumnCount());
            assertEquals("a", keyDef.getColumn(0).getName());
            assertEquals("b", keyDef.getColumn(1).getName());
        }
    }

    // ==================== IndexMetaPage 测试 ====================

    @Nested
    @DisplayName("IndexMetaPage 测试")
    class IndexMetaPageTests {

        @Test
        @DisplayName("初始化元数据页面")
        void testInitPage() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PageId pageId = new PageId(0, metaPageNo);
                BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexMetaPage.initPage(frame, mtr);
                    assertTrue(IndexMetaPage.isValid(frame));
                    assertEquals(0, IndexMetaPage.readIndexCount(frame));
                    assertEquals(0, IndexMetaPage.readNextPage(frame));
                } finally {
                    frame.writeUnlock();
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("写入和读取描述符")
        void testWriteAndReadDescriptor() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PageId pageId = new PageId(0, metaPageNo);
                BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexMetaPage.initPage(frame, mtr);

                    // 写入描述符
                    IndexDescriptor desc1 = IndexDescriptor.createIntIndex(
                            1L, "idx1", 10L, 0, 5, IndexType.PRIMARY, "id"
                    );
                    IndexDescriptor desc2 = IndexDescriptor.createIntIndex(
                            2L, "idx2", 10L, 0, 6, IndexType.SECONDARY, "name"
                    );

                    assertTrue(IndexMetaPage.writeDescriptor(frame, desc1));
                    assertTrue(IndexMetaPage.writeDescriptor(frame, desc2));

                    assertEquals(2, IndexMetaPage.readIndexCount(frame));

                    // 读取所有描述符
                    List<IndexDescriptor> descriptors = IndexMetaPage.readAllDescriptors(frame);
                    assertEquals(2, descriptors.size());
                    assertEquals("idx1", descriptors.get(0).getIndexName());
                    assertEquals("idx2", descriptors.get(1).getIndexName());
                } finally {
                    frame.writeUnlock();
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("查找描述符")
        void testFindDescriptor() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PageId pageId = new PageId(0, metaPageNo);
                BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexMetaPage.initPage(frame, mtr);

                    IndexDescriptor desc = IndexDescriptor.createIntIndex(
                            42L, "test_idx", 10L, 0, 5, IndexType.UNIQUE, "col"
                    );
                    IndexMetaPage.writeDescriptor(frame, desc);

                    // 查找存在的
                    IndexDescriptor found = IndexMetaPage.findDescriptor(frame, 42L);
                    assertNotNull(found);
                    assertEquals("test_idx", found.getIndexName());

                    // 查找不存在的
                    IndexDescriptor notFound = IndexMetaPage.findDescriptor(frame, 999L);
                    assertNull(notFound);
                } finally {
                    frame.writeUnlock();
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除描述符")
        void testDeleteDescriptor() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PageId pageId = new PageId(0, metaPageNo);
                BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();

                try {
                    IndexMetaPage.initPage(frame, mtr);

                    IndexDescriptor desc = IndexDescriptor.createIntIndex(
                            1L, "to_delete", 10L, 0, 5, IndexType.SECONDARY, "col"
                    );
                    IndexMetaPage.writeDescriptor(frame, desc);

                    // 删除
                    assertTrue(IndexMetaPage.deleteDescriptor(frame, 1L));

                    // 读取应该不包含已删除的
                    List<IndexDescriptor> descriptors = IndexMetaPage.readAllDescriptors(frame);
                    assertEquals(0, descriptors.size());
                } finally {
                    frame.writeUnlock();
                }

                mtr.commit();
            }
        }
    }

    // ==================== IndexManager 测试 ====================

    @Nested
    @DisplayName("IndexManager 测试")
    class IndexManagerTests {

        @Test
        @DisplayName("初始化索引管理器")
        void testInitialize() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                assertEquals(0, manager.getIndexCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("创建索引")
        void testCreateIndex() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                BTree btree = manager.createIntIndex("pk_users", 1L, IndexType.PRIMARY, "id", mtr);

                assertNotNull(btree);
                assertEquals(1, manager.getIndexCount());
                assertTrue(manager.indexExists(1L, "pk_users"));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("创建多个索引")
        void testCreateMultipleIndexes() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                manager.createIntIndex("pk_users", 1L, IndexType.PRIMARY, "id", mtr);
                manager.createIntIndex("idx_age", 1L, IndexType.SECONDARY, "age", mtr);
                manager.createIntIndex("pk_orders", 2L, IndexType.PRIMARY, "id", mtr);

                assertEquals(3, manager.getIndexCount());

                // 获取表的索引
                List<IndexDescriptor> table1Indexes = manager.getTableIndexes(1L);
                assertEquals(2, table1Indexes.size());

                List<IndexDescriptor> table2Indexes = manager.getTableIndexes(2L);
                assertEquals(1, table2Indexes.size());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("打开索引")
        void testOpenIndex() throws Exception {
            long indexId;

            // 创建索引
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                BTree btree = manager.createIntIndex("pk_test", 1L, IndexType.PRIMARY, "id", mtr);
                indexId = btree.getMetadata().getIndexId();

                // 插入一些数据
                for (int i = 1; i <= 10; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                manager.updateIndexMetadata(btree, mtr);
                mtr.commit();
            }

            // 重新打开
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                BTree btree = manager.openIndex(indexId, mtr);
                assertNotNull(btree);

                // 验证数据
                assertTrue(btree.containsKey(IntKeyComparator.intToBytes(5), mtr));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("通过名称打开索引")
        void testOpenIndexByName() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                manager.createIntIndex("my_index", 10L, IndexType.SECONDARY, "col", mtr);

                BTree btree = manager.openIndexByName(10L, "my_index", mtr);
                assertNotNull(btree);

                mtr.commit();
            }
        }

        @Test
        @DisplayName("删除索引")
        void testDropIndex() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                BTree btree = manager.createIntIndex("to_drop", 1L, IndexType.SECONDARY, "col", mtr);
                long indexId = btree.getMetadata().getIndexId();

                assertEquals(1, manager.getIndexCount());

                assertTrue(manager.dropIndex(indexId, mtr));
                assertEquals(0, manager.getIndexCount());
                assertFalse(manager.indexExists(indexId));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("通过名称删除索引")
        void testDropIndexByName() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                manager.createIntIndex("drop_me", 5L, IndexType.SECONDARY, "col", mtr);

                assertTrue(manager.indexExists(5L, "drop_me"));
                assertTrue(manager.dropIndexByName(5L, "drop_me", mtr));
                assertFalse(manager.indexExists(5L, "drop_me"));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("重复索引名称应失败")
        void testDuplicateIndexNameShouldFail() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                manager.createIntIndex("same_name", 1L, IndexType.PRIMARY, "id", mtr);

                assertThrows(IllegalArgumentException.class, () -> {
                    manager.createIntIndex("same_name", 1L, IndexType.SECONDARY, "other", mtr);
                });

                // 不同表可以有相同名称
                assertDoesNotThrow(() -> {
                    manager.createIntIndex("same_name", 2L, IndexType.PRIMARY, "id", mtr);
                });

                mtr.commit();
            }
        }

        @Test
        @DisplayName("获取索引描述符")
        void testGetDescriptor() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                BTree btree = manager.createIntIndex("test_idx", 1L, IndexType.UNIQUE, "col", mtr);
                long indexId = btree.getMetadata().getIndexId();

                IndexDescriptor desc = manager.getDescriptor(indexId);
                assertNotNull(desc);
                assertEquals("test_idx", desc.getIndexName());
                assertEquals(IndexType.UNIQUE, desc.getIndexType());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("创建复合键索引")
        void testCreateCompositeKeyIndex() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                List<IndexDescriptor.ColumnDescriptor> columns = List.of(
                        new IndexDescriptor.ColumnDescriptor("tenant_id", ColumnType.INT, 4, false, false),
                        new IndexDescriptor.ColumnDescriptor("user_id", ColumnType.INT, 4, false, false)
                );

                BTree btree = manager.createIndex("idx_tenant_user", 1L, IndexType.UNIQUE, columns, mtr);
                assertNotNull(btree);

                IndexDescriptor desc = manager.getDescriptor(btree.getMetadata().getIndexId());
                assertEquals(2, desc.getColumns().size());

                mtr.commit();
            }
        }
    }

    // ==================== 集成测试 ====================

    @Nested
    @DisplayName("集成测试")
    class IntegrationTests {

        @Test
        @DisplayName("索引持久化和恢复")
        void testPersistenceAndRecovery() throws Exception {
            long indexId;

            // 创建索引并插入数据
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                BTree btree = manager.createIntIndex("persistent_idx", 1L, IndexType.PRIMARY, "id", mtr);
                indexId = btree.getMetadata().getIndexId();

                for (int i = 1; i <= 20; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }

                manager.updateIndexMetadata(btree, mtr);
                mtr.commit();
            }

            // 刷新到磁盘
            bufferPool.flushAllPages();

            // 模拟重启：创建新的 IndexManager
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                // 验证索引恢复
                assertEquals(1, manager.getIndexCount());
                assertTrue(manager.indexExists(indexId));

                // 打开并验证数据
                BTree btree = manager.openIndex(indexId, mtr);
                assertNotNull(btree);

                for (int i = 1; i <= 20; i++) {
                    assertTrue(btree.containsKey(IntKeyComparator.intToBytes(i), mtr),
                            "Key " + i + " should exist");
                }

                mtr.commit();
            }
        }

        @Test
        @DisplayName("多表多索引场景")
        void testMultiTableMultiIndex() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager manager = new IndexManager(bufferPool, 0, metaPageNo);
                manager.initialize(mtr);

                // 表1：users
                manager.createIntIndex("pk_users", 1L, IndexType.PRIMARY, "id", mtr);
                manager.createIntIndex("idx_users_age", 1L, IndexType.SECONDARY, "age", mtr);

                // 表2：orders
                manager.createIntIndex("pk_orders", 2L, IndexType.PRIMARY, "id", mtr);
                manager.createIntIndex("idx_orders_user", 2L, IndexType.SECONDARY, "user_id", mtr);
                manager.createIntIndex("idx_orders_date", 2L, IndexType.SECONDARY, "date", mtr);

                // 表3：products
                manager.createIntIndex("pk_products", 3L, IndexType.PRIMARY, "id", mtr);

                assertEquals(6, manager.getIndexCount());
                assertEquals(2, manager.getTableIndexes(1L).size());
                assertEquals(3, manager.getTableIndexes(2L).size());
                assertEquals(1, manager.getTableIndexes(3L).size());

                // 删除表2的一个索引
                manager.dropIndexByName(2L, "idx_orders_date", mtr);
                assertEquals(5, manager.getIndexCount());
                assertEquals(2, manager.getTableIndexes(2L).size());

                mtr.commit();
            }
        }
    }
}
