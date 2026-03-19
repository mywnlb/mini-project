package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.btree.BTree;
import cn.zhangyis.minidb.storage.btree.BTreeCursor;
import cn.zhangyis.minidb.storage.btree.ClusteredPrimaryKeyComparator;
import cn.zhangyis.minidb.storage.btree.CompositeKeyDef;
import cn.zhangyis.minidb.storage.btree.CompositeKeyValue;
import cn.zhangyis.minidb.storage.btree.CursorPosition;
import cn.zhangyis.minidb.storage.btree.IndexDescriptor;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogException;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.catalog.ColumnMeta;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.record.RecordHeader;
import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.reader.RowReader;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.record.format.CompactRecordFormat;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.dml.TransactionalDml;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.RecordVersion;
import cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader;
import cn.zhangyis.minidb.storage.transaction.mvcc.VisibilityChecker;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * storage-backed SQL 数据源（MVCC + Overlay 混合模式）。
 *
 * <p>读路径使用 MVCC ReadView 进行已提交数据的可见性过滤，支持 RC/RR 隔离级别。
 * DML 写入仍缓冲在 overlay 中，COMMIT 前统一 flush 到 storage（因 undo rollback 尚未实现）。
 * overlay 同时保证 read-your-writes 语义。</p>
 *
 * <p>当 UndoLogManager 为 null 时（测试环境），自动降级为无 MVCC 模式。</p>
 */
public class StorageDataSource implements DataSourceSpi, TransactionLifecycleParticipant {

    private static final int TABLE_INDEX_META_PAGE_NO = 3;

    private final CatalogManager catalogManager;
    private final String databaseName;
    private final BufferPool bufferPool;
    private final ExecutionContext executionContext;
    private final UndoLogManager undoLogManager;
    private final Map<String, TableAccess> tableCache = new LinkedHashMap<>();
    private final Map<Long, TransactionOverlay> overlays = new LinkedHashMap<>();

    public StorageDataSource(CatalogManager catalogManager, String databaseName,
                             ExecutionContext executionContext, BufferPool bufferPool) {
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.databaseName = Objects.requireNonNull(databaseName);
        this.executionContext = Objects.requireNonNull(executionContext);
        this.bufferPool = Objects.requireNonNull(bufferPool);
        this.undoLogManager = executionContext.txnManager().getUndoLogManager();
        this.executionContext.registerParticipant(this);
    }

    /**
     * 向后兼容构造器：保留旧签名，但实际按表动态解析 access，不再复用单个 TransactionalDml。
     */
    public StorageDataSource(CatalogManager catalogManager, String databaseName,
                             ExecutionContext executionContext, TransactionalDml ignored,
                             BufferPool bufferPool) {
        this(catalogManager, databaseName, executionContext, bufferPool);
    }

    @Override
    public Iterator<Row> scan(String tableName) {
        Transaction txn = executionContext.resolveTransactionForDml();
        try {
            List<Row> rows = materializeVisibleRows(openTable(tableName), txn);
            executionContext.autoCommitIfNeeded(txn);
            return rows.iterator();
        } catch (RuntimeException e) {
            executionContext.autoRollbackIfNeeded(txn);
            throw e;
        }
    }

    @Override
    public void insertRow(String tableName, Row row) {
        Transaction txn = executionContext.resolveTransactionForDml();
        try {
            TableAccess access = openTable(tableName);
            Row normalized = normalizeRow(row, access);
            RowKey key = access.requirePrimaryKey(normalized);
            ensurePrimaryKeyAvailable(access, txn, key, null);
            overlayFor(txn).table(tableName).insert(access, normalized);
            executionContext.autoCommitIfNeeded(txn);
        } catch (RuntimeException e) {
            executionContext.autoRollbackIfNeeded(txn);
            throw e;
        }
    }

