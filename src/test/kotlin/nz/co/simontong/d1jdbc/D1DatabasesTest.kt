package nz.co.simontong.d1jdbc

import java.sql.ResultSet
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

	/** Checks that statements run on the first database by name until another is chosen. */
	@Test
	fun `runs statements on the chosen database`() {
		server.connect("jdbc:d1://acc123").use { connection ->
			assertEquals("blog", connection.catalog)
			connection.createStatement().executeQuery("select 1")
			assertEquals("/client/v4/accounts/acc123/d1/database/id-blog/raw", server.requests.last().path)

			connection.catalog = "shop"
			connection.createStatement().executeQuery("select 1")
			assertEquals("shop", connection.catalog)
			assertEquals("/client/v4/accounts/acc123/d1/database/id-shop/raw", server.requests.last().path)

			connection.catalog = "blog"
			connection.createStatement().executeQuery("select 1")
			assertEquals("/client/v4/accounts/acc123/d1/database/id-blog/raw", server.requests.last().path)
		}
	}

	/**
	 * Checks that a database name in the URL picks that database, and an unknown or shared name
	 * says why.
	 */
	@Test
	fun `starts on the database named in the URL`() {
		server.connect("jdbc:d1://acc123/shop").use { connection ->
			assertEquals("shop", connection.catalog)
		}

		val unknown = assertFailsWith<SQLException> { server.connect("jdbc:d1://acc123/nope").catalog }
		assertEquals("There's no D1 database called \"nope\" that this connection can reach.", unknown.message)

		server.databases = listOf("id-a" to "app", "id-b" to "app")
		val shared = assertFailsWith<SQLException> { server.connect("jdbc:d1://acc123/app").catalog }
		assertEquals("More than one D1 database is called \"app\". Put its ID in the URL instead.", shared.message)
	}

	/** Checks that a database ID in the URL picks the database, while still listing the rest. */
	@Test
	fun `starts on the database in the URL`() {
		server.connect("jdbc:d1://acc123/id-blog").use { connection ->
			assertEquals("blog", connection.catalog)
			assertEquals(listOf(listOf("blog"), listOf("shop")), read(connection.metaData.catalogs, "TABLE_CAT"))
			assertEquals(
				listOf(listOf("blog", "item")),
				read(connection.metaData.getTables("blog", null, "%", null), "TABLE_CAT", "TABLE_NAME"),
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

			// A null catalog means every database.
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

			assertEquals("There's no D1 database called \"nope\" that this connection can reach.", error.message)
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

	/** Checks that two databases with the same name can both be chosen, each named with its ID. */
	@Test
	fun `tells apart databases that share a name`() {
		server.databases = listOf("id-a" to "app", "id-b" to "app", "id-c" to "other")

		server.connect("jdbc:d1://acc123").use { connection ->
			assertEquals(
				listOf(listOf("app (id-a)"), listOf("app (id-b)"), listOf("other")),
				read(connection.metaData.catalogs, "TABLE_CAT"),
			)
			connection.catalog = "app (id-b)"
			connection.createStatement().executeQuery("select 1")
			assertEquals("/client/v4/accounts/acc123/d1/database/id-b/raw", server.requests.last().path)
		}
	}

	/**
	 * Checks that a null catalog searches every database, and an empty one matches none, as JDBC
	 * says.
	 */
	@Test
	fun `searches every database for a null catalog and none for an empty one`() {
		server.connect("jdbc:d1://acc123/id-shop").use { connection ->
			assertEquals(
				listOf(listOf("blog", "item"), listOf("shop", "item")),
				read(connection.metaData.getTables(null, null, "%", null), "TABLE_CAT", "TABLE_NAME"),
			)
			assertEquals(emptyList(), read(connection.metaData.getTables("", null, "%", null), "TABLE_NAME"))
			assertEquals(emptyList(), read(connection.metaData.getSchemas("", null), "TABLE_SCHEM"))
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
