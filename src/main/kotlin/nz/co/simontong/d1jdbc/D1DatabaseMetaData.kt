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
	 * @param value text, number, boolean, or null
	 * @return the JSON value
	 */
	private fun json(value: Any?): JsonElement {
		return when (value) {
			null -> JsonNull
			is Number -> JsonPrimitive(value)
			is Boolean -> JsonPrimitive(value)
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
	 * Finds the tables a metadata call asks about, each with its database name.
	 *
	 * @param catalog database name, null for every database, or empty for none
	 * @param schemaPattern schema pattern, which must match `main`
	 * @param tablePattern table name pattern
	 * @return matching tables, sorted by database name
	 * @throws SQLException when D1 reports an error
	 */
	private fun tables(catalog: String?, schemaPattern: String?, tablePattern: String?): List<Pair<String, SchemaTable>> {
		if (!matches(schemaPattern, SCHEMA)) {
			return emptyList()
		}
		return connection.schemasFor(catalog).flatMap { (name, schema) ->
			schema.tables.filter { matches(tablePattern, it.name) }.map { name to it }
		}
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
			.filter { (_, table) -> types == null || types.any { type -> type.equals(table.type, ignoreCase = true) } }
			.sortedWith(compareBy({ it.second.type }, { it.first.lowercase() }, { it.second.name.lowercase() }))
			.map { (name, table) -> listOf(name, SCHEMA, table.name, table.type, null, null, null, null, null, null) }
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
		val rows = tables(catalog, schemaPattern, tableNamePattern).flatMap { (catalogName, table) ->
			table.columns.mapIndexedNotNull { index, column ->
				if (!matches(columnNamePattern, column.name)) {
					return@mapIndexedNotNull null
				}
				val type = declaredType(column.declaredType)
				val notNull = column.notNull || column == table.rowIdAlias
				val numeric = type.sqlType in setOf(Types.BIGINT, Types.DOUBLE, Types.NUMERIC)
				// JetBrains IDEs read a missing size as 0 and show TEXT(0), but read -1 as no size.
				val size = type.size ?: -1
				listOf(
					catalogName, SCHEMA, table.name, column.name, type.sqlType, type.name, size, null,
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

	/**
	 * Finds the one table a metadata call names exactly.
	 *
	 * @param catalog database name, null for every database, or empty for none
	 * @param schema schema name, or null
	 * @param table table name, matched ignoring case
	 * @return the database name and table, or null when there's no such table
	 * @throws SQLException when D1 reports an error
	 */
	private fun table(catalog: String?, schema: String?, table: String?): Pair<String, SchemaTable>? {
		if ((schema != null && !schema.equals(SCHEMA, ignoreCase = true)) || table == null) {
			return null
		}
		return connection.schemasFor(catalog).firstNotNullOfOrNull { (name, found) -> found.table(table)?.let { name to it } }
	}

	/**
	 * Lists a table's primary key columns.
	 *
	 * @param catalog catalog name, or null
	 * @param schema schema name, or null
	 * @param table table name
	 * @return one row per key column, sorted by column name as JDBC asks
	 * @throws SQLException when D1 reports an error
	 */
	override fun getPrimaryKeys(catalog: String?, schema: String?, table: String?): ResultSet {
		val (catalogName, found) = table(catalog, schema, table) ?: return rowsOf(PRIMARY_KEY_COLUMNS, emptyList())
		val rows = found.columns
			.filter { it.primaryKeyPosition > 0 }
			.sortedBy { it.name.lowercase() }
			.map { listOf(catalogName, SCHEMA, found.name, it.name, it.primaryKeyPosition, found.primaryKeyName) }
		return rowsOf(PRIMARY_KEY_COLUMNS, rows)
	}

	/**
	 * Lists a table's indexes and their columns.
	 *
	 * @param catalog catalog name, or null
	 * @param schema schema name, or null
	 * @param table table name
	 * @param unique whether to list only unique indexes
	 * @param approximate ignored, as no statistics are returned
	 * @return one row per index column, unique indexes first
	 * @throws SQLException when D1 reports an error
	 */
	override fun getIndexInfo(
		catalog: String?,
		schema: String?,
		table: String?,
		unique: Boolean,
		approximate: Boolean,
	): ResultSet {
		val (catalogName, found) = table(catalog, schema, table) ?: return rowsOf(INDEX_COLUMNS, emptyList())
		val rows = found.indexes
			.filter { it.unique || !unique }
			.sortedWith(compareBy({ !it.unique }, { it.name.lowercase() }))
			.flatMap { index ->
				index.columns.mapIndexed { position, column ->
					listOf(
						catalogName, SCHEMA, found.name, !index.unique, null, index.name, DatabaseMetaData.tableIndexOther,
						position + 1, column.name, if (column.descending) "D" else "A", null, null, null,
					)
				}
			}
		return rowsOf(INDEX_COLUMNS, rows)
	}

	/**
	 * Lists foreign key columns, keeping each key's columns together in key order.
	 *
	 * @param catalog catalog name, or null
	 * @param schema schema name, or null
	 * @param parentTable referenced table name, or null for any
	 * @param childTable referencing table name, or null for any
	 * @return the result set
	 * @throws SQLException when D1 reports an error
	 */
	private fun foreignKeys(catalog: String?, schema: String?, parentTable: String?, childTable: String?): ResultSet {
		if (schema != null && !schema.equals(SCHEMA, ignoreCase = true)) {
			return rowsOf(FOREIGN_KEY_COLUMNS, emptyList())
		}
		val rows = mutableListOf<List<Any?>>()
		for ((catalogName, schemaTables) in connection.schemasFor(catalog)) {
			val children = schemaTables.tables.filter { childTable == null || it.name.equals(childTable, ignoreCase = true) }
			for (child in children) {
				rows.addAll(foreignKeyRows(catalogName, schemaTables, child, parentTable))
			}
		}
		return rowsOf(FOREIGN_KEY_COLUMNS, rows)
	}

	/**
	 * Builds the foreign key rows of one child table.
	 *
	 * @param catalogName the database's name
	 * @param schemaTables the database's schema
	 * @param child the referencing table
	 * @param parentTable referenced table name, or null for any
	 * @return one row per key column
	 */
	private fun foreignKeyRows(
		catalogName: String,
		schemaTables: D1Schema,
		child: SchemaTable,
		parentTable: String?,
	): List<List<Any?>> {
		val rows = mutableListOf<List<Any?>>()
		val keys = child.foreignKeys.filter { parentTable == null || it.parentTable.equals(parentTable, ignoreCase = true) }
		for (key in keys.sortedBy { it.parentTable.lowercase() }) {
			val parent = schemaTables.table(key.parentTable)
			// A key with no parent columns refers to the parent's primary key.
			val parentKey = parent?.columns.orEmpty().filter { it.primaryKeyPosition > 0 }.sortedBy { it.primaryKeyPosition }
			key.columns.forEachIndexed { position, (from, to) ->
				rows += listOf(
					catalogName, SCHEMA, parent?.name ?: key.parentTable, to ?: parentKey.getOrNull(position)?.name,
					catalogName, SCHEMA, child.name, from, position + 1, ruleOf(key.onUpdate), ruleOf(key.onDelete),
					key.name, null, DatabaseMetaData.importedKeyNotDeferrable,
				)
			}
		}
		return rows
	}

	/**
	 * Turns a SQLite foreign key action into its JDBC constant.
	 *
	 * @param action action as `pragma foreign_key_list` writes it
	 * @return a [DatabaseMetaData] importedKey constant
	 */
	private fun ruleOf(action: String): Int {
		return when (action.uppercase()) {
			"CASCADE" -> DatabaseMetaData.importedKeyCascade
			"RESTRICT" -> DatabaseMetaData.importedKeyRestrict
			"SET NULL" -> DatabaseMetaData.importedKeySetNull
			"SET DEFAULT" -> DatabaseMetaData.importedKeySetDefault
			else -> DatabaseMetaData.importedKeyNoAction
		}
	}

	/**
	 * Lists the foreign keys a table holds.
	 *
	 * @param catalog catalog name, or null
	 * @param schema schema name, or null
	 * @param table referencing table name
	 * @return one row per key column
	 * @throws SQLException when D1 reports an error
	 */
	override fun getImportedKeys(catalog: String?, schema: String?, table: String?): ResultSet {
		return foreignKeys(catalog, schema, null, table ?: return rowsOf(FOREIGN_KEY_COLUMNS, emptyList()))
	}

	/**
	 * Lists the foreign keys that refer to a table.
	 *
	 * @param catalog catalog name, or null
	 * @param schema schema name, or null
	 * @param table referenced table name
	 * @return one row per key column
	 * @throws SQLException when D1 reports an error
	 */
	override fun getExportedKeys(catalog: String?, schema: String?, table: String?): ResultSet {
		return foreignKeys(catalog, schema, table ?: return rowsOf(FOREIGN_KEY_COLUMNS, emptyList()), null)
	}

	/**
	 * Lists the foreign keys from one table to another.
	 *
	 * @param parentCatalog referenced table's catalog, or null
	 * @param parentSchema referenced table's schema, or null
	 * @param parentTable referenced table name
	 * @param foreignCatalog referencing table's catalog, or null
	 * @param foreignSchema referencing table's schema, or null
	 * @param foreignTable referencing table name
	 * @return one row per key column
	 * @throws SQLException when D1 reports an error
	 */
	override fun getCrossReference(
		parentCatalog: String?,
		parentSchema: String?,
		parentTable: String?,
		foreignCatalog: String?,
		foreignSchema: String?,
		foreignTable: String?,
	): ResultSet {
		// A foreign key can only refer to a table in the same database.
		val differentDatabase = !parentCatalog.isNullOrEmpty() && !foreignCatalog.isNullOrEmpty() && parentCatalog != foreignCatalog
		if (parentTable == null || foreignTable == null || differentDatabase) {
			return rowsOf(FOREIGN_KEY_COLUMNS, emptyList())
		}
		if (foreignSchema != null && !foreignSchema.equals(SCHEMA, ignoreCase = true)) {
			return rowsOf(FOREIGN_KEY_COLUMNS, emptyList())
		}
		return foreignKeys(foreignCatalog.takeUnless { it.isNullOrEmpty() } ?: parentCatalog, parentSchema, parentTable, foreignTable)
	}

	/**
	 * Lists SQLite's storage types, which every declared type maps to.
	 *
	 * @return one row per type, sorted by JDBC type
	 */
	override fun getTypeInfo(): ResultSet {
		// Text and blobs can be as big as D1's 2 MB row limit.
		val rows = listOf(
			typeRow("INTEGER", Types.BIGINT, 19, null, null, autoIncrement = true),
			typeRow("NUMERIC", Types.NUMERIC, 19, null, null, autoIncrement = false),
			typeRow("REAL", Types.DOUBLE, 15, null, null, autoIncrement = false),
			typeRow("TEXT", Types.VARCHAR, 2_000_000, "'", "'", autoIncrement = false),
			typeRow("BLOB", Types.BLOB, 2_000_000, "X'", "'", autoIncrement = false),
		)
		return rowsOf(TYPE_COLUMNS, rows)
	}

	/**
	 * Builds one row of [getTypeInfo].
	 *
	 * @param name type name
	 * @param sqlType [Types] constant
	 * @param precision largest size or precision
	 * @param prefix text before a literal, or null
	 * @param suffix text after a literal, or null
	 * @param autoIncrement whether a column of this type can fill itself in
	 * @return the row values
	 */
	private fun typeRow(
		name: String,
		sqlType: Int,
		precision: Int,
		prefix: String?,
		suffix: String?,
		autoIncrement: Boolean,
	): List<Any?> {
		val numeric = sqlType in setOf(Types.BIGINT, Types.NUMERIC, Types.DOUBLE)
		return listOf(
			name, sqlType, precision, prefix, suffix, null, DatabaseMetaData.typeNullable, sqlType == Types.VARCHAR,
			DatabaseMetaData.typeSearchable, false, false, autoIncrement, null, 0, 0, null, null,
			if (numeric) 10 else null,
		)
	}

	/**
	 * Names the columns that identify a row, which is the primary key or else the row ID.
	 *
	 * @param catalog catalog name, or null
	 * @param schema schema name, or null
	 * @param table table name
	 * @param scope how long the identifier must stay valid, ignored as it never changes
	 * @param nullable whether nullable columns may be used, ignored
	 * @return one row per identifying column
	 * @throws SQLException when D1 reports an error
	 */
	override fun getBestRowIdentifier(
		catalog: String?,
		schema: String?,
		table: String?,
		scope: Int,
		nullable: Boolean,
	): ResultSet {
		val found = table(catalog, schema, table)?.second
		if (found == null || found.type != "TABLE") {
			return rowsOf(ROW_ID_COLUMNS, emptyList())
		}
		val keys = found.columns.filter { it.primaryKeyPosition > 0 }.sortedBy { it.primaryKeyPosition }
		if (keys.isEmpty() && !found.withoutRowId) {
			val rowId = listOf(
				DatabaseMetaData.bestRowSession, "rowid", Types.BIGINT, "INTEGER", 19, null, 0,
				DatabaseMetaData.bestRowPseudo,
			)
			return rowsOf(ROW_ID_COLUMNS, listOf(rowId))
		}
		val rows = keys.map { column ->
			val type = declaredType(column.declaredType)
			listOf(
				DatabaseMetaData.bestRowSession, column.name, type.sqlType, type.name, type.size, null,
				type.decimalDigits, DatabaseMetaData.bestRowNotPseudo,
			)
		}
		return rowsOf(ROW_ID_COLUMNS, rows)
	}

	/**
	 * Lists the `main` schema of every database.
	 *
	 * @return one row per database
	 * @throws SQLException when the database list can't be loaded
	 */
	override fun getSchemas(): ResultSet = getSchemas(null, null)

	/**
	 * Lists the `main` schema of the databases asked about.
	 *
	 * @param catalog database name, or null for every database
	 * @param schemaPattern schema pattern, or null
	 * @return one row per matching database
	 * @throws SQLException when the database list can't be loaded
	 */
	override fun getSchemas(catalog: String?, schemaPattern: String?): ResultSet {
		if (!matches(schemaPattern, SCHEMA)) {
			return rowsOf(listOf("TABLE_SCHEM", "TABLE_CATALOG"), emptyList())
		}
		val rows = connection.databases()
			.filter { catalog == null || it.name == catalog }
			.map { listOf(SCHEMA, it.name) }
		return rowsOf(listOf("TABLE_SCHEM", "TABLE_CATALOG"), rows)
	}

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

	/** @return the version the driver was built as */
	override fun getDriverVersion(): String = DRIVER_VERSION

	/** @return the driver's major version */
	override fun getDriverMajorVersion(): Int = DRIVER_MAJOR_VERSION

	/** @return the driver's minor version */
	override fun getDriverMinorVersion(): Int = DRIVER_MINOR_VERSION

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

	/** @return "database", as each catalog is one D1 database */
	override fun getCatalogTerm(): String = "database"

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


	/**
	 * Lists every D1 database the token can reach, asking Cloudflare again each time.
	 *
	 * @return one row per database, sorted by name
	 * @throws SQLException when the database list can't be loaded
	 */
	override fun getCatalogs(): ResultSet {
		return rowsOf(listOf("TABLE_CAT"), connection.databases(reload = true).map { listOf(it.name) })
	}


	/** @return an empty result, filled in once schema browsing is built */
	override fun getColumnPrivileges(p0: String?, p1: String?, p2: String?, p3: String?): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE")

	/** @return an empty result, filled in once schema browsing is built */
	override fun getTablePrivileges(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE")


	/** @return an empty result, filled in once schema browsing is built */
	override fun getVersionColumns(p0: String?, p1: String?, p2: String?): ResultSet = emptyResult("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN")







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

	/** @return true, as an INSERT can return the new row's ID */
	override fun supportsGetGeneratedKeys(): Boolean = true

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

		/** Columns of [getPrimaryKeys], in the order JDBC sets. */
		val PRIMARY_KEY_COLUMNS = listOf("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME")

		/** Columns of [getIndexInfo], in the order JDBC sets. */
		val INDEX_COLUMNS = listOf(
			"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME", "TYPE",
			"ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION",
		)

		/** Columns of the foreign key methods, in the order JDBC sets. */
		val FOREIGN_KEY_COLUMNS = listOf(
			"PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM",
			"FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME",
			"DEFERRABILITY",
		)

		/** Columns of [getTypeInfo], in the order JDBC sets. */
		val TYPE_COLUMNS = listOf(
			"TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX", "CREATE_PARAMS", "NULLABLE",
			"CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE", "AUTO_INCREMENT",
			"LOCAL_TYPE_NAME", "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX",
		)

		/** Columns of [getBestRowIdentifier], in the order JDBC sets. */
		val ROW_ID_COLUMNS = listOf(
			"SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS",
			"PSEUDO_COLUMN",
		)

		/** Columns of [getTables], in the order JDBC sets. */
		val TABLE_COLUMNS = listOf(
			"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
			"TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION",
		)
	}
}
