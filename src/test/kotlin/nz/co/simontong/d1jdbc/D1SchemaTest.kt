package nz.co.simontong.d1jdbc

import java.sql.ResultSet
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
}
