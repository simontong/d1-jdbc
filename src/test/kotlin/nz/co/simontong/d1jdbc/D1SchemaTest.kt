package nz.co.simontong.d1jdbc

import java.sql.ResultSet
import java.sql.Types
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
