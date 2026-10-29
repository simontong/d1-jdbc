package nz.co.simontong.d1jdbc

import java.time.Duration
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
 * @property parentTable table the key refers to
 * @property columns child and parent column pairs in order, where the parent is null for its primary key
 * @property onUpdate ON UPDATE action, like `CASCADE`
 * @property onDelete ON DELETE action, like `CASCADE`
 */
internal data class SchemaForeignKey(
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
 * @property indexes indexes on the table
 * @property foreignKeys foreign keys on the table
 */
internal data class SchemaTable(
	val name: String,
	val type: String,
	val withoutRowId: Boolean,
	val columns: List<SchemaColumn>,
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
 * @param result one statement's result
 * @return one map per row
 */
private fun records(result: D1Result): List<Map<String, JsonElement>> {
	return result.rows.map { row -> result.columns.zip(row).toMap() }
}

/**
 * Loads the whole schema in two requests, as Cloudflare limits how many API calls an account makes.
 *
 * @param client client for the database
 * @return the schema
 * @throws java.sql.SQLException when D1 reports an error
 */
internal fun loadSchema(client: D1Client): D1Schema {
	val (tableList, indexList) = client.execute(listOf(
		D1Query("pragma table_list"),
		D1Query("select name, tbl_name from sqlite_schema where type = 'index'"),
	))
	val found = records(tableList).filter { row ->
		val name = row.getValue("name").text()!!
		row.getValue("schema").text() == "main" && row.getValue("type").text() in setOf("table", "view") && !isSystemTable(name)
	}
	val tableNames = found.map { it.getValue("name").text()!! }.toSet()
	val indexNames = records(indexList)
		.filter { it.getValue("tbl_name").text() in tableNames }
		.map { it.getValue("name").text()!! }

	// Ask for every table's details, then every index's columns, in as few requests as possible.
	val queries = mutableListOf<D1Query>()
	for (row in found) {
		val name = quoted(row.getValue("name").text()!!)
		queries += D1Query("pragma table_xinfo($name)")
		queries += D1Query("pragma index_list($name)")
		queries += D1Query("pragma foreign_key_list($name)")
	}
	indexNames.forEach { queries += D1Query("pragma index_xinfo(${quoted(it)})") }
	val results = queries.chunked(STATEMENTS_PER_REQUEST).flatMap { client.execute(it) }

	val indexColumns = indexNames.indices.associate { index ->
		val columns = records(results[found.size * 3 + index])
			.filter { it.getValue("key").number() == 1 }
			.map { SchemaIndexColumn(it.getValue("name").text(), it.getValue("desc").number() == 1) }
		indexNames[index] to columns
	}
	val tables = found.mapIndexed { position, row ->
		val (columns, indexes, foreignKeys) = results.subList(position * 3, position * 3 + 3)
		SchemaTable(
			name = row.getValue("name").text()!!,
			type = if (row.getValue("type").text() == "view") "VIEW" else "TABLE",
			withoutRowId = row.getValue("wr").number() == 1,
			columns = readColumns(columns),
			indexes = records(indexes).map { index ->
				val name = index.getValue("name").text()!!
				SchemaIndex(name, index.getValue("unique").number() == 1, indexColumns[name].orEmpty())
			},
			foreignKeys = readForeignKeys(foreignKeys),
		)
	}
	return D1Schema(tables.sortedBy { it.name.lowercase() })
}

/**
 * Reads the visible columns from `pragma table_xinfo`.
 *
 * @param result the PRAGMA's result
 * @return columns in order
 */
private fun readColumns(result: D1Result): List<SchemaColumn> {
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
 * @param result the PRAGMA's result
 * @return foreign keys in order
 */
private fun readForeignKeys(result: D1Result): List<SchemaForeignKey> {
	return records(result)
		.groupBy { it.getValue("id").number() }
		.toSortedMap()
		.values
		.map { rows ->
			val ordered = rows.sortedBy { it.getValue("seq").number() }
			val first = ordered.first()
			SchemaForeignKey(
				parentTable = first.getValue("table").text()!!,
				columns = ordered.map { it.getValue("from").text()!! to it.getValue("to").text() },
				onUpdate = first.getValue("on_update").text()!!,
				onDelete = first.getValue("on_delete").text()!!,
			)
		}
}

/**
 * Keeps the schema for a short time, so a tool reading it table by table makes only two requests.
 *
 * @param client client for the database
 * @param maxAge how long a loaded schema is reused
 * @param clock returns the current time in nanoseconds
 */
internal class SchemaCache(
	private val client: D1Client,
	private val maxAge: Duration,
	private val clock: () -> Long = System::nanoTime,
) {
	private var schema: D1Schema? = null
	private var loadedAt = 0L

	/**
	 * Returns the schema, loading it when there's none or it's too old.
	 *
	 * @return the schema
	 * @throws java.sql.SQLException when D1 reports an error
	 */
	fun get(): D1Schema {
		val cached = schema
		if (cached != null && clock() - loadedAt < maxAge.toNanos()) {
			return cached
		}
		val loaded = loadSchema(client)
		schema = loaded
		loadedAt = clock()
		return loaded
	}

	/** Forgets the schema, so the next call loads it again. */
	fun clear() {
		schema = null
	}
}
