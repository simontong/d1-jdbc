package nz.co.simontong.d1jdbc

import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Types
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests schema browsing through JDBC metadata, against a SQLite database standing in for D1.
 */
class D1SchemaTest {
	private val server = StubD1Server()
	private val database = SqliteD1(server)

	init {
		database.setUp(
			"create table _cf_KV (key text primary key, value blob) without rowid",
			"create table parent (id integer primary key autoincrement, code text not null unique, "
				+ "note varchar(20) default 'x', g integer generated always as (id * 2) virtual)",
			"create table child (a integer, b integer, pid integer references parent(id) on delete cascade, "
				+ "primary key (a, b), foreign key (a) references parent(id))",
			"create index child_b on child(b desc) where b > 0",
			"create view v as select id from parent",
			"create temp table scratch (x)",
		)
	}

	// Metadata numbers read back as Long, as getObject returns Long for whole numbers.
	private val BIGINT = Types.BIGINT.toLong()
	private val VARCHAR = Types.VARCHAR.toLong()
	private val BLOB = Types.BLOB.toLong()
	private val DOUBLE = Types.DOUBLE.toLong()
	private val NUMERIC = Types.NUMERIC.toLong()
	private val OTHER_INDEX = DatabaseMetaData.tableIndexOther.toLong()
	private val NO_ACTION = DatabaseMetaData.importedKeyNoAction.toLong()
	private val CASCADE = DatabaseMetaData.importedKeyCascade.toLong()
	private val SET_NULL = DatabaseMetaData.importedKeySetNull.toLong()
	private val NOT_PSEUDO = DatabaseMetaData.bestRowNotPseudo.toLong()
	private val PSEUDO = DatabaseMetaData.bestRowPseudo.toLong()

	/** Stops the stub server and closes the database after each test. */
	@AfterTest
	fun stop() {
		server.close()
		database.close()
	}

	/**
	 * Reads chosen columns from every row of a result set.
	 *
	 * @param rows result set to read
	 * @param columns column names to read
	 * @return one list of values per row
	 */
	private fun read(rows: ResultSet, vararg columns: String): List<List<Any?>> {
		return generateSequence { if (rows.next()) columns.map { rows.getObject(it) } else null }.toList()
	}

