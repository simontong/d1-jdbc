package nz.co.simontong.d1jdbc

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * One column of a table or view.
 *
 * @property name column name
 * @property declaredType type as written in CREATE TABLE, or empty
 * @property notNull whether the column has NOT NULL
 * @property defaultValue default value as SQL text, or null
 * @property primaryKeyPosition position in the primary key starting at 1, or 0 when not part of it
 * @property generated whether the column is generated
 */
internal data class SchemaColumn(
	val name: String,
	val declaredType: String,
	val notNull: Boolean,
	val defaultValue: String?,
	val primaryKeyPosition: Int,
	val generated: Boolean,
)

/**
 * One column of an index.
 *
 * @property name column name, or null for an expression
 * @property descending whether the index sorts this column in descending order
 */
internal data class SchemaIndexColumn(
	val name: String?,
	val descending: Boolean,
)

/**
 * One index on a table.
 *
 * @property name index name
 * @property unique whether the index is unique
 * @property columns key columns in order
 */
internal data class SchemaIndex(
	val name: String,
	val unique: Boolean,
	val columns: List<SchemaIndexColumn>,
)

/**
 * One foreign key on a table.
 *
 * @property name name given in CREATE TABLE, or null
 * @property parentTable table the key refers to
 * @property columns child and parent column pairs, where a null parent means its primary key
 * @property onUpdate ON UPDATE action, like `CASCADE`
 * @property onDelete ON DELETE action, like `CASCADE`
 */
internal data class SchemaForeignKey(
	val name: String?,
	val parentTable: String,
	val columns: List<Pair<String, String?>>,
	val onUpdate: String,
	val onDelete: String,
)

/**
 * One table or view.
 *
 * @property name table name
 * @property type `TABLE` or `VIEW`
 * @property withoutRowId whether the table is WITHOUT ROWID
 * @property columns visible columns in order
 * @property primaryKeyName primary key name given in CREATE TABLE, or null
 * @property indexes indexes on the table
 * @property foreignKeys foreign keys on the table
 */
internal data class SchemaTable(
	val name: String,
	val type: String,
	val withoutRowId: Boolean,
	val columns: List<SchemaColumn>,
	val primaryKeyName: String?,
	val indexes: List<SchemaIndex>,
	val foreignKeys: List<SchemaForeignKey>,
) {
	/** The column that is an alias for the row ID, if any. */
	val rowIdAlias: SchemaColumn?
		get() {
			val keys = columns.filter { it.primaryKeyPosition > 0 }
			val key = keys.singleOrNull() ?: return null
			return key.takeIf { type == "TABLE" && !withoutRowId && it.declaredType.equals("integer", ignoreCase = true) }
		}
}

/**
 * The tables and views of a D1 database, without D1's and SQLite's own tables.
 *
 * @property tables tables and views sorted by name
 */
