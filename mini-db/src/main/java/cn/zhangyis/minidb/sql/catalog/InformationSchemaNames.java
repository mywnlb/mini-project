package cn.zhangyis.minidb.sql.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InformationSchemaNames {

    public static final String SCHEMA_NAME = "INFORMATION_SCHEMA";

    public static final String SCHEMATA = "SCHEMATA";
    public static final String TABLES = "TABLES";
    public static final String COLUMNS = "COLUMNS";
    public static final String STATISTICS = "STATISTICS";
    public static final String ENGINES = "ENGINES";

    public static final String INTERNAL_SCHEMATA = "__MINIDB_INFO_SCHEMATA";
    public static final String INTERNAL_TABLES = "__MINIDB_INFO_TABLES";
    public static final String INTERNAL_COLUMNS = "__MINIDB_INFO_COLUMNS";
    public static final String INTERNAL_STATISTICS = "__MINIDB_INFO_STATISTICS";
    public static final String INTERNAL_ENGINES = "__MINIDB_INFO_ENGINES";

    private static final Map<String, String> EXTERNAL_TO_INTERNAL = Map.of(
            SCHEMATA, INTERNAL_SCHEMATA,
            TABLES, INTERNAL_TABLES,
            COLUMNS, INTERNAL_COLUMNS,
            STATISTICS, INTERNAL_STATISTICS,
            ENGINES, INTERNAL_ENGINES
    );

    private static final Map<String, String> INTERNAL_TO_EXTERNAL;

    static {
        Map<String, String> reversed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : EXTERNAL_TO_INTERNAL.entrySet()) {
            reversed.put(entry.getValue(), entry.getKey());
        }
        INTERNAL_TO_EXTERNAL = Map.copyOf(reversed);
    }

    private InformationSchemaNames() {
    }

    public static String internalNameFor(String externalTableName) {
        if (externalTableName == null) {
            return null;
        }
        return EXTERNAL_TO_INTERNAL.get(externalTableName.toUpperCase());
    }

    public static String externalNameForInternal(String internalTableName) {
        if (internalTableName == null) {
            return null;
        }
        return INTERNAL_TO_EXTERNAL.get(internalTableName.toUpperCase());
    }

    public static boolean isVirtualInternalName(String tableName) {
        return externalNameForInternal(tableName) != null;
    }

    public static boolean isInformationSchemaQualifiedName(String tableName) {
        return tableName != null
                && tableName.regionMatches(true, 0, SCHEMA_NAME + ".", 0, SCHEMA_NAME.length() + 1);
    }

    public static List<String> supportedExternalTables() {
        return List.of(SCHEMATA, TABLES, COLUMNS, STATISTICS, ENGINES);
    }
}