	/** Checks that the driver lists tables and views, hiding D1's and SQLite's own tables. */
	@Test
	fun `lists tables and views without system tables`() {
		server.connect().use { connection ->
			val tables = connection.metaData.getTables(null, null, "%", null)
			assertEquals(10, tables.metaData.columnCount)
			assertEquals("TABLE_NAME", tables.metaData.getColumnLabel(3))

			assertEquals(
				listOf(
					listOf(null, "main", "child", "TABLE"),
					listOf(null, "main", "parent", "TABLE"),
					listOf(null, "main", "v", "VIEW"),
				),
				read(tables, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE"),
			)
			assertEquals(
				listOf(listOf("v")),
				read(connection.metaData.getTables(null, "main", "_", arrayOf("VIEW")), "TABLE_NAME"),
			)
			assertEquals(
				listOf(listOf("parent")),
				read(connection.metaData.getTables("", "%", "PAR%", arrayOf("TABLE")), "TABLE_NAME"),
			)
			assertEquals(emptyList(), read(connection.metaData.getTables("other", null, "%", null), "TABLE_NAME"))
		}
	}

	/** Checks that the driver describes each column, with types from SQLite's affinity rules. */
	@Test
	fun `describes each column`() {
		server.connect().use { connection ->
			val columns = connection.metaData.getColumns(null, "main", "parent", "%")

			assertEquals(24, columns.metaData.columnCount)
			assertEquals(
				listOf(
					listOf("parent", "id", BIGINT, "INTEGER", null, null, 0L, null, 1L, "NO", "YES", "NO"),
					listOf("parent", "code", VARCHAR, "TEXT", null, null, 0L, null, 2L, "NO", "NO", "NO"),
					listOf("parent", "note", VARCHAR, "VARCHAR", 20L, null, 1L, "'x'", 3L, "YES", "NO", "NO"),
					listOf("parent", "g", BIGINT, "INTEGER", null, null, 1L, null, 4L, "YES", "NO", "YES"),
				),
				read(
					columns, "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "DECIMAL_DIGITS",
					"NULLABLE", "COLUMN_DEF", "ORDINAL_POSITION", "IS_NULLABLE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN",
				),
			)
			assertEquals(
				listOf(listOf("parent", "id", 1L), listOf("v", "id", 1L)),
				read(connection.metaData.getColumns(null, null, "%", "_d"), "TABLE_NAME", "COLUMN_NAME", "ORDINAL_POSITION"),
			)
		}
	}

	/** Checks that the driver lists primary key columns in key order. */
	@Test
	fun `lists primary key columns`() {
		server.connect().use { connection ->
			val keys = connection.metaData.getPrimaryKeys(null, null, "child")

			assertEquals(6, keys.metaData.columnCount)
			assertEquals(
				listOf(listOf("child", "a", 1L), listOf("child", "b", 2L)),
				read(keys, "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ"),
			)
			assertEquals(
				listOf(listOf("id", 1L)),
				read(connection.metaData.getPrimaryKeys(null, null, "parent"), "COLUMN_NAME", "KEY_SEQ"),
			)
			assertEquals(emptyList(), read(connection.metaData.getPrimaryKeys(null, null, "v"), "COLUMN_NAME"))
		}
	}

	/** Checks that the driver lists each index's columns, with unique ones first. */
	@Test
	fun `lists indexes and their columns`() {
		server.connect().use { connection ->
			val indexes = connection.metaData.getIndexInfo(null, null, "child", false, true)

			assertEquals(13, indexes.metaData.columnCount)
			assertEquals(
				listOf(
					listOf("child", false, "sqlite_autoindex_child_1", OTHER_INDEX, 1L, "a", "A"),
					listOf("child", false, "sqlite_autoindex_child_1", OTHER_INDEX, 2L, "b", "A"),
					listOf("child", true, "child_b", OTHER_INDEX, 1L, "b", "D"),
				),
				read(indexes, "TABLE_NAME", "NON_UNIQUE", "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC"),
			)
			assertEquals(
				listOf(listOf("sqlite_autoindex_child_1"), listOf("sqlite_autoindex_child_1")),
				read(connection.metaData.getIndexInfo(null, null, "child", true, true), "INDEX_NAME"),
			)
		}
	}

	/** Checks that foreign keys list from both ends, filling in implied parent columns. */
	@Test
	fun `lists foreign keys from both ends`() {
		database.setUp("create table remark (pid integer references parent on update set null)")
		val columns = arrayOf(
			"PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE",
		)

		server.connect().use { connection ->
			val imported = connection.metaData.getImportedKeys(null, null, "child")

			assertEquals(14, imported.metaData.columnCount)
			assertEquals(
				listOf(
					listOf("parent", "id", "child", "a", 1L, NO_ACTION, NO_ACTION),
					listOf("parent", "id", "child", "pid", 1L, NO_ACTION, CASCADE),
				),
				read(imported, *columns),
			)
			assertEquals(
				listOf(
					listOf("parent", "id", "child", "a", 1L, NO_ACTION, NO_ACTION),
					listOf("parent", "id", "child", "pid", 1L, NO_ACTION, CASCADE),
					listOf("parent", "id", "remark", "pid", 1L, SET_NULL, NO_ACTION),
				),
				read(connection.metaData.getExportedKeys(null, null, "parent"), *columns),
			)
			assertEquals(
				listOf(listOf("parent", "id", "remark", "pid", 1L, SET_NULL, NO_ACTION)),
				read(connection.metaData.getCrossReference(null, null, "parent", null, null, "remark"), *columns),
			)
		}
	}

	/** Checks that browsing reuses one schema load, and a schema change makes it load again. */
	@Test
	fun `reuses the loaded schema until something changes it`() {
		server.connect().use { connection ->
			val metaData = connection.metaData
			metaData.getTables(null, null, "%", null)
			for (table in listOf("parent", "child", "v")) {
				metaData.getColumns(null, null, table, null)
				metaData.getPrimaryKeys(null, null, table)
				metaData.getIndexInfo(null, null, table, false, true)
				metaData.getImportedKeys(null, null, table)
			}
			assertEquals(2, server.requests.size)

			connection.createStatement().execute("select 1")
			metaData.getTables(null, null, "%", null)
			assertEquals(3, server.requests.size)

			connection.createStatement().execute("create table added (x)")
			assertEquals(
				listOf(listOf("added")),
				read(metaData.getTables(null, null, "added", null), "TABLE_NAME"),
			)
			assertEquals(6, server.requests.size)
		}

		server.connect(extra = mapOf("schemaCacheSeconds" to "0")).use { connection ->
			connection.metaData.getTables(null, null, "%", null)
			connection.metaData.getTables(null, null, "%", null)
		}
		assertEquals(10, server.requests.size)
	}

	/** Checks that the driver lists SQLite's five storage types, sorted by JDBC type. */
	@Test
	fun `lists SQLite's storage types`() {
		server.connect().use { connection ->
			val types = connection.metaData.typeInfo

			assertEquals(18, types.metaData.columnCount)
			assertEquals(
				listOf(
					listOf("INTEGER", BIGINT, null, null, true),
					listOf("NUMERIC", NUMERIC, null, null, false),
					listOf("REAL", DOUBLE, null, null, false),
					listOf("TEXT", VARCHAR, "'", "'", false),
					listOf("BLOB", BLOB, "X'", "'", false),
				),
				read(types, "TYPE_NAME", "DATA_TYPE", "LITERAL_PREFIX", "LITERAL_SUFFIX", "AUTO_INCREMENT"),
			)
		}
		assertTrue(server.requests.isEmpty())
	}

	/** Checks that the best row identifier is the primary key, or the row ID when there is none. */
	@Test
	fun `names the columns that identify a row`() {
		database.setUp("create table loose (x text)")

		server.connect().use { connection ->
			val metaData = connection.metaData
			val scope = DatabaseMetaData.bestRowSession

			assertEquals(
				listOf(listOf("a", BIGINT, NOT_PSEUDO), listOf("b", BIGINT, NOT_PSEUDO)),
				read(metaData.getBestRowIdentifier(null, null, "child", scope, true), "COLUMN_NAME", "DATA_TYPE", "PSEUDO_COLUMN"),
			)
			assertEquals(
				listOf(listOf("rowid", BIGINT, PSEUDO)),
				read(metaData.getBestRowIdentifier(null, null, "loose", scope, true), "COLUMN_NAME", "DATA_TYPE", "PSEUDO_COLUMN"),
			)
			assertEquals(emptyList(), read(metaData.getBestRowIdentifier(null, null, "v", scope, true), "COLUMN_NAME"))
		}
	}

	/** Checks that SQLite's affinity rules decide each declared type's JDBC type. */
	@Test
	fun `maps declared types by SQLite's affinity rules`() {
		database.setUp("create table types (a bigint, b nvarchar(10), c clob, d blob, e, f double precision, g float, "
			+ "h decimal(10,2), i boolean, j datetime, k charint, l floating point)")

		server.connect().use { connection ->
			val columns = connection.metaData.getColumns(null, null, "types", null)

			assertEquals(
				listOf(
					listOf("a", BIGINT, "BIGINT", null, null),
					listOf("b", VARCHAR, "NVARCHAR", 10L, null),
					listOf("c", VARCHAR, "CLOB", null, null),
					listOf("d", BLOB, "BLOB", null, null),
					listOf("e", BLOB, "", null, null),
					listOf("f", DOUBLE, "DOUBLE PRECISION", null, null),
					listOf("g", DOUBLE, "FLOAT", null, null),
					listOf("h", NUMERIC, "DECIMAL", 10L, 2L),
					listOf("i", NUMERIC, "BOOLEAN", null, null),
					listOf("j", NUMERIC, "DATETIME", null, null),
					listOf("k", BIGINT, "CHARINT", null, null),
					listOf("l", BIGINT, "FLOATING POINT", null, null),
				),
				read(columns, "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "DECIMAL_DIGITS"),
			)
		}
	}
}
