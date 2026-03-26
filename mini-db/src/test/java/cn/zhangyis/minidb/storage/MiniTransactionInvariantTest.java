package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MiniTransactionInvariantTest extends BaseStorageTest {

    @Test
    void repeatedGetPage_reusesSinglePinAndCommitReleasesExactlyOnce() throws Exception {
        PageId pageId = allocateCommittedPage();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame first = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
            BufferFrame second = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);

            assertSame(first, second, "同一 PageId 在一个 MTR 中不应重复 pin");
            assertEquals(1, first.getPinCount(), "重复 getPage 不应增加 pinCount");

            first.writeLock();
            try {
                mtr.writeInt(first, 32, 42);
            } finally {
                first.writeUnlock();
            }
            mtr.commit();
        }

        BufferFrame probe = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        try {
            assertEquals(1, probe.getPinCount(), "commit 后页面不应残留额外 pin");
            assertTrue(probe.isDirty(), "commit 后脏页应进入 flush 管理");
            assertEquals(42, probe.buffer().getInt(32));
        } finally {
            bufferPool.unpinPage(pageId, false);
        }
    }

    @Test
    void closeWithoutCommit_abortsAndKeepsPageOutOfFlushList() throws Exception {
        PageId pageId = allocateCommittedPage();
        MiniTransaction mtr = new MiniTransaction(bufferPool);

        BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
        frame.writeLock();
        try {
            mtr.writeInt(frame, 64, 99);
        } finally {
            frame.writeUnlock();
        }
        mtr.close();

        assertEquals(MiniTransaction.State.ABORTED, mtr.getState());
        assertEquals(0, bufferPool.getDirtyCount(), "未提交的 MTR 不能把页面暴露给 FlushList");

        BufferFrame probe = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        try {
            assertEquals(1, probe.getPinCount(), "close() 回滚后页面 pin 必须完全释放");
        } finally {
            bufferPool.unpinPage(pageId, false);
        }
    }

    @Test
    void markDirty_rejectsPageOutsideMemo() {
        MiniTransaction mtr = new MiniTransaction(bufferPool);
        try {
            Page unmanaged = new Page(PageId.of(SPACE_ID, 999));
            assertThrows(PageNotManagedByMtrException.class, () -> mtr.markDirty(unmanaged));
        } finally {
            mtr.close();
        }
    }

    private PageId allocateCommittedPage() throws Exception {
        PageId pageId;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            page.setPageType(PageType.FIL_PAGE_INDEX);
            pageId = page.getPageId();
            mtr.commit();
        }
        bufferPool.flushPage(pageId);
        return pageId;
    }
}