    @Override
    public int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater) {
        Transaction txn = executionContext.resolveTransactionForDml();
        try {
            TableAccess access = openTable(tableName);
            TableOverlay overlay = overlayFor(txn).table(tableName);
            List<Row> visibleRows = materializeVisibleRows(access, txn);

            int count = 0;
            for (Row row : visibleRows) {
                if (!filter.test(row)) {
                    continue;
                }
                RowKey originalKey = access.keyOf(row);
                Row updated = new Row(row.columns());
                updater.accept(updated);
                Row normalized = normalizeRow(updated, access);
                RowKey key = access.requirePrimaryKey(normalized);
                if (!originalKey.equals(key)) {
                    throw new UnsupportedOperationException(
                        "Updating primary key is not yet supported by storage-backed SQL path");
                }
                ensurePrimaryKeyAvailable(access, txn, key, row);
                overlay.update(access, row, normalized);
                count++;
            }

            executionContext.autoCommitIfNeeded(txn);
            return count;
        } catch (RuntimeException e) {
            executionContext.autoRollbackIfNeeded(txn);
            throw e;
        }
    }

    @Override
    public int deleteRows(String tableName, Predicate<Row> filter) {
        Transaction txn = executionContext.resolveTransactionForDml();
        try {
            TableAccess access = openTable(tableName);
            TableOverlay overlay = overlayFor(txn).table(tableName);
            List<Row> visibleRows = materializeVisibleRows(access, txn);

            int count = 0;
            for (Row row : visibleRows) {
                if (!filter.test(row)) {
                    continue;
                }
                overlay.delete(access, row);
                count++;
            }

            executionContext.autoCommitIfNeeded(txn);
            return count;
        } catch (RuntimeException e) {
            executionContext.autoRollbackIfNeeded(txn);
            throw e;
        }
    }

    @Override
    public boolean supportsLookup(String tableName, String columnName) {
        return openTable(tableName).supportsLookup(columnName);
    }

    @Override
    public void invalidateTable(String tableName) {
        tableCache.remove(tableName.toUpperCase());
    }

    @Override
    public Iterator<Row> lookup(String tableName, String outputName, String columnName, Object value) {
        Transaction txn = executionContext.resolveTransactionForDml();
        try {
            if (value == null) {
                executionContext.autoCommitIfNeeded(txn);
                return List.<Row>of().iterator();
            }
            TableAccess access = openTable(tableName);
            if (!access.supportsLookup(columnName)) {
                throw new UnsupportedOperationException(
                    "Lookup only supports single-column primary key on table " + tableName);
            }

            RowKey key = access.encodeLookupKey(columnName, value);
            TableOverlay overlay = overlayOrNull(txn, tableName);
            List<Row> result = new ArrayList<>(1);

            if (overlay != null) {
                PendingChange pending = overlay.visibleByCurrentKey(key);
                if (pending != null) {
                    result.add(renameQualifier(pending.row(), tableName, outputName));
                    executionContext.autoCommitIfNeeded(txn);
                    return result.iterator();
                }
                if (overlay.hidesCommittedRow(key)) {
                    executionContext.autoCommitIfNeeded(txn);
                    return result.iterator();
                }
            }

            Row committed = loadCommittedRow(access, key, txn);
            if (committed != null) {
                result.add(renameQualifier(committed, tableName, outputName));
            }
            executionContext.autoCommitIfNeeded(txn);
            return result.iterator();
        } catch (RuntimeException e) {
            executionContext.autoRollbackIfNeeded(txn);
            throw e;
        }
    }

    @Override
    public void beforeCommit(Transaction txn) {
        TransactionOverlay overlay = overlays.get(txn.getId().getValue());
        if (overlay == null || overlay.isEmpty()) {
            return;
        }

        for (Map.Entry<String, TableOverlay> entry : overlay.tables.entrySet()) {
            flushTable(txn, openTable(entry.getKey()), entry.getValue());
        }
    }

    @Override
    public void afterCommit(Transaction txn) {
        overlays.remove(txn.getId().getValue());
    }

    @Override
    public void beforeRollback(Transaction txn) {
        overlays.remove(txn.getId().getValue());
    }

    private void flushTable(Transaction txn, TableAccess access, TableOverlay overlay) {
        for (PendingChange change : overlay.pendingChanges()) {
            switch (change.kind()) {
                case INSERT -> insertCommitted(access, txn, change.row());
                case UPDATE -> {
                    if (!change.originalKey().equals(change.currentKey())) {
                        deleteCommitted(access, txn, change.originalKey());
                        insertCommitted(access, txn, change.row());
                    } else {
                        updateCommitted(access, txn, change.currentKey(), change.row());
                    }
                }
                case DELETE -> deleteCommitted(access, txn, change.originalKey());
            }
        }
    }

    private void insertCommitted(TableAccess access, Transaction txn, Row row) {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TransactionalDml dml = access.dml();
            boolean inserted = dml.insert(mtr, txn, rowToTuple(row, access), access.keyOf(row).bytes());
            if (!inserted) {
                throw new IllegalStateException("Duplicate primary key on table " + access.tableName());
            }
            access.flushPrimaryMetadata(dml.getBTree(), mtr);
            mtr.commit();
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage insert failed for table: " + access.tableName(), e);
        }
    }

    private void updateCommitted(TableAccess access, Transaction txn, RowKey key, Row row) {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TransactionalDml dml = access.dml();
            boolean updated = dml.update(mtr, txn, key.bytes(), rowToTuple(row, access), List.of());
            if (!updated) {
                throw new IllegalStateException("Primary key not found on table " + access.tableName());
            }
            access.flushPrimaryMetadata(dml.getBTree(), mtr);
            mtr.commit();
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage update failed for table: " + access.tableName(), e);
        }
    }

    private void deleteCommitted(TableAccess access, Transaction txn, RowKey key) {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TransactionalDml dml = access.dml();
            boolean deleted = dml.delete(mtr, txn, key.bytes());
            if (!deleted) {
                throw new IllegalStateException("Primary key not found on table " + access.tableName());
            }
            access.flushPrimaryMetadata(dml.getBTree(), mtr);
            mtr.commit();
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage delete failed for table: " + access.tableName(), e);
        }
    }

    private void ensurePrimaryKeyAvailable(TableAccess access, Transaction txn, RowKey candidateKey, Row sourceRow) {
        RowKey sourceKey = sourceRow == null ? null : access.keyOf(sourceRow);
        TableOverlay overlay = overlayOrNull(txn, access.tableName());

        if (overlay != null) {
            PendingChange visibleChange = overlay.visibleByCurrentKey(candidateKey);
            if (visibleChange != null && !visibleChange.matchesSourceRow(sourceKey)) {
                throw new IllegalStateException("Duplicate primary key on table " + access.tableName());
            }
        }

        Row committed = loadCommittedRow(access, candidateKey, txn);
        if (committed == null) {
            return;
        }

        boolean sameRow = sourceKey != null && sourceKey.equals(candidateKey);
        boolean hiddenByOverlay = overlay != null && overlay.hidesCommittedRow(candidateKey);
        if (!sameRow && !hiddenByOverlay) {
            throw new IllegalStateException("Duplicate primary key on table " + access.tableName());
        }
    }

    private List<Row> materializeVisibleRows(TableAccess access, Transaction txn) {
        TableOverlay overlay = overlayOrNull(txn, access.tableName());
        List<IndexedRow> committedRows = loadCommittedRows(access, txn);
        List<Row> visible = new ArrayList<>(committedRows.size());

        for (IndexedRow committed : committedRows) {
            if (overlay != null && overlay.hidesCommittedRow(committed.key())) {
                continue;
            }
            visible.add(committed.row());
        }

        if (overlay != null) {
            for (PendingChange change : overlay.visibleRows()) {
                visible.add(change.row());
            }
        }

        return visible;
    }

    private List<IndexedRow> loadCommittedRows(TableAccess access, Transaction txn) {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BTree btree = access.openPrimaryTree(mtr);
            BTreeCursor cursor = btree.openCursor(mtr);
            cursor.seekFirst();

            // I1: ReadView 为 null 时降级为无 MVCC 模式
            ReadView readView = txn.getOrCreateReadView();
            VersionChainReader chainReader = access.versionChainReader();

            List<IndexedRow> rows = new ArrayList<>();
            while (cursor.isValid()) {
                CursorPosition position = cursor.getPosition();
                Page page = mtr.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
                ByteBuffer buf = page.getBuffer();
                int recordOffset = position.getRecordOffset();
                RecordHeader header = RecordHeader.readFrom(buf, recordOffset);

                if (readView == null) {
                    // 降级：无 ReadView 时保持旧行为（仅 isDeleted 过滤）
                    if (!header.isDeleted()) {
                        DataTuple tuple = access.rowReader().read(buf, recordOffset);
                        rows.add(new IndexedRow(new RowKey(cursor.getKey()),
                            tupleToRow(access.tableName(), tuple, access.columns())));
                    }
                } else {
                    // MVCC 可见性检查
                    int dataStart = recordOffset + RecordHeader.SIZE;
                    long trxId = CompactRecordFormat.readTrxId(buf, dataStart + SystemLayout.OFF_TRX_ID);

                    if (VisibilityChecker.isVisible(trxId, readView)) {
                        // 当前版本可见，检查删除标记
                        if (!header.isDeleted()) {
                            DataTuple tuple = access.rowReader().read(buf, recordOffset);
                            rows.add(new IndexedRow(new RowKey(cursor.getKey()),
                                tupleToRow(access.tableName(), tuple, access.columns())));
                        }
                    } else if (chainReader != null) {
                        // 当前版本不可见，遍历版本链查找可见版本
                        long rollPtrValue = CompactRecordFormat.readRollPtr(
                            buf, dataStart + SystemLayout.OFF_ROLL_PTR);
                        RollbackPointer rollPtr = RollbackPointer.decode(rollPtrValue);
                        Optional<RecordVersion> visibleVersion =
                            chainReader.findVisibleVersion(rollPtr, readView);
                        if (visibleVersion.isPresent() && !visibleVersion.get().isDeleteMarked()) {
                            DataTuple tuple = visibleVersion.get().toDataTuple();
                            rows.add(new IndexedRow(new RowKey(cursor.getKey()),
                                tupleToRow(access.tableName(), tuple, access.columns())));
                        }
                    }
                    // else: 不可见 + 无版本链 → 跳过
                }
                cursor.next();
            }
            cursor.close();
            mtr.commit();
            return rows;
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage scan failed for table: " + access.tableName(), e);
        }
    }

    private Row loadCommittedRow(TableAccess access, RowKey key, Transaction txn) {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BTree btree = access.openPrimaryTree(mtr);
            var result = btree.search(key.bytes(), mtr);
            if (!result.isExactMatch()) {
                mtr.commit();
                return null;
            }

            Page page = mtr.getPage(result.getPageId(), BufferPool.FetchMode.READ_EXISTING);
            ByteBuffer buf = page.getBuffer();
            int recordOffset = result.getRecordOffset();
            RecordHeader header = RecordHeader.readFrom(buf, recordOffset);

            ReadView readView = txn.getOrCreateReadView();
            if (readView == null) {
                // 降级：无 ReadView 时保持旧行为
                if (header.isDeleted()) {
                    mtr.commit();
                    return null;
                }
                DataTuple tuple = access.rowReader().read(buf, recordOffset);
                mtr.commit();
                return tupleToRow(access.tableName(), tuple, access.columns());
            }

            // MVCC 可见性检查
            int dataStart = recordOffset + RecordHeader.SIZE;
            long trxId = CompactRecordFormat.readTrxId(buf, dataStart + SystemLayout.OFF_TRX_ID);

            if (VisibilityChecker.isVisible(trxId, readView)) {
                if (header.isDeleted()) {
                    mtr.commit();
                    return null;
                }
                DataTuple tuple = access.rowReader().read(buf, recordOffset);
                mtr.commit();
                return tupleToRow(access.tableName(), tuple, access.columns());
            }

            // 当前版本不可见，遍历版本链
            VersionChainReader chainReader = access.versionChainReader();
            if (chainReader != null) {
                long rollPtrValue = CompactRecordFormat.readRollPtr(
                    buf, dataStart + SystemLayout.OFF_ROLL_PTR);
                RollbackPointer rollPtr = RollbackPointer.decode(rollPtrValue);
                Optional<RecordVersion> visibleVersion =
                    chainReader.findVisibleVersion(rollPtr, readView);
                if (visibleVersion.isPresent() && !visibleVersion.get().isDeleteMarked()) {
                    DataTuple tuple = visibleVersion.get().toDataTuple();
                    mtr.commit();
                    return tupleToRow(access.tableName(), tuple, access.columns());
                }
            }

            mtr.commit();
            return null;
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage lookup failed for table: " + access.tableName(), e);
        }
    }

    private Row normalizeRow(Row row, TableAccess access) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ColumnMeta column : access.columns()) {
            String qualifiedName = access.tableName() + "." + column.getName();
            Object value = row.get(qualifiedName);
            if (value == null) {
                value = row.get(column.getName());
            }
            values.put(qualifiedName, value);
        }
        return new Row(values);
    }

    private DataTuple rowToTuple(Row row, TableAccess access) {
        DataField[] fields = new DataField[access.columns().size()];
        for (int i = 0; i < access.columns().size(); i++) {
            ColumnMeta column = access.columns().get(i);
            Object value = row.get(access.tableName() + "." + column.getName());
            if (value == null) {
                value = row.get(column.getName());
            }
            fields[i] = toDataField(value, column);
        }
        return DataTuple.of(fields);
    }

    private DataField toDataField(Object value, ColumnMeta column) {
        if (value == null) {
            return DataField.nullField(column.getType());
        }
        FieldKind kind = column.getKind();
        return switch (kind) {
            case INT -> DataField.intField(((Number) value).intValue());
            case BIGINT -> DataField.bigintField(((Number) value).longValue());
            case TINYINT -> DataField.tinyintField(((Number) value).byteValue());
            case SMALLINT -> DataField.smallintField(((Number) value).shortValue());
            case VARCHAR -> DataField.varcharField(String.valueOf(value));
            case CHAR -> DataField.charField(String.valueOf(value), column.getType().getLength());
            case TEXT -> DataField.textField(String.valueOf(value));
            case VARBINARY -> DataField.varbinaryField((byte[]) value);
            case BINARY -> DataField.binaryField((byte[]) value, column.getType().getLength());
            case BLOB -> DataField.blobField((byte[]) value);
        };
    }

    private Row tupleToRow(String tableName, DataTuple tuple, List<ColumnMeta> columns) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < columns.size() && i < tuple.getFieldCount(); i++) {
            ColumnMeta column = columns.get(i);
            DataField field = tuple.getField(i);
            values.put(tableName + "." + column.getName(), field == null ? null : field.getValue());
        }
        return new Row(values);
    }

    private Row renameQualifier(Row row, String sourceTable, String outputName) {
        if (sourceTable.equalsIgnoreCase(outputName)) {
            return row;
        }

        Map<String, Object> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.columns().entrySet()) {
            String key = entry.getKey();
            if (key.contains(".")) {
                String[] parts = key.split("\\.", 2);
                if (parts[0].equalsIgnoreCase(sourceTable)) {
                    key = outputName + "." + parts[1];
                }
            }
            renamed.put(key, entry.getValue());
        }
        return new Row(renamed);
    }

    private TransactionOverlay overlayFor(Transaction txn) {
        return overlays.computeIfAbsent(txn.getId().getValue(), ignored -> new TransactionOverlay());
    }

    private TableOverlay overlayOrNull(Transaction txn, String tableName) {
        TransactionOverlay overlay = overlays.get(txn.getId().getValue());
        return overlay == null ? null : overlay.tables.get(tableName.toUpperCase());
    }

    private TableAccess openTable(String tableName) {
        return tableCache.computeIfAbsent(tableName.toUpperCase(), ignored -> loadTableAccess(tableName));
    }

    private TableAccess loadTableAccess(String tableName) {
        try {
            TableDescriptor table = catalogManager.getTable(databaseName, tableName);
            RecordSchema schema = table.getSchemaRegistry().getCurrentSchema();
            SystemLayout layout = SystemLayout.WITH_PK;

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                IndexManager indexManager = new IndexManager(bufferPool, table.getSpaceId(), TABLE_INDEX_META_PAGE_NO);
                indexManager.initialize(mtr);
                IndexDescriptor primary = indexManager.getDescriptor(table.getPrimaryIndexId());
                if (primary == null) {
                    throw new IllegalStateException("Primary index descriptor missing for table " + tableName);
                }
                mtr.commit();
                return new TableAccess(
                    bufferPool,
                    table,
                    schema,
                    layout,
                    new RowReader(table.getSchemaRegistry(), layout),
                    primary.toCompositeKeyDef(),
                    primary,
                    new ClusteredPrimaryKeyComparator(primary.toCompositeKeyDef(), layout.userColumnsOffset()),
                    undoLogManager
                );
            }
        } catch (MiniDbException e) {
            throw new RuntimeException("Failed to open table access for " + tableName, e);
        }
    }

    private static final class TableAccess {
        private final BufferPool bufferPool;
        private final TableDescriptor table;
        private final RecordSchema schema;
        private final SystemLayout layout;
        private final RowReader rowReader;
        private final CompositeKeyDef primaryKeyDef;
        private final IndexDescriptor primaryIndex;
        private final ClusteredPrimaryKeyComparator primaryComparator;
        private final UndoLogManager undoLogManager;

        private TableAccess(BufferPool bufferPool, TableDescriptor table, RecordSchema schema, SystemLayout layout,
                            RowReader rowReader, CompositeKeyDef primaryKeyDef,
                            IndexDescriptor primaryIndex,
                            ClusteredPrimaryKeyComparator primaryComparator,
                            UndoLogManager undoLogManager) {
            this.bufferPool = bufferPool;
            this.table = table;
            this.schema = schema;
            this.layout = layout;
            this.rowReader = rowReader;
            this.primaryKeyDef = primaryKeyDef;
            this.primaryIndex = primaryIndex;
            this.primaryComparator = primaryComparator;
            this.undoLogManager = undoLogManager;
        }

        String tableName() {
            return table.getTableName();
        }

        List<ColumnMeta> columns() {
            return table.getColumns();
        }

        RowReader rowReader() {
            return rowReader;
        }

        boolean supportsLookup(String columnName) {
            return primaryIndex.getColumns().size() == 1
                && primaryIndex.getColumns().get(0).getName().equalsIgnoreCase(columnName);
        }

        RowKey requirePrimaryKey(Row row) {
            Object[] values = primaryKeyValues(row);
            for (Object value : values) {
                if (value == null) {
                    throw new IllegalArgumentException("Primary key cannot be null for table " + tableName());
                }
            }
            RowKey key = keyOf(values);
            if (key.bytes().length == 0) {
                throw new IllegalArgumentException("Primary key cannot be empty for table " + tableName());
            }
            return key;
        }

        RowKey keyOf(Row row) {
            return keyOf(primaryKeyValues(row));
        }

        private Object[] primaryKeyValues(Row row) {
            Object[] values = new Object[primaryIndex.getColumns().size()];
            for (int i = 0; i < primaryIndex.getColumns().size(); i++) {
                String columnName = primaryIndex.getColumns().get(i).getName();
                values[i] = row.get(tableName() + "." + columnName);
                if (values[i] == null) {
                    values[i] = row.get(columnName);
                }
            }
            return values;
        }

        private RowKey keyOf(Object[] values) {
            return new RowKey(new CompositeKeyValue(primaryKeyDef, values).encode());
        }

        RowKey encodeLookupKey(String columnName, Object value) {
            if (!supportsLookup(columnName)) {
                throw new UnsupportedOperationException("Lookup not supported on column " + columnName);
            }
            if (value == null) {
                throw new IllegalArgumentException("Lookup value cannot be null for table " + tableName());
            }
            return new RowKey(new CompositeKeyValue(primaryKeyDef, value).encode());
        }

        BTree openPrimaryTree(MiniTransaction mtr) throws MiniDbException {
            return new BTree(primaryIndex.toBTreeMetadata(), bufferPool, primaryComparator);
        }

        VersionChainReader versionChainReader() {
            if (undoLogManager == null) {
                return null;
            }
            return new VersionChainReader(undoLogManager.createUndoRecordReader());
        }

        TransactionalDml dml() {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree tree = openPrimaryTree(mtr);
                mtr.commit();
                // 始终使用 registry 当前最新 schema，保证 update 写入的 rowVersion 与 currentSchema 对齐
                RecordSchema currentSchema = table.getSchemaRegistry().getCurrentSchema();
                return new TransactionalDml(tree, undoLogManager, bufferPool, currentSchema, layout, (int) table.getTableId());
            } catch (MiniDbException e) {
                throw new RuntimeException("Failed to open primary index for DML on table " + tableName(), e);
            }
        }

        void flushPrimaryMetadata(BTree tree, MiniTransaction mtr) throws MiniDbException {
            IndexManager indexManager = new IndexManager(bufferPool, table.getSpaceId(), TABLE_INDEX_META_PAGE_NO);
            indexManager.initialize(mtr);
            indexManager.updateIndexMetadata(tree, mtr);
        }
    }

    private static final class TransactionOverlay {
        private final Map<String, TableOverlay> tables = new LinkedHashMap<>();

        TableOverlay table(String tableName) {
            return tables.computeIfAbsent(tableName.toUpperCase(), ignored -> new TableOverlay());
        }

        boolean isEmpty() {
            return tables.values().stream().allMatch(TableOverlay::isEmpty);
        }
    }

    private static final class TableOverlay {
        private final LinkedHashMap<RowKey, PendingChange> pending = new LinkedHashMap<>();
        private final LinkedHashMap<RowKey, PendingChange> visible = new LinkedHashMap<>();

        boolean isEmpty() {
            return pending.isEmpty();
        }

        void insert(TableAccess access, Row row) {
            RowKey key = access.keyOf(row);
            PendingChange change = new PendingChange(ChangeKind.INSERT, key, key, row, false);
            pending.put(key, change);
            visible.put(key, change);
        }

        void update(TableAccess access, Row before, Row after) {
            RowKey oldKey = access.keyOf(before);
            RowKey newKey = access.keyOf(after);
            PendingChange current = visible.remove(oldKey);

            if (current == null) {
                PendingChange next = new PendingChange(ChangeKind.UPDATE, oldKey, newKey, after, true);
                pending.put(oldKey, next);
                visible.put(newKey, next);
                return;
            }

            if (current.kind() == ChangeKind.INSERT) {
                pending.remove(current.identityKey());
                PendingChange next = new PendingChange(ChangeKind.INSERT, newKey, newKey, after, false);
                pending.put(newKey, next);
                visible.put(newKey, next);
                return;
            }

            PendingChange next = new PendingChange(ChangeKind.UPDATE, current.originalKey(), newKey, after, true);
            pending.put(current.identityKey(), next);
            visible.put(newKey, next);
        }

        void delete(TableAccess access, Row row) {
            RowKey key = access.keyOf(row);
            PendingChange current = visible.remove(key);

            if (current == null) {
                pending.put(key, new PendingChange(ChangeKind.DELETE, key, key, null, true));
                return;
            }

            if (current.kind() == ChangeKind.INSERT) {
                pending.remove(current.identityKey());
                return;
            }

            pending.put(current.identityKey(),
                new PendingChange(ChangeKind.DELETE, current.originalKey(), current.originalKey(), null, true));
        }

        PendingChange visibleByCurrentKey(RowKey key) {
            return visible.get(key);
        }

        boolean hidesCommittedRow(RowKey committedKey) {
            PendingChange change = pending.get(committedKey);
            return change != null && change.fromCommittedRow();
        }

        List<PendingChange> visibleRows() {
            return new ArrayList<>(visible.values());
        }

        List<PendingChange> pendingChanges() {
            return new ArrayList<>(pending.values());
        }
    }

    private enum ChangeKind {
        INSERT, UPDATE, DELETE
    }

    private record PendingChange(ChangeKind kind, RowKey originalKey, RowKey currentKey,
                                 Row row, boolean fromCommittedRow) {
        RowKey identityKey() {
            return fromCommittedRow ? originalKey : currentKey;
        }

        boolean matchesSourceRow(RowKey sourceKey) {
            return sourceKey != null
                && (sourceKey.equals(originalKey) || sourceKey.equals(currentKey));
        }
    }

    private record IndexedRow(RowKey key, Row row) {
    }

    private static final class RowKey {
        private final byte[] bytes;

        private RowKey(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof RowKey other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }
}
