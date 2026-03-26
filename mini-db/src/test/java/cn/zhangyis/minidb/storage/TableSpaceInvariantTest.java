package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import cn.zhangyis.minidb.storage.space.ExtentState;
import cn.zhangyis.minidb.storage.space.FspHeaderPage;
import cn.zhangyis.minidb.storage.space.Segment;
import cn.zhangyis.minidb.storage.space.TableSpace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TableSpaceInvariantTest extends BaseStorageTest {

    @Test
    void initializeTablespace_keepsBootstrapPagesReserved() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = FspHeaderPage.fromExistingPage(
                    mtr.getPage(PageId.of(SPACE_ID, 0), BufferPool.FetchMode.READ_EXISTING));

            assertEquals(PageType.FIL_PAGE_TYPE_FSP_HDR, fsp.getPageType());
            assertEquals(PageType.FIL_PAGE_TYPE_ALLOCATED,
                    mtr.getPage(PageId.of(SPACE_ID, 1), BufferPool.FetchMode.READ_EXISTING).getPageType());
            assertEquals(PageType.FIL_PAGE_INODE,
                    mtr.getPage(PageId.of(SPACE_ID, 2), BufferPool.FetchMode.READ_EXISTING).getPageType());
            assertEquals(1L, fsp.getNextSegmentId(), "初始化后应从 segment id 1 开始分配");
            assertEquals(ExtentState.FSEG, fsp.getXdesEntry(0).getState(),
                    "extent 0 必须保留给系统页，不能重新加入 FREE 链表");
            assertTrue(fsp.getFreeList().getLength() >= 1, "初始化后必须至少存在一个空闲 extent");
            assertEquals(1, fsp.getInodesFreeList().getLength(), "第一个 INODE page 必须挂到 INODES_FREE");
        }
    }

    @Test
    void segmentLifecycle_preservesMonotonicIdsAndReclaimsExtents() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            FspHeaderPage fsp = FspHeaderPage.fromExistingPage(
                    mtr.getPage(PageId.of(SPACE_ID, 0), BufferPool.FetchMode.READ_EXISTING));
            int freeExtentsBefore = fsp.getFreeList().getLength();
            long nextSegmentIdBefore = fsp.getNextSegmentId();

            Segment segment = tableSpace.createSegment(mtr);
            assertNotNull(segment);
            assertEquals(nextSegmentIdBefore, segment.getSegmentId());

            for (int i = 0; i < 33; i++) {
                assertNotNull(segment.allocatePage(mtr), "第 33 页应触发 extent 路径而不是失败");
            }

            tableSpace.dropSegment(mtr, segment.getSegmentId());

            assertNull(tableSpace.getSegment(mtr, segment.getSegmentId()));
            assertEquals(nextSegmentIdBefore + 1, fsp.getNextSegmentId(),
                    "segment id 必须单调递增且不可复用");
            assertTrue(fsp.getFreeList().getLength() >= freeExtentsBefore,
                    "dropSegment 后 extent 应归还给表空间 FREE 链表");
            mtr.commit();
        }
    }

    @Test
    void reopenTablespace_preservesBootstrapLayout() throws Exception {
        bufferPool.close();
        diskManager.close();

        diskManager = new DiskManager(testDir);
        diskManager.openTablespace(SPACE_ID, SPACE_NAME);
        bufferPool = new BufferPool(BUFFER_POOL_SIZE, diskManager);

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = FspHeaderPage.fromExistingPage(
                    mtr.getPage(PageId.of(SPACE_ID, 0), BufferPool.FetchMode.READ_EXISTING));

            assertEquals(1L, fsp.getNextSegmentId());
            assertEquals(PageType.FIL_PAGE_TYPE_FSP_HDR, fsp.getPageType());
            assertEquals(PageType.FIL_PAGE_INODE,
                    mtr.getPage(PageId.of(SPACE_ID, 2), BufferPool.FetchMode.READ_EXISTING).getPageType());
            assertEquals(ExtentState.FSEG, fsp.getXdesEntry(0).getState());
        }
    }
}
