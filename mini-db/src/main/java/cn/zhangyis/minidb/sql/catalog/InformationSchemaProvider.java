package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.types.SqlType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * information_schema 只读 provider。
 *
 * <p>所有行都由现有 CatalogSpi 即时派生，不维护第二份持久化元数据副本。</p>
 */
public class InformationSchemaProvider {

    private static final String TABLE_CATALOG = "def";
    private static final String DEFAULT_CHARSET = "utf8mb4";
    private static final String DEFAULT_COLLATION = "utf8mb4_general_ci";
    private static final String ENGINE = "mini-db";
    private static final String TABLE_TYPE = "BASE TABLE";
    private static final String INDEX_TYPE = "BTREE";

    private final CatalogSpi baseCatalog;

    public InformationSchemaProvider(CatalogSpi baseCatalog) {
        this.baseCatalog = baseCatalog;
    }

    public boolean supportsInternalName(String tableName) {
        return InformationSchemaNames.isVirtualInternalName(tableName);
    }

    public TableMeta getTable(String internalTableName) {
        String externalName = InformationSchemaNames.externalNameForInternal(internalTableName);
        if (externalName == null) {
            return null;
        }

        List<ColumnMeta> columns = switch (externalName) {
            case InformationSchemaNames.SCHEMATA -> schemataColumns();
            case InformationSchemaNames.TABLES -> tablesColumns();
            case InformationSchemaNames.COLUMNS -> columnsColumns();
            case InformationSchemaNames.STATISTICS -> statisticsColumns();
            case InformationSchemaNames.ENGINES -> enginesColumns();
            default -> List.of();
        };

        return TableMeta.of(internalTableName, columns, rowCountFor(internalTableName));
    }

    public Iterator<Row> scan(String internalTableName) {
        return rows(internalTableName).iterator();
    }

    public List<Row> rows(String internalTableName) {
        return switch (internalTableName.toUpperCase()) {
            case InformationSchemaNames.INTERNAL_SCHEMATA -> buildSchemataRows();
            case InformationSchemaNames.INTERNAL_TABLES -> buildTablesRows();
            case InformationSchemaNames.INTERNAL_COLUMNS -> buildColumnsRows();
            case InformationSchemaNames.INTERNAL_STATISTICS -> buildStatisticsRows();
            case InformationSchemaNames.INTERNAL_ENGINES -> buildEnginesRows();
            default -> List.of();
        };
    }

    private List<Row> buildSchemataRows() {
        List<Row> rows = new ArrayList<>();
        for (String database : allDatabases()) {
            rows.add(row(InformationSchemaNames.INTERNAL_SCHEMATA, Map.of(
                    "CATALOG_NAME", TABLE_CATALOG,
                    "SCHEMA_NAME", database,
                    "DEFAULT_CHARACTER_SET_NAME", charsetFor(database),
                    "DEFAULT_COLLATION_NAME", DEFAULT_COLLATION
            )));
        }
        return rows;
    }

