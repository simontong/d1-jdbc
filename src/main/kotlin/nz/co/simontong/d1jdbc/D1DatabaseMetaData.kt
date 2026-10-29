package nz.co.simontong.d1jdbc

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.RowIdLifetime
import java.sql.SQLException
import java.sql.Types
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Describes D1 and its schema to JDBC tools.
 *
 * @param connection connection the metadata belongs to
 */
class D1DatabaseMetaData(
	private val connection: D1Connection,
) : DatabaseMetaData {
	/**
	 * Builds a result with the given columns and rows.
	 *
	 * @param columns column names
	 * @param rows row values
	 * @return the result set
	 */
	private fun result(columns: List<String>, rows: List<List<JsonElement>>): ResultSet {
		return D1ResultSet(null, columns, rows)
	}

	/**
	 * Builds a result with the given columns and no rows.
	 *
	 * @param columns column names
	 * @return the result set
	 */
	private fun emptyResult(vararg columns: String): ResultSet = result(columns.toList(), emptyList())

	/**
	 * Turns a Kotlin value into a JSON value for a metadata row.
	 *
	 * @param value text, number, or null
	 * @return the JSON value
	 */
	private fun json(value: Any?): JsonElement {
		return when (value) {
			null -> JsonNull
			is Number -> JsonPrimitive(value)
			else -> JsonPrimitive(value.toString())
		}
	}

	/**
	 * Builds a result whose rows are lists of Kotlin values.
	 *
	 * @param columns column names
	 * @param rows row values
	 * @return the result set
	 */
	private fun rowsOf(columns: List<String>, rows: List<List<Any?>>): ResultSet {
		return result(columns, rows.map { row -> row.map { json(it) } })
	}

	/**
	 * Says whether a name matches a JDBC search pattern, ignoring case as SQLite does.
	 *
	 * @param pattern pattern using `%` and `_`, or null to match everything
	 * @param name name to check
	 * @return true when it matches
	 */
	private fun matches(pattern: String?, name: String): Boolean {
		if (pattern == null) {
			return true
		}
		val regex = StringBuilder()
		var index = 0
		while (index < pattern.length) {
			val char = pattern[index]
			when {
				char == '\\' && index + 1 < pattern.length -> {
					index++
					regex.append(Regex.escape(pattern[index].toString()))
				}

				char == '%' -> {
					regex.append(".*")
				}

				char == '_' -> {
					regex.append(".")
				}

				else -> {
					regex.append(Regex.escape(char.toString()))
				}
			}
			index++
		}
		return Regex(regex.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matches(name)
	}

	/**
	 * Finds the tables a metadata call asks about.
	 *
	 * @param catalog catalog name, where null or empty match D1's lack of catalogs
	 * @param schemaPattern schema pattern, which must match `main`
	 * @param tablePattern table name pattern
	 * @return matching tables
	 * @throws SQLException when D1 reports an error
	 */
	private fun tables(catalog: String?, schemaPattern: String?, tablePattern: String?): List<SchemaTable> {
		if (!catalog.isNullOrEmpty() || !matches(schemaPattern, SCHEMA)) {
			return emptyList()
		}
		return connection.schemaCache.get().tables.filter { matches(tablePattern, it.name) }
	}

	/**
	 * Lists tables and views, without D1's and SQLite's own tables.
	 *
	 * @param catalog catalog name, or null
	 * @param schemaPattern schema pattern, or null
	 * @param tableNamePattern table name pattern, or null
	 * @param types table types to include, or null for all
	 * @return one row per table, sorted by type and name
	 * @throws SQLException when D1 reports an error
	 */
	override fun getTables(
		catalog: String?,
		schemaPattern: String?,
		tableNamePattern: String?,
		types: Array<out String>?,
	): ResultSet {
		val rows = tables(catalog, schemaPattern, tableNamePattern)
			.filter { types == null || types.any { type -> type.equals(it.type, ignoreCase = true) } }
			.sortedWith(compareBy({ it.type }, { it.name.lowercase() }))
			.map { listOf(null, SCHEMA, it.name, it.type, null, null, null, null, null, null) }
		return rowsOf(TABLE_COLUMNS, rows)
	}

	/**
	 * Describes the columns of tables and views.
	 *
	 * @param catalog catalog name, or null
	 * @param schemaPattern schema pattern, or null
	 * @param tableNamePattern table name pattern, or null
	 * @param columnNamePattern column name pattern, or null
	 * @return one row per column, sorted by table and position
	 * @throws SQLException when D1 reports an error
	 */
	override fun getColumns(
		catalog: String?,
		schemaPattern: String?,
		tableNamePattern: String?,
		columnNamePattern: String?,
	): ResultSet {
		val rows = tables(catalog, schemaPattern, tableNamePattern).flatMap { table ->
			table.columns.mapIndexedNotNull { index, column ->
				if (!matches(columnNamePattern, column.name)) {
					return@mapIndexedNotNull null
				}
				val type = declaredType(column.declaredType)
				val notNull = column.notNull || column == table.rowIdAlias
				val numeric = type.sqlType in setOf(Types.BIGINT, Types.DOUBLE, Types.NUMERIC)
				listOf(
					null, SCHEMA, table.name, column.name, type.sqlType, type.name, type.size, null,
					type.decimalDigits, if (numeric) 10 else null,
					if (notNull) DatabaseMetaData.columnNoNulls else DatabaseMetaData.columnNullable,
					null, column.defaultValue, null, null, null, index + 1, if (notNull) "NO" else "YES",
					null, null, null, null,
					if (column == table.rowIdAlias) "YES" else "NO",
					if (column.generated) "YES" else "NO",
				)
			}
		}
		return rowsOf(COLUMN_COLUMNS, rows)
	}

	/** @return the one schema D1 has, `main` */
	override fun getSchemas(): ResultSet {
		return result(listOf("TABLE_SCHEM", "TABLE_CATALOG"), listOf(listOf(JsonPrimitive(SCHEMA), JsonNull)))
	}

	/** @return the one schema D1 has, `main` */
	override fun getSchemas(p0: String?, p1: String?): ResultSet = getSchemas()

	/** @return the kinds of table D1 has */
	override fun getTableTypes(): ResultSet {
		return result(listOf("TABLE_TYPE"), listOf(listOf(JsonPrimitive("TABLE")), listOf(JsonPrimitive("VIEW"))))
	}

	/**
	 * Returns this object as the given interface.
	 *
	 * @param iface interface to unwrap to
	 * @return this object
	 * @throws SQLException when it doesn't implement the interface
	 */
	override fun <T : Any?> unwrap(iface: Class<T>): T {
		if (!iface.isInstance(this)) {
			throw SQLException("The database metadata is not a ${iface.name}.")
		}
		return iface.cast(this)
	}

	/** @return whether this object implements the interface */
	override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

	// Fixed answers about what D1 and this driver support.

	/** @return false */
	override fun allProceduresAreCallable(): Boolean = false

	/** @return true */
	override fun allTablesAreSelectable(): Boolean = true

	/** @return the connection's JDBC URL */
	override fun getURL(): String = connection.url

	/** @return "" */
	override fun getUserName(): String = ""

	/** @return whether the connection blocks writes */
	override fun isReadOnly(): Boolean = connection.isReadOnly

	/** @return false */
	override fun nullsAreSortedHigh(): Boolean = false

	/** @return true */
	override fun nullsAreSortedLow(): Boolean = true

	/** @return false */
	override fun nullsAreSortedAtStart(): Boolean = false

	/** @return false */
	override fun nullsAreSortedAtEnd(): Boolean = false

	/** @return "Cloudflare D1" */
	override fun getDatabaseProductName(): String = "Cloudflare D1"

	/** @return "D1" */
	override fun getDatabaseProductVersion(): String = "D1"

	/** @return "D1 JDBC Driver" */
	override fun getDriverName(): String = "D1 JDBC Driver"

	/** @return "1.0" */
	override fun getDriverVersion(): String = "1.0"

	/** @return 1 */
	override fun getDriverMajorVersion(): Int = 1

	/** @return 0, meaning no limit or unknown */
	override fun getDriverMinorVersion(): Int = 0

	/** @return false */
	override fun usesLocalFiles(): Boolean = false

	/** @return false */
	override fun usesLocalFilePerTable(): Boolean = false

	/** @return false */
	override fun supportsMixedCaseIdentifiers(): Boolean = false

	/** @return false */
	override fun storesUpperCaseIdentifiers(): Boolean = false

	/** @return false */
	override fun storesLowerCaseIdentifiers(): Boolean = false

	/** @return true */
	override fun storesMixedCaseIdentifiers(): Boolean = true

	/** @return false */
	override fun supportsMixedCaseQuotedIdentifiers(): Boolean = false

	/** @return false */
	override fun storesUpperCaseQuotedIdentifiers(): Boolean = false

	/** @return false */
	override fun storesLowerCaseQuotedIdentifiers(): Boolean = false

	/** @return true */
	override fun storesMixedCaseQuotedIdentifiers(): Boolean = true

	/** @return "\"" */
	override fun getIdentifierQuoteString(): String = "\""

	/** @return "" */
	override fun getSQLKeywords(): String = ""

	/** @return "" */
	override fun getNumericFunctions(): String = ""

	/** @return "" */
	override fun getStringFunctions(): String = ""

	/** @return "" */
	override fun getSystemFunctions(): String = ""

	/** @return "" */
	override fun getTimeDateFunctions(): String = ""

	/** @return "\\" */
	override fun getSearchStringEscape(): String = "\\"

	/** @return "" */
	override fun getExtraNameCharacters(): String = ""

	/** @return true */
	override fun supportsAlterTableWithAddColumn(): Boolean = true

	/** @return false */
	override fun supportsAlterTableWithDropColumn(): Boolean = false

	/** @return true */
	override fun supportsColumnAliasing(): Boolean = true

	/** @return true */
	override fun nullPlusNonNullIsNull(): Boolean = true

	/** @return false, as CONVERT isn't supported */
	override fun supportsConvert(): Boolean = false

	/** @return false, as CONVERT isn't supported */
	override fun supportsConvert(p0: Int, p1: Int): Boolean = false

	/** @return true */
	override fun supportsTableCorrelationNames(): Boolean = true

	/** @return false */
	override fun supportsDifferentTableCorrelationNames(): Boolean = false

	/** @return true */
	override fun supportsExpressionsInOrderBy(): Boolean = true

	/** @return true */
	override fun supportsOrderByUnrelated(): Boolean = true

	/** @return true */
	override fun supportsGroupBy(): Boolean = true

	/** @return true */
	override fun supportsGroupByUnrelated(): Boolean = true

	/** @return true */
	override fun supportsGroupByBeyondSelect(): Boolean = true

	/** @return true */
	override fun supportsLikeEscapeClause(): Boolean = true

	/** @return true */
	override fun supportsMultipleResultSets(): Boolean = true

	/** @return false */
	override fun supportsMultipleTransactions(): Boolean = false

	/** @return true */
	override fun supportsNonNullableColumns(): Boolean = true

	/** @return true */
	override fun supportsMinimumSQLGrammar(): Boolean = true

	/** @return false */
	override fun supportsCoreSQLGrammar(): Boolean = false

	/** @return false */
	override fun supportsExtendedSQLGrammar(): Boolean = false

	/** @return false */
	override fun supportsANSI92EntryLevelSQL(): Boolean = false

	/** @return false */
	override fun supportsANSI92IntermediateSQL(): Boolean = false

	/** @return false */
	override fun supportsANSI92FullSQL(): Boolean = false

	/** @return false */
	override fun supportsIntegrityEnhancementFacility(): Boolean = false

	/** @return true */
	override fun supportsOuterJoins(): Boolean = true

	/** @return false */
	override fun supportsFullOuterJoins(): Boolean = false

	/** @return true */
	override fun supportsLimitedOuterJoins(): Boolean = true

	/** @return "schema" */
	override fun getSchemaTerm(): String = "schema"

	/** @return "procedure" */
	override fun getProcedureTerm(): String = "procedure"

	/** @return "catalog" */
	override fun getCatalogTerm(): String = "catalog"

	/** @return false */
	override fun isCatalogAtStart(): Boolean = false

	/** @return "." */
	override fun getCatalogSeparator(): String = "."

	/** @return false */
	override fun supportsSchemasInDataManipulation(): Boolean = false

	/** @return false */
	override fun supportsSchemasInProcedureCalls(): Boolean = false

	/** @return false */
	override fun supportsSchemasInTableDefinitions(): Boolean = false

	/** @return false */
	override fun supportsSchemasInIndexDefinitions(): Boolean = false

	/** @return false */
	override fun supportsSchemasInPrivilegeDefinitions(): Boolean = false

	/** @return false */
	override fun supportsCatalogsInDataManipulation(): Boolean = false

	/** @return false */
	override fun supportsCatalogsInProcedureCalls(): Boolean = false

	/** @return false */
	override fun supportsCatalogsInTableDefinitions(): Boolean = false

	/** @return false */
	override fun supportsCatalogsInIndexDefinitions(): Boolean = false

	/** @return false */
	override fun supportsCatalogsInPrivilegeDefinitions(): Boolean = false

	/** @return false */
	override fun supportsPositionedDelete(): Boolean = false

	/** @return false */
	override fun supportsPositionedUpdate(): Boolean = false

	/** @return false */
	override fun supportsSelectForUpdate(): Boolean = false

	/** @return false */
	override fun supportsStoredProcedures(): Boolean = false

	/** @return true */
	override fun supportsSubqueriesInComparisons(): Boolean = true

	/** @return true */
	override fun supportsSubqueriesInExists(): Boolean = true

	/** @return true */
	override fun supportsSubqueriesInIns(): Boolean = true

	/** @return true */
	override fun supportsSubqueriesInQuantifieds(): Boolean = true

	/** @return true */
	override fun supportsCorrelatedSubqueries(): Boolean = true

	/** @return true */
	override fun supportsUnion(): Boolean = true

	/** @return true */
	override fun supportsUnionAll(): Boolean = true

	/** @return false */
	override fun supportsOpenCursorsAcrossCommit(): Boolean = false

	/** @return false */
	override fun supportsOpenCursorsAcrossRollback(): Boolean = false

	/** @return false */
	override fun supportsOpenStatementsAcrossCommit(): Boolean = false

	/** @return false */
	override fun supportsOpenStatementsAcrossRollback(): Boolean = false

	/** @return 0, meaning no limit or unknown */
	override fun getMaxBinaryLiteralLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxCharLiteralLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxColumnNameLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxColumnsInGroupBy(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxColumnsInIndex(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxColumnsInOrderBy(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxColumnsInSelect(): Int = 0

	/** @return 100 */
	override fun getMaxColumnsInTable(): Int = 100

	/** @return 0, meaning no limit or unknown */
	override fun getMaxConnections(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxCursorNameLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxIndexLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxSchemaNameLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxProcedureNameLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxCatalogNameLength(): Int = 0

	/** @return 2_000_000 */
	override fun getMaxRowSize(): Int = 2_000_000

	/** @return false */
	override fun doesMaxRowSizeIncludeBlobs(): Boolean = false

	/** @return 100_000 */
	override fun getMaxStatementLength(): Int = 100_000

	/** @return 0, meaning no limit or unknown */
	override fun getMaxStatements(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxTableNameLength(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxTablesInSelect(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getMaxUserNameLength(): Int = 0

	/** @return Connection.TRANSACTION_NONE */
	override fun getDefaultTransactionIsolation(): Int = Connection.TRANSACTION_NONE

	/** @return false */
	override fun supportsTransactions(): Boolean = false

	/** @return true only for no transactions */
	override fun supportsTransactionIsolationLevel(p0: Int): Boolean = p0 == Connection.TRANSACTION_NONE

	/** @return false */
	override fun supportsDataDefinitionAndDataManipulationTransactions(): Boolean = false

	/** @return false */
	override fun supportsDataManipulationTransactionsOnly(): Boolean = false

	/** @return false */
	override fun dataDefinitionCausesTransactionCommit(): Boolean = false

	/** @return false */
	override fun dataDefinitionIgnoredInTransactions(): Boolean = false

	/** @return an empty result, filled in once schema browsing is built */
	override fun getProcedures(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED1", "RESERVED2", "RESERVED3", "REMARKS", "PROCEDURE_TYPE", "SPECIFIC_NAME")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getProcedureColumns(p0: String?, p1: String?, p2: String?, p3: String?): ResultSet = emptyResult("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME")


	/** @return an empty result, filled in once schema browsing is built */
	override fun getCatalogs(): ResultSet = emptyResult("TABLE_CAT")


	/** @return an empty result, filled in once schema browsing is built */
	override fun getColumnPrivileges(p0: String?, p1: String?, p2: String?, p3: String?): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getTablePrivileges(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getBestRowIdentifier(p0: String?, p1: String?, p2: String?, p3: Int, p4: Boolean): ResultSet = emptyResult("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getVersionColumns(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getPrimaryKeys(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getImportedKeys(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getExportedKeys(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getCrossReference(p0: String?, p1: String?, p2: String?, p3: String?, p4: String?, p5: String?): ResultSet = emptyResult("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getTypeInfo(): ResultSet = emptyResult("TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX", "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getIndexInfo(p0: String?, p1: String?, p2: String?, p3: Boolean, p4: Boolean): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION")

	/** @return true only for forward-only results */
	override fun supportsResultSetType(p0: Int): Boolean = p0 == ResultSet.TYPE_FORWARD_ONLY

	/** @return true only for forward-only, read-only results */
	override fun supportsResultSetConcurrency(p0: Int, p1: Int): Boolean = p0 == ResultSet.TYPE_FORWARD_ONLY && p1 == ResultSet.CONCUR_READ_ONLY

	/** @return false */
	override fun ownUpdatesAreVisible(p0: Int): Boolean = false

	/** @return false */
	override fun ownDeletesAreVisible(p0: Int): Boolean = false

	/** @return false */
	override fun ownInsertsAreVisible(p0: Int): Boolean = false

	/** @return false */
	override fun othersUpdatesAreVisible(p0: Int): Boolean = false

	/** @return false */
	override fun othersDeletesAreVisible(p0: Int): Boolean = false

	/** @return false */
	override fun othersInsertsAreVisible(p0: Int): Boolean = false

	/** @return false */
	override fun updatesAreDetected(p0: Int): Boolean = false

	/** @return false */
	override fun deletesAreDetected(p0: Int): Boolean = false

	/** @return false */
	override fun insertsAreDetected(p0: Int): Boolean = false

	/** @return true */
	override fun supportsBatchUpdates(): Boolean = true

	/** @return an empty result, filled in once schema browsing is built */
	override fun getUDTs(p0: String?, p1: String?, p2: String?, p3: IntArray?): ResultSet = emptyResult("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE")

	/** @return the connection this metadata belongs to */
	override fun getConnection(): Connection = connection

	/** @return false */
	override fun supportsSavepoints(): Boolean = false

	/** @return false */
	override fun supportsNamedParameters(): Boolean = false

	/** @return false */
	override fun supportsMultipleOpenResults(): Boolean = false

	/** @return false */
	override fun supportsGetGeneratedKeys(): Boolean = false

	/** @return an empty result, filled in once schema browsing is built */
	override fun getSuperTypes(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getSuperTables(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getAttributes(p0: String?, p1: String?, p2: String?, p3: String?): ResultSet = emptyResult("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE", "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE")

	/** @return true only for holding results over commit */
	override fun supportsResultSetHoldability(p0: Int): Boolean = p0 == ResultSet.HOLD_CURSORS_OVER_COMMIT

	/** @return ResultSet.HOLD_CURSORS_OVER_COMMIT */
	override fun getResultSetHoldability(): Int = ResultSet.HOLD_CURSORS_OVER_COMMIT

	/** @return 0, meaning no limit or unknown */
	override fun getDatabaseMajorVersion(): Int = 0

	/** @return 0, meaning no limit or unknown */
	override fun getDatabaseMinorVersion(): Int = 0

	/** @return 4 */
	override fun getJDBCMajorVersion(): Int = 4

	/** @return 3 */
	override fun getJDBCMinorVersion(): Int = 3

	/** @return DatabaseMetaData.sqlStateSQL */
	override fun getSQLStateType(): Int = DatabaseMetaData.sqlStateSQL

	/** @return false */
	override fun locatorsUpdateCopy(): Boolean = false

	/** @return false */
	override fun supportsStatementPooling(): Boolean = false

	/** @return that row IDs aren't supported */
	override fun getRowIdLifetime(): RowIdLifetime = RowIdLifetime.ROWID_UNSUPPORTED

	/** @return false */
	override fun supportsStoredFunctionsUsingCallSyntax(): Boolean = false

	/** @return false */
	override fun autoCommitFailureClosesAllResultSets(): Boolean = false

	/** @return an empty result, filled in once schema browsing is built */
	override fun getClientInfoProperties(): ResultSet = emptyResult("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getFunctions(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getFunctionColumns(p0: String?, p1: String?, p2: String?, p3: String?): ResultSet = emptyResult("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getPseudoColumns(p0: String?, p1: String?, p2: String?, p3: String?): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE")

	/** @return false */
	override fun generatedKeyAlwaysReturned(): Boolean = false


	private companion object {
		/** The one schema D1 has. */
		const val SCHEMA = "main"

		/** Columns of [getColumns], in the order JDBC sets. */
		val COLUMN_COLUMNS = listOf(
			"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
			"BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
			"SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA",
			"SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN",
		)

		/** Columns of [getTables], in the order JDBC sets. */
		val TABLE_COLUMNS = listOf(
			"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
			"TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION",
		)
	}
}
