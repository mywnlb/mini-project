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
                delegate, new InformationSchemaProvider(new MockCatalog()));

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> dataSource.insertRow(
                        InformationSchemaNames.INTERNAL_TABLES,
                        new Row(Map.of("TABLE_NAME", "X"))));
        assertTrue(error.getMessage().contains("read-only"));
        assertFalse(delegateCalled.get(), "虚拟表写入不能触达底层 dataSource");
    }
}
