package nz.co.simontong.d1jdbc

import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests what an IDE needs to edit rows in a result grid, using SQLite in place of D1.
 */
class D1EditingTest {
	private val server = StubD1Server()
	private val database = SqliteD1(server)

	init {
		database.setUp(
			"create table item (id integer primary key, name text not null, price real, "
				+ "doubled real generated always as (price * 2) virtual)",
			"insert into item (id, name, price) values (1, 'tea', 4), (2, 'cake', 5.5)",
			"create table loose (x text)",
			"insert into loose values ('a')",
		)
	}

	/** Stops the stub server and closes the database after each test. */
	@AfterTest
	fun stop() {
		server.close()
		database.close()
	}

	/**
	 * Reads chosen details of every column in result metadata.
	 *
	 * @param metaData result metadata
	 * @param details reads one detail of a column by number
	 * @return one list of details per column
	 */
	private fun columns(metaData: ResultSetMetaData, details: (Int) -> List<Any?>): List<List<Any?>> {
		return (1..metaData.columnCount).map(details)
	}

	/** Checks that a single-table query names its table and uses the table's declared types. */
	@Test
	fun `names the source table of a single-table query`() {
		server.connect().use { connection ->
			val rows = connection.createStatement().executeQuery("SELECT t.* FROM \"item\" t LIMIT 501")
			val metaData = rows.metaData

			assertEquals(
				listOf(
					listOf("id", "id", "item", "main", Types.BIGINT, "INTEGER", ResultSetMetaData.columnNoNulls, true, true),
					listOf("name", "name", "item", "main", Types.VARCHAR, "TEXT", ResultSetMetaData.columnNoNulls, false, true),
					listOf("price", "price", "item", "main", Types.DOUBLE, "REAL", ResultSetMetaData.columnNullable, false, true),
					listOf("doubled", "doubled", "item", "main", Types.DOUBLE, "REAL", ResultSetMetaData.columnNullable, false, false),
				),
				columns(metaData) {
					listOf(
						metaData.getColumnLabel(it), metaData.getColumnName(it), metaData.getTableName(it),
						metaData.getSchemaName(it), metaData.getColumnType(it), metaData.getColumnTypeName(it),
						metaData.isNullable(it), metaData.isAutoIncrement(it), metaData.isWritable(it),
					)
				},
			)
			rows.next()
			assertEquals(4.0, rows.getObject("price"))
			assertEquals("java.lang.Double", metaData.getColumnClassName(3))
		}
	}

	/** Checks that named columns, aliases and the row ID each map to their table column. */
	@Test
	fun `maps named columns, aliases and the row ID`() {
		server.connect().use { connection ->
			val sql = "select i.name as label, price, i.rowid, upper(name), 1 as one from main.item as i where id > 0 order by id"
			val metaData = connection.createStatement().executeQuery(sql).metaData

			assertEquals(
				listOf(
					listOf("label", "name", "item", true),
					listOf("price", "price", "item", true),
					listOf("id", "id", "item", true),
					listOf("upper(name)", "upper(name)", "", false),
					listOf("one", "one", "", false),
				),
				columns(metaData) {
					listOf(metaData.getColumnLabel(it), metaData.getColumnName(it), metaData.getTableName(it), metaData.isWritable(it))
				},
			)
			assertEquals(true, metaData.isAutoIncrement(3))

			val loose = connection.createStatement().executeQuery("select rowid, * from loose").metaData
			assertEquals(listOf("rowid", "loose", false, true), listOf(
				loose.getColumnName(1), loose.getTableName(1), loose.isWritable(1), loose.isAutoIncrement(1),
			))
			assertEquals(listOf("x", "loose", true), listOf(loose.getColumnName(2), loose.getTableName(2), loose.isWritable(2)))
		}
	}

	/** Checks that results not made of one table's rows name no table, so they can't be edited. */
	@Test
	fun `names no table when rows don't come from one table`() {
		val queries = listOf(
			"select item.name, loose.x from item join loose",
			"select name from item, loose",
			"select count(*) as name from item group by name",
			"select name from item union select x from loose",
			"select distinct name from item",
			"select name from (select name from item)",
			"with c as (select name from item) select name from c",
			"select name from item; select x from loose",
		)

		server.connect().use { connection ->
			for (sql in queries) {
				val rows = connection.createStatement().executeQuery(sql)
				assertEquals("", rows.metaData.getTableName(1), sql)
			}
		}
	}

	/** Checks that an insert can return the new row's ID, which D1 reports as the last row ID. */
	@Test
	fun `returns the new row's ID after an insert`() {
		server.connect().use { connection ->
			val statement = connection.createStatement()
			assertEquals(1, statement.executeUpdate("insert into item (name) values ('jam')", Statement.RETURN_GENERATED_KEYS))
			val keys = statement.generatedKeys
			assertTrue(keys.next())
			assertEquals(3L, keys.getLong(1))
			assertFalse(keys.next())

			val prepared = connection.prepareStatement("insert into loose values (?)", Statement.RETURN_GENERATED_KEYS)
			prepared.setString(1, "b")
			prepared.executeUpdate()
			val preparedKeys = prepared.generatedKeys
			assertTrue(preparedKeys.next())
			assertEquals(2L, preparedKeys.getLong(1))

			statement.executeUpdate("update item set name = 'x' where id = 99", Statement.RETURN_GENERATED_KEYS)
			assertFalse(statement.generatedKeys.next())
			statement.executeUpdate("insert into item (name) values ('pie')")
			assertFalse(statement.generatedKeys.next())
		}
	}

	/** Checks that a query still reads when the schema can't load, with a warning saying why. */
	@Test
	fun `warns when the schema can't be loaded`() {
		server.connect().use { connection ->
			val rows = connection.createStatement().executeQuery("select name from item order by id")
			server.respond(500, "")

			assertEquals("", rows.metaData.getTableName(1))
			assertEquals(
				"Could not read the schema, so the columns' tables are unknown. Cloudflare's API failed with HTTP 500. "
					+ "Try again shortly.",
				rows.warnings.message,
			)
			rows.next()
			assertEquals("tea", rows.getString(1))
		}
	}
}