internal data class D1Schema(
	val tables: List<SchemaTable>,
) {
	/**
	 * Finds a table by name, ignoring case as SQLite does.
	 *
	 * @param name table name
	 * @return the table, or null
	 */
	fun table(name: String): SchemaTable? = tables.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** Most statements sent in one request while loading the schema. */
private const val STATEMENTS_PER_REQUEST = 200

/**
 * Says whether a table belongs to D1 or SQLite, so tools shouldn't show it.
 *
 * @param name table name
 * @return true for a system table
 */
private fun isSystemTable(name: String): Boolean {
	return name.startsWith("sqlite_", ignoreCase = true) || name.startsWith("_cf_", ignoreCase = true)
}

/**
 * Quotes a name for use in a PRAGMA.
 *
 * @param name table or index name
 * @return the quoted name
 */
private fun quoted(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/**
 * Reads a JSON value as text.
 *
 * @return the text, or null for JSON null
 */
private fun JsonElement.text(): String? = if (this is JsonNull) null else (this as JsonPrimitive).content

/**
 * Reads a JSON value as a whole number.
 *
 * @return the number
 */
private fun JsonElement.number(): Int = (this as JsonPrimitive).content.toDouble().toInt()

/**
 * Reads the rows of a result as maps from column name to value.
 *
 * @param result one statement's result, or null when it failed
 * @return one map per row
 */
private fun records(result: D1Result?): List<Map<String, JsonElement>> {
	if (result == null) {
		return emptyList()
	}
	return result.rows.map { row -> result.columns.zip(row).toMap() }
}

/**
 * Runs a batch, and when one statement fails, splits the batch to run the rest without it.
 *
 * A view whose table was dropped makes its PRAGMA fail, and D1 fails the whole batch for it.
 *
 * @param client client for the account
 * @param databaseId database to run them on
 * @param queries statements to run
 * @param failing collects each statement that failed on its own
 * @return one result per statement, null for each that failed
 * @throws java.sql.SQLException when Cloudflare fails for any reason other than the SQL
 */
private fun runSkippingFailures(
	client: D1Client,
	databaseId: String,
	queries: List<D1Query>,
	failing: MutableSet<String>,
): List<D1Result?> {
	try {
		return client.execute(databaseId, queries, retry = true)
	} catch (error: java.sql.SQLException) {
		if (error.errorCode != D1Client.SQL_ERROR_CODE) {
			throw error
		}
		if (queries.size == 1) {
			failing += queries.single().sql
			return listOf(null)
		}
	}
	val half = queries.size / 2
	return runSkippingFailures(client, databaseId, queries.subList(0, half), failing) +
		runSkippingFailures(client, databaseId, queries.subList(half, queries.size), failing)
}

/**
 * Loads the whole schema in two requests, as Cloudflare limits how many API calls an account makes.
 *
 * @param client client for the account
 * @param databaseId database to read
 * @param failing statements to leave out, which also collects new failures
 * @return the schema
 * @throws java.sql.SQLException when D1 reports an error
 */
internal fun loadSchema(
	client: D1Client,
	databaseId: String,
	failing: MutableSet<String> = mutableSetOf(),
): D1Schema {
	val (tableList, schemaList) = client.execute(
		databaseId,
		listOf(
			D1Query("pragma table_list"),
			D1Query("select type, name, tbl_name, sql from sqlite_schema where type in ('table', 'index')"),
		),
		retry = true,
	)
	val found = records(tableList).filter { row ->
		val name = row.getValue("name").text()!!
		row.getValue("schema").text() == "main" && row.getValue("type").text() in setOf("table", "view") && !isSystemTable(name)
	}
	val tableNames = found.map { it.getValue("name").text()!! }.toSet()
	val (tableRows, indexRows) = records(schemaList).partition { it.getValue("type").text() == "table" }
	val indexNames = indexRows
		.filter { it.getValue("tbl_name").text() in tableNames }
		.map { it.getValue("name").text()!! }
	val tableSql = tableRows.associate { it.getValue("name").text()!! to it.getValue("sql").text() }

	// Ask for every table's details, then every index's columns, in as few requests as possible.
	val queries = mutableListOf<D1Query>()
	for (row in found) {
		val name = quoted(row.getValue("name").text()!!)
		queries += D1Query("pragma table_xinfo($name)")
		queries += D1Query("pragma index_list($name)")
		queries += D1Query("pragma foreign_key_list($name)")
	}
	indexNames.forEach { queries += D1Query("pragma index_xinfo(${quoted(it)})") }
	// Statements that failed last time, like a broken view's, are left out so they don't cost calls again.
	val toSend = queries.filter { it.sql !in failing }
	val sent = toSend.chunked(STATEMENTS_PER_REQUEST).flatMap { runSkippingFailures(client, databaseId, it, failing) }
	val bySql = toSend.map { it.sql }.zip(sent).toMap()
	val results = queries.map { bySql[it.sql] }

	val indexColumns = indexNames.indices.associate { index ->
		val columns = records(results[found.size * 3 + index])
			.filter { it.getValue("key").number() == 1 }
			.map { SchemaIndexColumn(it.getValue("name").text(), it.getValue("desc").number() == 1) }
		indexNames[index] to columns
	}
	val tables = found.mapIndexed { position, row ->
		val (columns, indexes, foreignKeys) = results.subList(position * 3, position * 3 + 3)
		val name = row.getValue("name").text()!!
		val names = constraintNames(tableSql[name])
		SchemaTable(
			name = name,
			type = if (row.getValue("type").text() == "view") "VIEW" else "TABLE",
			withoutRowId = row.getValue("wr").number() == 1,
			columns = readColumns(columns),
			primaryKeyName = names.primaryKey,
			indexes = records(indexes).map { index ->
				val name = index.getValue("name").text()!!
				SchemaIndex(name, index.getValue("unique").number() == 1, indexColumns[name].orEmpty())
			},
			foreignKeys = readForeignKeys(foreignKeys, names.foreignKeys),
		)
	}
	return D1Schema(tables.sortedBy { it.name.lowercase() })
}

/**
 * Reads the visible columns from `pragma table_xinfo`.
 *
 * @param result the PRAGMA's result, or null when it failed
 * @return columns in order
 */
private fun readColumns(result: D1Result?): List<SchemaColumn> {
	// Hidden 1 marks a virtual table's hidden column, and 2 or 3 a generated column.
	return records(result).filter { it.getValue("hidden").number() != 1 }.map { row ->
		SchemaColumn(
			name = row.getValue("name").text()!!,
			declaredType = row.getValue("type").text().orEmpty(),
			notNull = row.getValue("notnull").number() == 1,
			defaultValue = row.getValue("dflt_value").text(),
			primaryKeyPosition = row.getValue("pk").number(),
			generated = row.getValue("hidden").number() >= 2,
		)
	}
}

/**
 * Reads foreign keys from `pragma foreign_key_list`, grouping the columns of each key.
 *
 * @param result the PRAGMA's result, or null when it failed
 * @param named keys that CREATE TABLE names
 * @return foreign keys in order
 */
private fun readForeignKeys(result: D1Result?, named: List<NamedForeignKey>): List<SchemaForeignKey> {
	val unused = named.toMutableList()
	return records(result)
		.groupBy { it.getValue("id").number() }
		.toSortedMap()
		.values
		.map { rows ->
			val ordered = rows.sortedBy { it.getValue("seq").number() }
			val first = ordered.first()
			val parentTable = first.getValue("table").text()!!
			val columns = ordered.map { it.getValue("from").text()!! to it.getValue("to").text() }

			// The PRAGMA has no names, so match a named key by its parent and child columns.
			val match = unused.firstOrNull { key ->
				key.parentTable.equals(parentTable, ignoreCase = true)
					&& key.columns.map { it.lowercase() } == columns.map { it.first.lowercase() }
			}
			match?.let { unused.remove(it) }
			SchemaForeignKey(
				name = match?.name,
				parentTable = parentTable,
				columns = columns,
				onUpdate = first.getValue("on_update").text()!!,
				onDelete = first.getValue("on_delete").text()!!,
			)
		}
}

/**
 * Keeps the schema for a short time, so a tool reading it table by table makes only two requests.
 *
 * @param client client for the account
 * @param databaseId database the schema belongs to
 * @param maxAge how long a loaded schema is reused
 * @param clock returns the current time in nanoseconds
 */
internal class SchemaCache(
	private val client: D1Client,
	private val databaseId: String,
	private val maxAge: Duration,
	private val clock: () -> Long = System::nanoTime,
) {
	@Volatile
	private var schema: D1Schema? = null

	/** Schema statements that failed, left out of later loads until the schema is cleared. */
	private val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

	@Volatile
	private var loadedAt = 0L

	/**
	 * Returns the schema, loading it when there's none or it's too old.
	 *
	 * @return the schema
	 * @throws java.sql.SQLException when D1 reports an error
	 */
	@Synchronized
	fun get(): D1Schema {
		val cached = schema
		if (cached != null && clock() - loadedAt < maxAge.toNanos()) {
			return cached
		}
		val loaded = try {
			loadSchema(client, databaseId, failing)
		} catch (error: RuntimeException) {
			// A missing field or wrong type in D1's reply shows up as one of these.
			throw java.sql.SQLNonTransientConnectionException(
				"D1 sent a schema reply the driver could not read.",
				"08000",
				error,
			)
		}
		schema = loaded
		loadedAt = clock()
		return loaded
	}

	/** Forgets the schema, so the next call loads it again. */
	@Synchronized
	fun clear() {
		schema = null
		failing.clear()
	}

	/**
	 * Forgets the schema once it's too old to be used, which frees its memory. It takes no lock, so a
	 * slow load of one database never holds up others.
	 */
	fun dropIfStale() {
		if (schema != null && clock() - loadedAt >= maxAge.toNanos()) {
			schema = null
		}
	}
}

/** A declared type with an optional size, like `varchar(20)` or `decimal(10, 2)`. */
private val SIZED_TYPE = Regex("""^(.*?)\s*\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)$""")

/**
 * A declared column type split into its name and size, with its JDBC type.
 *
 * @property name type name in capitals, without the size
 * @property size first number in brackets, or null
 * @property decimalDigits second number in brackets, or null
 * @property sqlType [java.sql.Types] constant from SQLite's affinity rules
 */
internal data class DeclaredType(
	val name: String,
	val size: Int?,
	val decimalDigits: Int?,
	val sqlType: Int,
)

/**
 * Reads a declared column type, using SQLite's affinity rules in their set order.
 *
 * @param declared type as written in CREATE TABLE
 * @return the parsed type
 */
internal fun declaredType(declared: String): DeclaredType {
	val clean = declared.trim().replace(Regex("""\s+"""), " ").uppercase()
	val match = SIZED_TYPE.matchEntire(clean)
	val name = match?.groupValues?.get(1) ?: clean

	// Rules from https://sqlite.org/datatype3.html#determination_of_column_affinity
	val sqlType = when {
		"INT" in clean -> java.sql.Types.BIGINT
		"CHAR" in clean || "CLOB" in clean || "TEXT" in clean -> java.sql.Types.VARCHAR
		"BLOB" in clean || clean.isEmpty() -> java.sql.Types.BLOB
		"REAL" in clean || "FLOA" in clean || "DOUB" in clean -> java.sql.Types.DOUBLE
		else -> java.sql.Types.NUMERIC
	}
	return DeclaredType(
		name = name,
		size = match?.groupValues?.get(2)?.toIntOrNull(),
		decimalDigits = match?.groupValues?.get(3)?.toIntOrNull(),
		sqlType = sqlType,
	)
}
