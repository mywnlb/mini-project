package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import cn.zhangyis.minidb.storage.space.FspHeaderPage;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.InsertUndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecordType;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UndoLogManagerInvariantTest extends BaseStorageTest {

    @Test
    void initializeUndoTablespace_setsPhysicalReadySentinel() throws Exception {
        initUndoSpace();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = FspHeaderPage.fromExistingPage(
                    mtr.getPage(PageId.of(UNDO_SPACE_ID, 0), BufferPool.FetchMode.READ_EXISTING));
            assertEquals(PageType.FIL_PAGE_TYPE_FSP_HDR, fsp.getPageType());
            assertEquals(1L, fsp.getNextSegmentId(), "Undo 表空间初始化后 nextSegmentId 必须有效");
        }
    }

    @Test
    void commitTransaction_releasesInsertUndoAndQueuesUpdateHistory() throws Exception {
        initUndoSpace();
        TransactionManager txnManager = bootInMemoryTransactionSubsystem(UNDO_SPACE_ID, 4);
        Transaction trx = txnManager.begin();

        RollbackPointer insertPtr;
        RollbackPointer updatePtr;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            insertPtr = undoLogManager.writeInsertUndo(mtr, trx, 1, new byte[]{1});
            updatePtr = undoLogManager.writeUpdateUndo(
                    mtr, trx, 1, insertPtr, new byte[]{1},
                    List.of(new UpdateUndoRecord.OldColumnValue(2, new byte[]{9, 9})));
            mtr.commit();
        }

        assertInstanceOf(InsertUndoRecord.class, undoLogManager.readUndoRecord(insertPtr));
        assertInstanceOf(UpdateUndoRecord.class, undoLogManager.readUndoRecord(updatePtr));

        txnManager.commit(trx);

        assertEquals(0, undoLogManager.getActiveInsertSegmentCount());
        assertEquals(1, undoLogManager.getActiveUpdateSegmentCount());
        assertEquals(1, undoLogManager.getHistoryListLength());
    }

    @Test
    void rollbackTransaction_returnsUndoInReverseOrder() throws Exception {
        initUndoSpace();
        TransactionManager txnManager = bootInMemoryTransactionSubsystem(UNDO_SPACE_ID, 4);
        Transaction trx = txnManager.begin();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            RollbackPointer insertPtr = undoLogManager.writeInsertUndo(mtr, trx, 1, new byte[]{1});
            RollbackPointer updatePtr = undoLogManager.writeUpdateUndo(
                    mtr, trx, 1, insertPtr, new byte[]{1},
                    List.of(new UpdateUndoRecord.OldColumnValue(2, new byte[]{8})));
            undoLogManager.writeDeleteUndo(mtr, trx, 1, updatePtr, new byte[]{1}, new byte[]{7, 6, 5});
            mtr.commit();
        }

        List<UndoRecordType> types = new ArrayList<>();
        for (UndoRecord record : undoLogManager.rollbackTransaction(trx)) {
            types.add(record.getType());
        }

        assertEquals(List.of(UndoRecordType.DELETE_MARK, UndoRecordType.UPDATE, UndoRecordType.INSERT), types);
        assertEquals(0, undoLogManager.getActiveInsertSegmentCount());
        assertEquals(0, undoLogManager.getActiveUpdateSegmentCount());
    }
}
