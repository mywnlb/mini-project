package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.catalog.ColumnMeta;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.dml.TransactionalDml;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.buffer.BufferPool;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 存储引擎数据源：通过 TransactionalDml 访问 B+Tree 数据。
 *
 * <p>将 DataTuple ↔ Row 进行双向转换，桥接 SQL 执行器与存储引擎。</p>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>S1: scan 通过 TransactionalDml.scan() 获取 MVCC 感知的迭代器</li>
 *   <li>S2: DML 通过 TransactionalDml 在 MTR 保护下执行</li>
 *   <li>S3: 所有操作在 Transaction 上下文中执行</li>
 * </ul>
 */
public class StorageDataSource implements DataSourceSpi {

    private final CatalogManager catalogManager;
    private final String databaseName;
    private final Transaction transaction;
    private final TransactionalDml dml;
    private final BufferPool bufferPool;

    public StorageDataSource(CatalogManager catalogManager, String databaseName,
                             Transaction transaction, TransactionalDml dml,
                             BufferPool bufferPool) {
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.databaseName = Objects.requireNonNull(databaseName);
        this.transaction = Objects.requireNonNull(transaction);
        this.dml = Objects.requireNonNull(dml);
        this.bufferPool = Objects.requireNonNull(bufferPool);
    }

    @Override
    public Iterator<Row> scan(String tableName) {
        try {
            TableDescriptor tableDesc = catalogManager.getTable(databaseName, tableName);
            List<ColumnMeta> columns = tableDesc.getColumns();

            // 通过 TransactionalDml.scan() 获取 MVCC 感知的全表扫描迭代器
            MiniTransaction mtr = new MiniTransaction(bufferPool);
            Iterator<DataTuple> tupleIter = dml.scan(mtr, transaction, null, null);

            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    boolean has = tupleIter.hasNext();
                    if (!has) {
                        try { mtr.close(); } catch (Exception ignored) {}
                    }
                    return has;
                }

                @Override
                public Row next() {
                    DataTuple tuple = tupleIter.next();
                    return tupleToRow(tableName, tuple, columns);
                }
            };
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage scan failed for table: " + tableName, e);
        }
    }

    @Override
    public void insertRow(String tableName, Row row) {
        try {
            TableDescriptor tableDesc = catalogManager.getTable(databaseName, tableName);
            List<ColumnMeta> columns = tableDesc.getColumns();
            DataTuple tuple = rowToTuple(tableName, row, columns);
            byte[] primaryKey = extractPrimaryKey(row, tableDesc);

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                dml.insert(mtr, transaction, tuple, primaryKey);
                mtr.commit();
            }
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage insert failed for table: " + tableName, e);
        }
    }

    @Override
    public int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater) {
        try {
            TableDescriptor tableDesc = catalogManager.getTable(databaseName, tableName);
            List<ColumnMeta> columns = tableDesc.getColumns();

            MiniTransaction scanMtr = new MiniTransaction(bufferPool);
            Iterator<DataTuple> iter = dml.scan(scanMtr, transaction, null, null);

            int count = 0;
            try {
                while (iter.hasNext()) {
                    DataTuple tuple = iter.next();
                    Row row = tupleToRow(tableName, tuple, columns);
                    if (filter.test(row)) {
                        updater.accept(row);
                        DataTuple newTuple = rowToTuple(tableName, row, columns);
                        byte[] pk = extractPrimaryKey(row, tableDesc);

                        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                            dml.update(mtr, transaction, pk, newTuple, List.of());
                            mtr.commit();
                        }
                        count++;
                    }
                }
            } finally {
                try { scanMtr.close(); } catch (Exception ignored) {}
            }
            return count;
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage update failed for table: " + tableName, e);
        }
    }

    @Override
    public int deleteRows(String tableName, Predicate<Row> filter) {
        try {
            TableDescriptor tableDesc = catalogManager.getTable(databaseName, tableName);
            List<ColumnMeta> columns = tableDesc.getColumns();

            MiniTransaction scanMtr = new MiniTransaction(bufferPool);
            Iterator<DataTuple> iter = dml.scan(scanMtr, transaction, null, null);

            int count = 0;
            try {
                while (iter.hasNext()) {
                    DataTuple tuple = iter.next();
                    Row row = tupleToRow(tableName, tuple, columns);
                    if (filter.test(row)) {
                        byte[] pk = extractPrimaryKey(row, tableDesc);
                        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                            dml.delete(mtr, transaction, pk);
                            mtr.commit();
                        }
                        count++;
                    }
                }
            } finally {
                try { scanMtr.close(); } catch (Exception ignored) {}
            }
            return count;
        } catch (MiniDbException e) {
            throw new RuntimeException("Storage delete failed for table: " + tableName, e);
        }
    }

    // ==================== 转换方法 ====================

    private Row tupleToRow(String tableName, DataTuple tuple, List<ColumnMeta> columns) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < columns.size() && i < tuple.getFieldCount(); i++) {
            ColumnMeta col = columns.get(i);
            DataField field = tuple.getField(i);
            String qualifiedName = tableName + "." + col.getName();
            map.put(qualifiedName, field != null ? field.getValue() : null);
        }
        return new Row(map);
    }

    private DataTuple rowToTuple(String tableName, Row row, List<ColumnMeta> columns) {
        DataField[] fields = new DataField[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColumnMeta col = columns.get(i);
            Object value = row.get(tableName + "." + col.getName());
            if (value == null) {
                value = row.get(col.getName());
            }
            fields[i] = objectToDataField(value, col.getKind());
        }
        return DataTuple.of(fields);
    }

    private DataField objectToDataField(Object value, FieldKind kind) {
        if (value == null) {
            return DataField.nullField(kind);
        }
        return switch (kind) {
            case INT -> DataField.intField(((Number) value).intValue());
            case BIGINT -> DataField.bigintField(((Number) value).longValue());
            case TINYINT -> DataField.tinyintField(((Number) value).byteValue());
            case SMALLINT -> DataField.smallintField(((Number) value).shortValue());
            case VARCHAR -> DataField.varcharField(value.toString());
            case CHAR -> DataField.charField(value.toString(), value.toString().length());
            case TEXT -> DataField.textField(value.toString());
            default -> DataField.varcharField(value.toString());
        };
    }

    private byte[] extractPrimaryKey(Row row, TableDescriptor tableDesc) {
        for (ColumnMeta col : tableDesc.getColumns()) {
            if (!col.isNullable()) {
                Object value = row.get(tableDesc.getTableName() + "." + col.getName());
                if (value == null) {
                    value = row.get(col.getName());
                }
                if (value instanceof Number num) {
                    ByteBuffer buf = ByteBuffer.allocate(8);
                    buf.putLong(num.longValue());
                    return buf.array();
                }
                if (value instanceof String str) {
                    return str.getBytes(StandardCharsets.UTF_8);
                }
            }
        }
        return new byte[0];
    }
}