    private List<Row> buildTablesRows() {
        List<Row> rows = new ArrayList<>();
        for (String database : allDatabases()) {
            if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
                for (String table : InformationSchemaNames.supportedExternalTables()) {
                    String internalName = InformationSchemaNames.internalNameFor(table);
                    TableMeta meta = getTable(internalName);
                    rows.add(row(InformationSchemaNames.INTERNAL_TABLES, Map.of(
                            "TABLE_CATALOG", TABLE_CATALOG,
                            "TABLE_SCHEMA", InformationSchemaNames.SCHEMA_NAME,
                            "TABLE_NAME", table,
                            "TABLE_TYPE", TABLE_TYPE,
                            "ENGINE", ENGINE,
                            "TABLE_ROWS", meta != null ? meta.rowCount() : 0L,
                            "DATA_LENGTH", 0L,
                            "INDEX_LENGTH", 0L
                    )));
                }
                continue;
            }

            for (String tableName : baseCatalog.listTables(database)) {
                TableMeta tableMeta = baseCatalog.getTable(database, tableName);
                rows.add(row(InformationSchemaNames.INTERNAL_TABLES, Map.of(
                        "TABLE_CATALOG", TABLE_CATALOG,
                        "TABLE_SCHEMA", database,
                        "TABLE_NAME", tableName,
                        "TABLE_TYPE", TABLE_TYPE,
                        "ENGINE", ENGINE,
                        "TABLE_ROWS", tableMeta != null ? tableMeta.rowCount() : 0L,
                        "DATA_LENGTH", 0L,
                        "INDEX_LENGTH", 0L
                )));
            }
        }
        return rows;
    }

    private List<Row> buildColumnsRows() {
        List<Row> rows = new ArrayList<>();
        for (String database : allDatabases()) {
            if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
                for (String table : InformationSchemaNames.supportedExternalTables()) {
                    String internalName = InformationSchemaNames.internalNameFor(table);
                    TableMeta meta = getTable(internalName);
                    if (meta != null) {
                        appendColumnRows(rows, InformationSchemaNames.SCHEMA_NAME, table,
                                meta.columns(), InformationSchemaNames.INTERNAL_COLUMNS);
                    }
                }
                continue;
            }

            for (String tableName : baseCatalog.listTables(database)) {
                appendColumnRows(rows, database, tableName,
                        baseCatalog.getColumns(database, tableName),
                        InformationSchemaNames.INTERNAL_COLUMNS);
            }
        }
        return rows;
    }

    private List<Row> buildStatisticsRows() {
        List<Row> rows = new ArrayList<>();
        for (String database : allDatabases()) {
            if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
                continue;
            }

            for (String tableName : baseCatalog.listTables(database)) {
                TableMeta tableMeta = baseCatalog.getTable(database, tableName);
                List<IndexMeta> indexes = baseCatalog.getIndexes(database, tableName);
                for (IndexMeta index : indexes) {
                    List<String> columnNames = index.columns().isEmpty()
                            ? primaryColumnsFallback(tableMeta)
                            : index.columns();
                    for (int i = 0; i < columnNames.size(); i++) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("TABLE_CATALOG", TABLE_CATALOG);
                        values.put("TABLE_SCHEMA", database);
                        values.put("TABLE_NAME", tableName);
                        values.put("NON_UNIQUE", index.primary() || index.unique() ? 0 : 1);
                        values.put("INDEX_SCHEMA", database);
                        values.put("INDEX_NAME", index.indexName());
                        values.put("SEQ_IN_INDEX", i + 1);
                        values.put("COLUMN_NAME", columnNames.get(i));
                        values.put("COLLATION", "A");
                        values.put("CARDINALITY", tableMeta != null ? tableMeta.rowCount() : 0L);
                        values.put("INDEX_TYPE", INDEX_TYPE);
                        rows.add(row(InformationSchemaNames.INTERNAL_STATISTICS, values));
                    }
                }
            }
        }
        return rows;
    }

    private List<Row> buildEnginesRows() {
        return List.of(row(InformationSchemaNames.INTERNAL_ENGINES, Map.of(
                "ENGINE", ENGINE,
                "SUPPORT", "YES",
                "COMMENT", "mini-db virtual storage engine",
                "TRANSACTIONS", "YES",
                "XA", "NO",
                "SAVEPOINTS", "YES"
        )));
    }

    private void appendColumnRows(List<Row> rows, String database, String tableName,
                                  List<ColumnMeta> columns, String internalTableName) {
        List<IndexMeta> indexes = InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)
                ? List.of()
                : baseCatalog.getIndexes(database, tableName);

        for (int i = 0; i < columns.size(); i++) {
            ColumnMeta column = columns.get(i);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("TABLE_CATALOG", TABLE_CATALOG);
            values.put("TABLE_SCHEMA", database);
            values.put("TABLE_NAME", tableName);
            values.put("COLUMN_NAME", column.name());
            values.put("ORDINAL_POSITION", i + 1);
            values.put("COLUMN_DEFAULT", column.defaultValue());
            values.put("IS_NULLABLE", column.nullable() ? "YES" : "NO");
            values.put("DATA_TYPE", dataTypeName(column.type()));
            values.put("COLUMN_KEY", columnKey(column, indexes));
            values.put("COLUMN_TYPE", columnType(column.type()));
            rows.add(row(internalTableName, values));
        }
    }

    private List<String> allDatabases() {
        Set<String> databases = new LinkedHashSet<>(baseCatalog.listDatabases());
        if (databases.isEmpty()) {
            databases.add("DEFAULT");
        }
        databases.add(InformationSchemaNames.SCHEMA_NAME);
        return List.copyOf(databases);
    }

    private long rowCountFor(String internalTableName) {
        return switch (internalTableName.toUpperCase()) {
            case InformationSchemaNames.INTERNAL_SCHEMATA -> allDatabases().size();
            case InformationSchemaNames.INTERNAL_TABLES -> virtualTablesRowCount();
            case InformationSchemaNames.INTERNAL_COLUMNS -> virtualColumnsRowCount();
            case InformationSchemaNames.INTERNAL_STATISTICS -> virtualStatisticsRowCount();
            case InformationSchemaNames.INTERNAL_ENGINES -> 1L;
            default -> 0L;
        };
    }

    private long virtualTablesRowCount() {
        long count = InformationSchemaNames.supportedExternalTables().size();
        for (String database : allDatabases()) {
            if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
                continue;
            }
            count += baseCatalog.listTables(database).size();
        }
        return count;
    }

    private long virtualColumnsRowCount() {
        long count = schemataColumns().size()
                + tablesColumns().size()
                + columnsColumns().size()
                + statisticsColumns().size()
                + enginesColumns().size();
        for (String database : allDatabases()) {
            if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
                continue;
            }
            for (String tableName : baseCatalog.listTables(database)) {
                count += baseCatalog.getColumns(database, tableName).size();
            }
        }
        return count;
    }

    private long virtualStatisticsRowCount() {
        long count = 0L;
        for (String database : allDatabases()) {
            if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
                continue;
            }
            for (String tableName : baseCatalog.listTables(database)) {
                TableMeta tableMeta = baseCatalog.getTable(database, tableName);
                for (IndexMeta index : baseCatalog.getIndexes(database, tableName)) {
                    List<String> columnNames = index.columns().isEmpty()
                            ? primaryColumnsFallback(tableMeta)
                            : index.columns();
                    count += columnNames.size();
                }
            }
        }
        return count;
    }

    private String charsetFor(String database) {
        if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
            return DEFAULT_CHARSET;
        }
        String charset = baseCatalog.getDatabaseCharset(database);
        return charset != null && !charset.isBlank() ? charset : DEFAULT_CHARSET;
    }

    private String dataTypeName(SqlType sqlType) {
        return switch (sqlType) {
            case TINYINT -> "tinyint";
            case SMALLINT -> "smallint";
            case INT32 -> "int";
            case BIGINT -> "bigint";
            case CHAR -> "char";
            case TEXT -> "text";
            case BLOB -> "blob";
            case JSON -> "json";
            case DECIMAL -> "decimal";
            case DATE -> "date";
            case TIME -> "time";
            case DATETIME -> "datetime";
            case VARCHAR -> "varchar";
        };
    }

    private String columnType(SqlType sqlType) {
        return switch (sqlType) {
            case TINYINT -> "tinyint";
            case SMALLINT -> "smallint";
            case CHAR -> "char";
            case VARCHAR -> "varchar";
            case TEXT -> "text";
            case BLOB -> "blob";
            case JSON -> "json";
            case INT32 -> "int";
            case BIGINT -> "bigint";
            case DECIMAL -> "decimal";
            case DATE -> "date";
            case TIME -> "time";
            case DATETIME -> "datetime";
        };
    }

    private String columnKey(ColumnMeta column, List<IndexMeta> indexes) {
        if (column.isPrimaryKey()) {
            return "PRI";
        }
        for (IndexMeta index : indexes) {
            if (index.columns().stream().anyMatch(name -> name.equalsIgnoreCase(column.name()))) {
                return index.unique() ? "UNI" : "MUL";
            }
        }
        return "";
    }

    private List<String> primaryColumnsFallback(TableMeta tableMeta) {
        if (tableMeta == null) {
            return List.of();
        }
        return tableMeta.columns().stream()
                .filter(ColumnMeta::isPrimaryKey)
                .map(ColumnMeta::name)
                .toList();
    }

    private List<ColumnMeta> schemataColumns() {
        return List.of(
                new ColumnMeta("CATALOG_NAME", SqlType.VARCHAR, false, false, TABLE_CATALOG),
                new ColumnMeta("SCHEMA_NAME", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("DEFAULT_CHARACTER_SET_NAME", SqlType.VARCHAR, false, false, DEFAULT_CHARSET),
                new ColumnMeta("DEFAULT_COLLATION_NAME", SqlType.VARCHAR, false, false, DEFAULT_COLLATION)
        );
    }

    private List<ColumnMeta> tablesColumns() {
        return List.of(
                new ColumnMeta("TABLE_CATALOG", SqlType.VARCHAR, false, false, TABLE_CATALOG),
                new ColumnMeta("TABLE_SCHEMA", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("TABLE_NAME", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("TABLE_TYPE", SqlType.VARCHAR, false, false, TABLE_TYPE),
                new ColumnMeta("ENGINE", SqlType.VARCHAR, false, false, ENGINE),
                new ColumnMeta("TABLE_ROWS", SqlType.BIGINT, false, true, null),
                new ColumnMeta("DATA_LENGTH", SqlType.BIGINT, false, true, null),
                new ColumnMeta("INDEX_LENGTH", SqlType.BIGINT, false, true, null)
        );
    }

    private List<ColumnMeta> columnsColumns() {
        return List.of(
                new ColumnMeta("TABLE_CATALOG", SqlType.VARCHAR, false, false, TABLE_CATALOG),
                new ColumnMeta("TABLE_SCHEMA", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("TABLE_NAME", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("COLUMN_NAME", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("ORDINAL_POSITION", SqlType.INT32, false, false, null),
                new ColumnMeta("COLUMN_DEFAULT", SqlType.VARCHAR, false, true, null),
                new ColumnMeta("IS_NULLABLE", SqlType.VARCHAR, false, false, "YES"),
                new ColumnMeta("DATA_TYPE", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("COLUMN_KEY", SqlType.VARCHAR, false, false, ""),
                new ColumnMeta("COLUMN_TYPE", SqlType.VARCHAR, false, false, null)
        );
    }

    private List<ColumnMeta> statisticsColumns() {
        return List.of(
                new ColumnMeta("TABLE_CATALOG", SqlType.VARCHAR, false, false, TABLE_CATALOG),
                new ColumnMeta("TABLE_SCHEMA", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("TABLE_NAME", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("NON_UNIQUE", SqlType.INT32, false, false, null),
                new ColumnMeta("INDEX_SCHEMA", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("INDEX_NAME", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("SEQ_IN_INDEX", SqlType.INT32, false, false, null),
                new ColumnMeta("COLUMN_NAME", SqlType.VARCHAR, false, false, null),
                new ColumnMeta("COLLATION", SqlType.VARCHAR, false, true, null),
                new ColumnMeta("CARDINALITY", SqlType.BIGINT, false, true, null),
                new ColumnMeta("INDEX_TYPE", SqlType.VARCHAR, false, false, INDEX_TYPE)
        );
    }

    private List<ColumnMeta> enginesColumns() {
        return List.of(
                new ColumnMeta("ENGINE", SqlType.VARCHAR, false, false, ENGINE),
                new ColumnMeta("SUPPORT", SqlType.VARCHAR, false, false, "YES"),
                new ColumnMeta("COMMENT", SqlType.VARCHAR, false, false, "mini-db virtual storage engine"),
                new ColumnMeta("TRANSACTIONS", SqlType.VARCHAR, false, false, "YES"),
                new ColumnMeta("XA", SqlType.VARCHAR, false, false, "NO"),
                new ColumnMeta("SAVEPOINTS", SqlType.VARCHAR, false, false, "YES")
        );
    }

    private Row row(String internalTableName, Map<String, Object> values) {
        Map<String, Object> qualified = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            qualified.put(internalTableName + "." + entry.getKey(), entry.getValue());
        }
        return new Row(qualified);
    }
}
