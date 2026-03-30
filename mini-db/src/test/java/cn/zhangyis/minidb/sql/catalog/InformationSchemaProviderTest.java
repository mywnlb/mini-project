package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.sql.exec.MetadataAwareDataSource;
import cn.zhangyis.minidb.sql.exec.Row;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class InformationSchemaProviderTest {

    @Test
    void providerRowsTrackBaseCatalogMetadata() {
        MockCatalog baseCatalog = new MockCatalog();
        InformationSchemaProvider provider = new InformationSchemaProvider(baseCatalog);

        TableMeta tablesMeta = provider.getTable(InformationSchemaNames.INTERNAL_TABLES);
        assertNotNull(tablesMeta);
        assertEquals(8, tablesMeta.columns().size());

        boolean foundUsers = provider.rows(InformationSchemaNames.INTERNAL_TABLES).stream()
                .anyMatch(row -> "DEFAULT".equals(row.get("TABLE_SCHEMA"))
                        && "users".equalsIgnoreCase(String.valueOf(row.get("TABLE_NAME"))));
        assertTrue(foundUsers, "应从底层 catalog 派生出 users 表");

        boolean foundUsersNameColumn = provider.rows(InformationSchemaNames.INTERNAL_COLUMNS).stream()
                .anyMatch(row -> "users".equalsIgnoreCase(String.valueOf(row.get("TABLE_NAME")))
                        && "name".equalsIgnoreCase(String.valueOf(row.get("COLUMN_NAME"))));
        assertTrue(foundUsersNameColumn, "应从底层 catalog 派生出 users.name 列");
    }

    @Test
    void virtualTableMetadata_usesNonRecursiveRowCounts() {
        MockCatalog baseCatalog = new MockCatalog();
        InformationSchemaProvider provider = new InformationSchemaProvider(baseCatalog);

        TableMeta tablesMeta = provider.getTable(InformationSchemaNames.INTERNAL_TABLES);
        TableMeta columnsMeta = provider.getTable(InformationSchemaNames.INTERNAL_COLUMNS);

        assertEquals(provider.rows(InformationSchemaNames.INTERNAL_TABLES).size(), tablesMeta.rowCount());
        assertEquals(provider.rows(InformationSchemaNames.INTERNAL_COLUMNS).size(), columnsMeta.rowCount());
    }

    @Test
    void providerExposesEnginesVirtualTable() {
        InformationSchemaProvider provider = new InformationSchemaProvider(new MockCatalog());

        TableMeta enginesMeta = provider.getTable(InformationSchemaNames.INTERNAL_ENGINES);
        assertNotNull(enginesMeta);
        assertEquals(6, enginesMeta.columns().size());
        assertEquals(1L, enginesMeta.rowCount());

        Row row = provider.rows(InformationSchemaNames.INTERNAL_ENGINES).getFirst();
        assertEquals("mini-db", row.get("ENGINE"));
        assertEquals("YES", row.get("SUPPORT"));
    }

    @Test
    void providerRegistersKeyColumnUsageAndTableConstraintsVirtualTables() {
        MockCatalog baseCatalog = new MockCatalog();
        InformationSchemaProvider provider = new InformationSchemaProvider(baseCatalog);

        String keyColumnUsageInternal = InformationSchemaNames.internalNameFor("KEY_COLUMN_USAGE");
        String tableConstraintsInternal = InformationSchemaNames.internalNameFor("TABLE_CONSTRAINTS");

        assertNotNull(keyColumnUsageInternal, "KEY_COLUMN_USAGE 必须注册到 information_schema 名称映射");
        assertNotNull(tableConstraintsInternal, "TABLE_CONSTRAINTS 必须注册到 information_schema 名称映射");
        assertNotNull(provider.getTable(keyColumnUsageInternal), "provider 必须暴露 KEY_COLUMN_USAGE 元数据");
        assertNotNull(provider.getTable(tableConstraintsInternal), "provider 必须暴露 TABLE_CONSTRAINTS 元数据");
    }

    @Test
    void providerBuildsKeyColumnUsageRowsFromPrimaryAndUniqueIndexesOnly() {
        MockCatalog baseCatalog = new MockCatalog();
        baseCatalog.createIndex(new IndexMeta("PRIMARY", "users", java.util.List.of("id"), true, true));
        baseCatalog.createIndex(new IndexMeta("UQ_USERS_NAME", "users", java.util.List.of("name"), false, true));
        baseCatalog.createIndex(new IndexMeta("IDX_USERS_NAME", "users", java.util.List.of("name"), false, false));

        InformationSchemaProvider provider = new InformationSchemaProvider(baseCatalog);
        String internalName = InformationSchemaNames.internalNameFor("KEY_COLUMN_USAGE");

        assertNotNull(internalName, "KEY_COLUMN_USAGE internal name must exist");

        java.util.List<Row> rows = provider.rows(internalName);
        assertEquals(2, rows.size(), "KEY_COLUMN_USAGE 只应暴露 PRIMARY/UNIQUE 列");
        assertTrue(rows.stream().anyMatch(row ->
                        "PRIMARY".equals(row.get("CONSTRAINT_NAME"))
                                && "users".equalsIgnoreCase(String.valueOf(row.get("TABLE_NAME")))
                                && "id".equalsIgnoreCase(String.valueOf(row.get("COLUMN_NAME")))
                                && Integer.valueOf(1).equals(row.get("ORDINAL_POSITION"))),
                "PRIMARY 键列必须出现在 KEY_COLUMN_USAGE");
        assertTrue(rows.stream().anyMatch(row ->
                        "UQ_USERS_NAME".equals(row.get("CONSTRAINT_NAME"))
                                && "users".equalsIgnoreCase(String.valueOf(row.get("TABLE_NAME")))
                                && "name".equalsIgnoreCase(String.valueOf(row.get("COLUMN_NAME")))
                                && Integer.valueOf(1).equals(row.get("ORDINAL_POSITION"))),
                "UNIQUE 键列必须出现在 KEY_COLUMN_USAGE");
        assertFalse(rows.stream().anyMatch(row ->
                        "IDX_USERS_NAME".equals(row.get("CONSTRAINT_NAME"))),
                "普通二级索引不能伪装成约束列");
    }

    @Test
    void providerBuildsTableConstraintRowsFromPrimaryAndUniqueIndexesOnly() {
        MockCatalog baseCatalog = new MockCatalog();
        baseCatalog.createIndex(new IndexMeta("PRIMARY", "users", java.util.List.of("id"), true, true));
        baseCatalog.createIndex(new IndexMeta("UQ_USERS_NAME", "users", java.util.List.of("name"), false, true));
        baseCatalog.createIndex(new IndexMeta("IDX_USERS_NAME", "users", java.util.List.of("name"), false, false));

        InformationSchemaProvider provider = new InformationSchemaProvider(baseCatalog);
        String internalName = InformationSchemaNames.internalNameFor("TABLE_CONSTRAINTS");

        assertNotNull(internalName, "TABLE_CONSTRAINTS internal name must exist");

        java.util.List<Row> rows = provider.rows(internalName);
        assertEquals(2, rows.size(), "TABLE_CONSTRAINTS 只应暴露 PRIMARY KEY / UNIQUE");
        assertTrue(rows.stream().anyMatch(row ->
                        "PRIMARY".equals(row.get("CONSTRAINT_NAME"))
                                && "PRIMARY KEY".equals(row.get("CONSTRAINT_TYPE"))
                                && "YES".equals(row.get("ENFORCED"))),
                "PRIMARY 索引必须映射为 PRIMARY KEY 约束");
        assertTrue(rows.stream().anyMatch(row ->
                        "UQ_USERS_NAME".equals(row.get("CONSTRAINT_NAME"))
                                && "UNIQUE".equals(row.get("CONSTRAINT_TYPE"))
                                && "YES".equals(row.get("ENFORCED"))),
                "UNIQUE 索引必须映射为 UNIQUE 约束");
        assertFalse(rows.stream().anyMatch(row ->
                        "IDX_USERS_NAME".equals(row.get("CONSTRAINT_NAME"))),
                "普通二级索引不能出现在 TABLE_CONSTRAINTS");
    }

    /**
     * E6: alterTableAddColumn 后 COLUMNS 表应即时反映新增列。
     * 覆盖不变量 I1, I2。
     */
    @Test
    void columnsViewReflectsNewlyAddedColumn() {
        MockCatalog baseCatalog = new MockCatalog();
        InformationSchemaProvider provider = new InformationSchemaProvider(baseCatalog);

        // addColumn 前的列数
        long columnsBefore = provider.rows(InformationSchemaNames.INTERNAL_COLUMNS).stream()
                .filter(row -> "users".equalsIgnoreCase(String.valueOf(row.get("TABLE_NAME"))))
                .count();

        baseCatalog.addColumn("users", new ColumnMeta("email", cn.zhangyis.minidb.sql.types.SqlType.VARCHAR, false));

        java.util.List<Row> columnsAfter = provider.rows(InformationSchemaNames.INTERNAL_COLUMNS).stream()
                .filter(row -> "users".equalsIgnoreCase(String.valueOf(row.get("TABLE_NAME"))))
                .toList();

        assertEquals(columnsBefore + 1, columnsAfter.size(),
                "addColumn 后 COLUMNS 应多出一行");
        assertTrue(columnsAfter.stream().anyMatch(row ->
                        "email".equalsIgnoreCase(String.valueOf(row.get("COLUMN_NAME")))
                                && "varchar".equals(row.get("DATA_TYPE"))),
                "新增的 email 列必须出现在 COLUMNS 视图中");
        // 验证 ORDINAL_POSITION 连续
        Row emailRow = columnsAfter.stream()
                .filter(row -> "email".equalsIgnoreCase(String.valueOf(row.get("COLUMN_NAME"))))
                .findFirst().orElseThrow();
        assertEquals(3, emailRow.get("ORDINAL_POSITION"),
                "email 应在第 3 个位置（id=1, name=2, email=3）");
    }

    /**
     * E7: 未实现的 information_schema 表名不能出现在 supportedExternalTables 中，
     * 查询 provider 应返回空行（不报错）。覆盖不变量 I6。
     */
    @Test
    void unsupportedInformationSchemaTablesReturnEmptyRows() {
        MockCatalog baseCatalog = new MockCatalog();
        InformationSchemaProvider provider = new InformationSchemaProvider(baseCatalog);

        // 这些表在 mini-db 中没有对应子系统，不能被 internalNameFor 解析
        java.util.List<String> unsupportedTables = java.util.List.of(
                "ROUTINES", "TRIGGERS", "EVENTS", "VIEWS",
                "USER_PRIVILEGES", "SCHEMA_PRIVILEGES", "TABLE_PRIVILEGES",
                "REFERENTIAL_CONSTRAINTS"
        );

        for (String table : unsupportedTables) {
            // 未注册的表没有 internal name
            String internalName = InformationSchemaNames.internalNameFor(table);
            assertNull(internalName,
                    table + " 不应注册到 information_schema 名称映射（无对应子系统）");
        }

        // supportedExternalTables 不能包含未实现表
        java.util.List<String> supported = InformationSchemaNames.supportedExternalTables();
        for (String table : unsupportedTables) {
            assertFalse(supported.contains(table),
                    table + " 不能出现在 supportedExternalTables 列表中");
        }
    }

    @Test
    void metadataAwareDataSourceRejectsWritesToVirtualTables() {
        AtomicBoolean delegateCalled = new AtomicBoolean(false);
        DataSourceSpi delegate = new DataSourceSpi() {
            @Override
            public Iterator<Row> scan(String tableName) {
                delegateCalled.set(true);
                return java.util.List.<Row>of().iterator();
            }

            @Override
            public void insertRow(String tableName, Row row) {
                delegateCalled.set(true);
            }

            @Override
            public int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater) {
                delegateCalled.set(true);
                return 0;
            }

            @Override
            public int deleteRows(String tableName, Predicate<Row> filter) {
                delegateCalled.set(true);
                return 0;
            }
        };

        MetadataAwareDataSource dataSource = new MetadataAwareDataSource(
                delegate, new MockCatalog());

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> dataSource.insertRow(
                        InformationSchemaNames.INTERNAL_TABLES,
                        new Row(Map.of("TABLE_NAME", "X"))));
        assertTrue(error.getMessage().contains("read-only"));
        assertFalse(delegateCalled.get(), "虚拟表写入不能触达底层 dataSource");
    }
}
