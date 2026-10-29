package nz.co.simontong.d1jdbc

import java.sql.ResultSetMetaData
import java.sql.Types
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests what an IDE needs to edit rows in a result grid, against a SQLite database standing in for D1.
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
}
