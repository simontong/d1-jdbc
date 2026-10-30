package nz.co.simontong.d1jdbc

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests a connection that reaches every D1 database in the account, using SQLite for each.
 */
class D1DatabasesTest {
	private val server = StubD1Server()
	private val database = SqliteD1(server)

	init {
		server.databases = listOf("id-shop" to "shop", "id-blog" to "blog")
		database.setUp("create table item (id integer primary key, name text)")
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

	/** Checks that every database is listed by name, with a `main` schema each. */
	@Test
	fun `lists every database by name`() {
		server.connect("jdbc:d1://acc123").use { connection ->
			assertEquals(listOf(listOf("blog"), listOf("shop")), read(connection.metaData.catalogs, "TABLE_CAT"))
			assertEquals(
				listOf(listOf("main", "blog"), listOf("main", "shop")),
				read(connection.metaData.schemas, "TABLE_SCHEM", "TABLE_CATALOG"),
			)
			assertEquals(
				listOf(listOf("main", "shop")),
				read(connection.metaData.getSchemas("shop", null), "TABLE_SCHEM", "TABLE_CATALOG"),
			)
			assertEquals("database", connection.metaData.catalogTerm)
		}
	}

	/** Checks that statements need a database chosen first, then run on the chosen one. */
	@Test
	fun `runs statements on the chosen database`() {
		server.connect("jdbc:d1://acc123").use { connection ->
			assertNull(connection.catalog)
			val error = assertFailsWith<SQLException> { connection.createStatement().executeQuery("select 1") }
			assertEquals("3D000", error.sqlState)
			assertEquals(
				"This connection can reach several D1 databases, so choose one first. Pick it in the IDE's "
					+ "database switcher, or add its ID to the URL.",
				error.message,
			)

			connection.catalog = "shop"
			connection.createStatement().executeQuery("select 1")
			assertEquals("shop", connection.catalog)
			assertEquals("/client/v4/accounts/acc123/d1/database/id-shop/raw", server.requests.last().path)

			connection.catalog = "blog"
			connection.createStatement().executeQuery("select 1")
			assertEquals("/client/v4/accounts/acc123/d1/database/id-blog/raw", server.requests.last().path)
		}
	}

	/** Checks that a database ID in the URL picks the database, while still listing the rest. */
	@Test
	fun `starts on the database in the URL`() {
		server.connect("jdbc:d1://acc123/id-blog").use { connection ->
			assertEquals("blog", connection.catalog)
			assertEquals(listOf(listOf("blog"), listOf("shop")), read(connection.metaData.catalogs, "TABLE_CAT"))
			assertEquals(
				listOf(listOf("blog", "item")),
				read(connection.metaData.getTables(null, null, "%", null), "TABLE_CAT", "TABLE_NAME"),
			)
		}
	}

	/** Checks that tables of a named database come from that database only. */
	@Test
	fun `reads the tables of the database asked about`() {
		server.connect("jdbc:d1://acc123").use { connection ->
			val metaData = connection.metaData
			assertEquals(listOf(listOf("shop", "item")), read(metaData.getTables("shop", null, "%", null), "TABLE_CAT", "TABLE_NAME"))
			assertTrue(server.requests.all { it.path.endsWith("/id-shop/raw") })
			assertEquals(listOf(listOf("shop", "id")), read(metaData.getPrimaryKeys("shop", null, "item"), "TABLE_CAT", "COLUMN_NAME"))
			assertEquals(emptyList(), read(metaData.getTables("nope", null, "%", null), "TABLE_NAME"))

			// With no database chosen and none named, every database's tables are listed.
			assertEquals(
				listOf(listOf("blog", "item"), listOf("shop", "item")),
				read(metaData.getTables(null, null, "%", null), "TABLE_CAT", "TABLE_NAME"),
			)
		}
	}

	/** Checks that choosing a database that doesn't exist fails and keeps the current choice. */
	@Test
	fun `refuses a database that doesn't exist`() {
		server.connect("jdbc:d1://acc123/id-shop").use { connection ->
			val error = assertFailsWith<SQLException> { connection.catalog = "nope" }

			assertEquals("There's no D1 database called \"nope\" in this account.", error.message)
			assertEquals("3D000", error.sqlState)
			assertEquals("shop", connection.catalog)
		}
	}

	/** Checks that an account with one database uses it without being told. */
	@Test
	fun `uses the only database when there's one`() {
		server.databases = listOf("id-only" to "only")

		server.connect("jdbc:d1://acc123").use { connection ->
			connection.createStatement().executeQuery("select 1")

			assertEquals("/client/v4/accounts/acc123/d1/database/id-only/raw", server.requests.last().path)
			assertEquals("only", connection.catalog)
		}
	}

	/** Checks that the database list is read page by page until every database is found. */
	@Test
	fun `reads every page of the database list`() {
		server.databases = (1..5).map { "id-$it" to "db$it" }
		server.databasesPerPage = 2

		server.connect("jdbc:d1://acc123").use { connection ->
			assertEquals(5, read(connection.metaData.catalogs, "TABLE_CAT").size)
		}
		assertEquals(
			listOf("page=1&per_page=10000", "page=2&per_page=10000", "page=3&per_page=10000"),
			server.databaseListRequests,
		)
	}

	/** Checks that a connection with no database chosen counts as working when the list loads. */
	@Test
	fun `checks the token when no database is chosen`() {
		server.connect("jdbc:d1://acc123").use { connection ->
			assertTrue(connection.isValid(5))
			assertTrue(server.requests.isEmpty())
			assertEquals(1, server.databaseListRequests.size)
		}
	}
}
