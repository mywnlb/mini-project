package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.storage.btree.ColumnType;
import cn.zhangyis.minidb.storage.btree.IndexDescriptor;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.record.schema.FieldType;

/**
 * FieldKind ↔ ColumnType 桥接工具类
 *
 * <p>桥接 record 层类型系统 (FieldKind/FieldType) 与 btree 层类型系统 (ColumnType)。</p>
 * <p>TINYINT/SMALLINT 提升为 INT（索引层不区分小整数）。</p>
 */
public final class TypeBridge {

    private TypeBridge() {
    }

    /**
     * FieldKind → ColumnType
     *
     * @throws CatalogException BLOB/TEXT 不可索引
     */
    public static ColumnType toColumnType(FieldKind kind) throws CatalogException {
        return switch (kind) {
            case TINYINT, SMALLINT, INT -> ColumnType.INT;
            case BIGINT -> ColumnType.BIGINT;
            case DECIMAL -> ColumnType.VARCHAR;
            case DATE -> ColumnType.CHAR;
            case TIME -> ColumnType.VARCHAR;
            case DATETIME -> ColumnType.CHAR;
            case CHAR -> ColumnType.CHAR;
            case VARCHAR -> ColumnType.VARCHAR;
            case BINARY -> ColumnType.BINARY;
            case VARBINARY -> ColumnType.VARBINARY;
            case BLOB, TEXT, JSON -> throw new CatalogException(700501,
                    "Type " + kind + " cannot be used in index keys");
        };
    }

    /**
     * 计算索引键的 maxLength
     *
     * <p>对于 TINYINT/SMALLINT，提升到 INT(4B)。其余返回 FieldType.getLength()。</p>
     */
    public static int toKeyMaxLength(FieldType fieldType) throws CatalogException {
        FieldKind kind = fieldType.getKind();
        if (kind.isLob()) {
            throw new CatalogException(700501,
                    "Type " + kind + " cannot be used in index keys");
        }
        return switch (kind) {
            case TINYINT, SMALLINT -> 4; // 提升到 INT
            default -> fieldType.getLength();
        };
    }

    /**
     * ColumnMeta → IndexDescriptor.ColumnDescriptor
     */
    public static IndexDescriptor.ColumnDescriptor toIndexColumn(
            ColumnMeta columnMeta, boolean descending) throws CatalogException {
        FieldType fieldType = columnMeta.getType();
        ColumnType columnType = toColumnType(fieldType.getKind());
        int maxLength = toKeyMaxLength(fieldType);
        return new IndexDescriptor.ColumnDescriptor(
                columnMeta.getName(),
                columnType,
                maxLength,
                fieldType.isNullable(),
                descending
        );
    }
}
