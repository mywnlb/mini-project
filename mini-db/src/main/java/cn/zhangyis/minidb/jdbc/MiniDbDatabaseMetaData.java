package cn.zhangyis.minidb.jdbc;

import java.sql.*;

/**
 * JDBC DatabaseMetaData 最小实现。
 *
 * <p>仅实现必要的元数据方法（驱动名、版本、产品名等），
 * 其他方法抛出 SQLFeatureNotSupportedException 或返回默认值。</p>
 */
public class MiniDbDatabaseMetaData implements DatabaseMetaData {

    private final Connection connection;
    private final String database;

    public MiniDbDatabaseMetaData(Connection connection, String database) {
        this.connection = connection;
        this.database = database;
    }

    @Override public String getDriverName() { return "MiniDB JDBC Driver"; }
    @Override public String getDriverVersion() { return "1.0"; }
    @Override public int getDriverMajorVersion() { return 1; }
    @Override public int getDriverMinorVersion() { return 0; }
    @Override public String getDatabaseProductName() { return "MiniDB"; }
    @Override public String getDatabaseProductVersion() { return "8.0.0-minidb"; }
    @Override public String getURL() { return "jdbc:minidb://" + database; }
    @Override public String getUserName() { return "root"; }
    @Override public boolean isReadOnly() { return false; }
    @Override public Connection getConnection() { return connection; }
    @Override public int getDatabaseMajorVersion() { return 8; }
    @Override public int getDatabaseMinorVersion() { return 0; }
    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 2; }
    @Override public boolean supportsTransactions() { return true; }
    @Override public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_REPEATABLE_READ; }

    // 大量方法返回默认值或抛异常，此处省略完整实现
    // 以下仅列出常被调用的方法

    @Override public boolean allProceduresAreCallable() { return false; }
    @Override public boolean allTablesAreSelectable() { return true; }
    @Override public boolean nullsAreSortedHigh() { return false; }
    @Override public boolean nullsAreSortedLow() { return true; }
    @Override public boolean nullsAreSortedAtStart() { return false; }
    @Override public boolean nullsAreSortedAtEnd() { return false; }
    @Override public boolean usesLocalFiles() { return false; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return true; }
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean storesLowerCaseIdentifiers() { return false; }
    @Override public boolean storesMixedCaseIdentifiers() { return true; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return true; }
    @Override public String getIdentifierQuoteString() { return "`"; }
    @Override public String getSQLKeywords() { return ""; }
    @Override public String getNumericFunctions() { return "ABS,CEIL,FLOOR,ROUND,MOD"; }
    @Override public String getStringFunctions() { return "UPPER,LOWER,CONCAT,SUBSTRING,TRIM,LENGTH,REPLACE"; }
    @Override public String getTimeDateFunctions() { return "NOW,DATE_FORMAT,DATEDIFF"; }
    @Override public String getSystemFunctions() { return "DATABASE,VERSION"; }
    @Override public String getSearchStringEscape() { return "\\"; }
    @Override public String getExtraNameCharacters() { return ""; }
    @Override public boolean supportsAlterTableWithAddColumn() { return true; }
    @Override public boolean supportsAlterTableWithDropColumn() { return false; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean nullPlusNonNullIsNull() { return true; }
    @Override public boolean supportsConvert() { return false; }
    @Override public boolean supportsConvert(int a, int b) { return false; }
    @Override public boolean supportsTableCorrelationNames() { return true; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsExpressionsInOrderBy() { return true; }
    @Override public boolean supportsOrderByUnrelated() { return true; }
    @Override public boolean supportsGroupBy() { return true; }
    @Override public boolean supportsGroupByUnrelated() { return true; }
    @Override public boolean supportsGroupByBeyondSelect() { return true; }
    @Override public boolean supportsLikeEscapeClause() { return true; }
    @Override public boolean supportsMultipleResultSets() { return false; }
    @Override public boolean supportsMultipleTransactions() { return true; }
    @Override public boolean supportsNonNullableColumns() { return true; }
    @Override public boolean supportsMinimumSQLGrammar() { return true; }
    @Override public boolean supportsCoreSQLGrammar() { return false; }
    @Override public boolean supportsExtendedSQLGrammar() { return false; }
    @Override public boolean supportsANSI92EntryLevelSQL() { return false; }
    @Override public boolean supportsANSI92IntermediateSQL() { return false; }
    @Override public boolean supportsANSI92FullSQL() { return false; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return false; }
    @Override public boolean supportsOuterJoins() { return true; }
    @Override public boolean supportsFullOuterJoins() { return false; }
    @Override public boolean supportsLimitedOuterJoins() { return true; }
    @Override public String getSchemaTerm() { return "schema"; }
    @Override public String getProcedureTerm() { return "procedure"; }
    @Override public String getCatalogTerm() { return "database"; }
    @Override public boolean isCatalogAtStart() { return true; }
    @Override public String getCatalogSeparator() { return "."; }
    @Override public boolean supportsSchemasInDataManipulation() { return false; }
    @Override public boolean supportsSchemasInProcedureCalls() { return false; }
    @Override public boolean supportsSchemasInTableDefinitions() { return false; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return false; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsSubqueriesInComparisons() { return true; }
    @Override public boolean supportsSubqueriesInExists() { return true; }
    @Override public boolean supportsSubqueriesInIns() { return true; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return false; }
    @Override public boolean supportsCorrelatedSubqueries() { return false; }
    @Override public boolean supportsUnion() { return true; }
    @Override public boolean supportsUnionAll() { return true; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return true; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return true; }
    @Override public int getMaxBinaryLiteralLength() { return 0; }
    @Override public int getMaxCharLiteralLength() { return 0; }
    @Override public int getMaxColumnNameLength() { return 64; }
    @Override public int getMaxColumnsInGroupBy() { return 0; }
    @Override public int getMaxColumnsInIndex() { return 0; }
    @Override public int getMaxColumnsInOrderBy() { return 0; }
    @Override public int getMaxColumnsInSelect() { return 0; }
    @Override public int getMaxColumnsInTable() { return 0; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 0; }
    @Override public int getMaxIndexLength() { return 0; }
    @Override public int getMaxSchemaNameLength() { return 0; }
    @Override public int getMaxProcedureNameLength() { return 0; }
    @Override public int getMaxCatalogNameLength() { return 0; }
    @Override public int getMaxRowSize() { return 0; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public int getMaxStatementLength() { return 0; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 64; }
    @Override public int getMaxTablesInSelect() { return 0; }
    @Override public int getMaxUserNameLength() { return 0; }
    @Override public boolean supportsTransactionIsolationLevel(int level) { return level == Connection.TRANSACTION_REPEATABLE_READ; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return true; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return true; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public ResultSet getProcedures(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getProcedureColumns(String a, String b, String c, String d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getTables(String a, String b, String c, String[] d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getSchemas() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getCatalogs() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getTableTypes() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getColumns(String a, String b, String c, String d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getColumnPrivileges(String a, String b, String c, String d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getTablePrivileges(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getBestRowIdentifier(String a, String b, String c, int d, boolean e) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getVersionColumns(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getPrimaryKeys(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getImportedKeys(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getExportedKeys(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getCrossReference(String a, String b, String c, String d, String e, String f) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getTypeInfo() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getIndexInfo(String a, String b, String c, boolean d, boolean e) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean supportsResultSetType(int type) { return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) { return false; }
    @Override public boolean ownUpdatesAreVisible(int type) { return false; }
    @Override public boolean ownDeletesAreVisible(int type) { return false; }
    @Override public boolean ownInsertsAreVisible(int type) { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) { return false; }
    @Override public boolean othersDeletesAreVisible(int type) { return false; }
    @Override public boolean othersInsertsAreVisible(int type) { return false; }
    @Override public boolean updatesAreDetected(int type) { return false; }
    @Override public boolean deletesAreDetected(int type) { return false; }
    @Override public boolean insertsAreDetected(int type) { return false; }
    @Override public boolean supportsBatchUpdates() { return false; }
    @Override public ResultSet getUDTs(String a, String b, String c, int[] d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean supportsSavepoints() { return false; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return false; }
    @Override public boolean supportsGetGeneratedKeys() { return false; }
    @Override public ResultSet getSuperTypes(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getSuperTables(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getAttributes(String a, String b, String c, String d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean supportsResultSetHoldability(int h) { return false; }
    @Override public int getResultSetHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public int getSQLStateType() { return sqlStateSQL; }
    @Override public boolean locatorsUpdateCopy() { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public RowIdLifetime getRowIdLifetime() { return RowIdLifetime.ROWID_UNSUPPORTED; }
    @Override public ResultSet getSchemas(String a, String b) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public ResultSet getClientInfoProperties() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getFunctions(String a, String b, String c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getFunctionColumns(String a, String b, String c, String d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSet getPseudoColumns(String a, String b, String c, String d) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean generatedKeyAlwaysReturned() { return false; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
