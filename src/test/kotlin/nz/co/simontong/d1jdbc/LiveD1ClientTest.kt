package nz.co.simontong.d1jdbc

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Tests the D1 client against the real throwaway database named in `.env`.
 */
class LiveD1ClientTest {
	private val settings = readEnvFile(Path.of(".env"))

	/** Checks that the client runs a query on the real database. */
	@Test
	fun `runs a query on the real database`() {
		val token = settings["D1_TEST_API_TOKEN"].orEmpty()
		assumeTrue(token.isNotBlank(), "No D1_TEST_API_TOKEN in .env, so the live test is skipped")
		val client = D1Client(
			accountId = settings.getValue("D1_TEST_ACCOUNT_ID"),
			apiToken = token,
		)

		val query = D1Query("select ? as answer", listOf(JsonPrimitive("hello")))
		val result = client.execute(settings.getValue("D1_TEST_DATABASE_ID"), listOf(query)).single()

		assertEquals(listOf("answer"), result.columns)
		assertEquals(listOf(listOf(JsonPrimitive("hello"))), result.rows)
	}

	/**
	 * Checks that a URL with only the account lists the real databases and can pick one by name.
	 */
	@Test
	fun `lists and picks the real account's databases`() {
		val token = settings["D1_TEST_API_TOKEN"].orEmpty()
		assumeTrue(token.isNotBlank(), "No D1_TEST_API_TOKEN in .env, so the live test is skipped")
		val properties = Properties()
		properties["password"] = token

		DriverManager.getConnection("jdbc:d1://${settings.getValue("D1_TEST_ACCOUNT_ID")}", properties).use { connection ->
			val catalogs = connection.metaData.catalogs
			val names = generateSequence { if (catalogs.next()) catalogs.getString(1) else null }.toList()
			assertTrue("d1-jdbc-test" in names, "Databases listed: $names")

			connection.catalog = "d1-jdbc-test"
			val rows = connection.createStatement().executeQuery("select 7 as seven")
			assertTrue(rows.next())
			assertEquals(7, rows.getInt(1))
		}
	}

	/** Checks that the driver connects and queries the real database through JDBC. */
	@Test
	fun `queries the real database through JDBC`() {
		val token = settings["D1_TEST_API_TOKEN"].orEmpty()
		assumeTrue(token.isNotBlank(), "No D1_TEST_API_TOKEN in .env, so the live test is skipped")
		val properties = Properties()
		properties["password"] = token
		val url = "jdbc:d1://${settings.getValue("D1_TEST_ACCOUNT_ID")}/${settings.getValue("D1_TEST_DATABASE_ID")}"

		DriverManager.getConnection(url, properties).use { connection ->
			assertEquals(true, connection.isValid(10))
			val rows = connection.createStatement().executeQuery("select 'hello' as answer, 42 as number")
			assertEquals(true, rows.next())
			assertEquals("hello", rows.getString("answer"))
			assertEquals(42, rows.getInt("number"))
		}
	}

	/** Checks that each kind of value survives a round trip through the real database unchanged. */
	@Test
	fun `round-trips each kind of value through the real database`() {
		val token = settings["D1_TEST_API_TOKEN"].orEmpty()
		assumeTrue(token.isNotBlank(), "No D1_TEST_API_TOKEN in .env, so the live test is skipped")
		val properties = Properties()
		properties["password"] = token
		val url = "jdbc:d1://${settings.getValue("D1_TEST_ACCOUNT_ID")}/${settings.getValue("D1_TEST_DATABASE_ID")}"

		DriverManager.getConnection(url, properties).use { connection ->
			val statement = connection.createStatement()
			statement.executeUpdate("drop table if exists live_values")
			statement.executeUpdate("create table live_values (id integer primary key, big integer, loose, r real, t text, b blob, d text)")
			try {
				val insert = connection.prepareStatement("insert into live_values values (?, ?, ?, ?, ?, ?, ?)")
				insert.setInt(1, 1)
				insert.setLong(2, Long.MAX_VALUE)
				insert.setLong(3, 5)
				insert.setDouble(4, 1.0)
				insert.setString(5, "kia ora")
				insert.setBytes(6, byteArrayOf(0, 1, -1))
				insert.setObject(7, LocalDateTime.parse("2026-10-29T10:11:12.345"))
				assertEquals(1, insert.executeUpdate())

				val select = connection.prepareStatement(
					"select cast(big as text) as big_text, typeof(loose) as loose_type, loose / 2 as half, r, t, b, d, "
						+ "datetime(d) as d_parsed from live_values where id = ? and big = ?",
				)
				select.setInt(1, 1)
				select.setLong(2, Long.MAX_VALUE)
				val rows = select.executeQuery()

				assertTrue(rows.next())
				assertEquals("9223372036854775807", rows.getString("big_text"))
				assertEquals("integer", rows.getString("loose_type"))
				assertEquals(2L, rows.getObject("half"))
				assertEquals(1.0, rows.getDouble("r"))
				assertEquals("kia ora", rows.getString("t"))
				assertEquals(listOf<Byte>(0, 1, -1), rows.getBytes("b").toList())
				assertEquals(LocalDateTime.parse("2026-10-29T10:11:12.345"), rows.getObject("d", LocalDateTime::class.java))
				assertEquals("2026-10-29 10:11:12", rows.getString("d_parsed"))
				assertFalse(rows.next())
			} finally {
				statement.executeUpdate("drop table live_values")
			}
		}
	}

	/** Checks that the driver reads the real database's schema and hides D1's own tables. */
	@Test
	fun `reads the real database's schema`() {
		val token = settings["D1_TEST_API_TOKEN"].orEmpty()
		assumeTrue(token.isNotBlank(), "No D1_TEST_API_TOKEN in .env, so the live test is skipped")
		val properties = Properties()
		properties["password"] = token
		val url = "jdbc:d1://${settings.getValue("D1_TEST_ACCOUNT_ID")}/${settings.getValue("D1_TEST_DATABASE_ID")}"

		DriverManager.getConnection(url, properties).use { connection ->
			val statement = connection.createStatement()
			statement.executeUpdate("drop view if exists live_broken")
			statement.executeUpdate("drop table if exists live_child")
			statement.executeUpdate("drop table if exists live_parent")
			statement.executeUpdate("drop table if exists live_gone")
			statement.executeUpdate("create table live_gone (x)")
			statement.executeUpdate("create view live_broken as select x from live_gone")
			statement.executeUpdate("drop table live_gone")
			statement.executeUpdate("create table live_parent (id integer primary key, code text not null unique)")
			statement.executeUpdate(
				"create table live_child (id integer constraint pk_live primary key, "
					+ "pid integer constraint fk_live references live_parent(id) on delete cascade)",
			)
			try {
				val metaData = connection.metaData
				val tables = metaData.getTables(null, null, "%", null)
				val names = generateSequence { if (tables.next()) tables.getString("TABLE_NAME") else null }.toList()
				assertTrue("live_parent" in names && "live_child" in names && "live_broken" in names)
				assertTrue(names.none { it.startsWith("_cf_") || it.startsWith("sqlite_") })

				val columns = metaData.getColumns(null, null, "live_parent", null)
				assertTrue(columns.next())
				assertEquals("id", columns.getString("COLUMN_NAME"))
				assertEquals("YES", columns.getString("IS_AUTOINCREMENT"))
				assertTrue(columns.next())
				assertEquals("code", columns.getString("COLUMN_NAME"))
				assertEquals("NO", columns.getString("IS_NULLABLE"))

				val indexes = metaData.getIndexInfo(null, null, "live_parent", true, true)
				assertTrue(indexes.next())
				assertEquals("code", indexes.getString("COLUMN_NAME"))

				val primaryKey = metaData.getPrimaryKeys(null, null, "live_child")
				assertTrue(primaryKey.next())
				assertEquals("pk_live", primaryKey.getString("PK_NAME"))

				val keys = metaData.getImportedKeys(null, null, "live_child")
				assertTrue(keys.next())
				assertEquals("live_parent", keys.getString("PKTABLE_NAME"))
				assertEquals("pid", keys.getString("FKCOLUMN_NAME"))
				assertEquals("fk_live", keys.getString("FK_NAME"))
				assertFalse(keys.next())
			} finally {
				statement.executeUpdate("drop view live_broken")
				statement.executeUpdate("drop table live_child")
				statement.executeUpdate("drop table live_parent")
			}
		}
	}

	/** Checks a grid edit on the real database: read, update a row, then insert and get its ID. */
	@Test
	fun `edits rows in the real database the way an IDE grid does`() {
		val token = settings["D1_TEST_API_TOKEN"].orEmpty()
		assumeTrue(token.isNotBlank(), "No D1_TEST_API_TOKEN in .env, so the live test is skipped")
		val properties = Properties()
		properties["password"] = token
		val url = "jdbc:d1://${settings.getValue("D1_TEST_ACCOUNT_ID")}/${settings.getValue("D1_TEST_DATABASE_ID")}"

		DriverManager.getConnection(url, properties).use { connection ->
			val statement = connection.createStatement()
			statement.executeUpdate("drop table if exists live_grid")
			statement.executeUpdate("create table live_grid (id integer primary key, name text not null, price real)")
			try {
				statement.executeUpdate("insert into live_grid (name, price) values ('tea', 4), ('cake', 5.5)")

				val rows = statement.executeQuery("SELECT t.* FROM live_grid t LIMIT 501")
				assertEquals("live_grid", rows.metaData.getTableName(2))
				assertEquals(true, rows.metaData.isWritable(2))
				assertTrue(rows.next())
				assertEquals(4.0, rows.getObject("price"))

				val update = connection.prepareStatement("update live_grid set name = ? where id = ?")
				update.setString(1, "green tea")
				update.setLong(2, rows.getLong("id"))
				assertEquals(1, update.executeUpdate())

				val insert = connection.prepareStatement(
					"insert into live_grid (name, price) values (?, ?)",
					java.sql.Statement.RETURN_GENERATED_KEYS,
				)
				insert.setString(1, "pie")
				insert.setDouble(2, 6.0)
				assertEquals(1, insert.executeUpdate())
				val keys = insert.generatedKeys
				assertTrue(keys.next())
				assertEquals(3L, keys.getLong(1))
			} finally {
				statement.executeUpdate("drop table live_grid")
			}
		}
	}
}

/**
 * Reads `KEY=value` lines from an env file.
 *
 * @param path env file to read
 * @return settings by name, empty when the file is missing
 */
private fun readEnvFile(path: Path): Map<String, String> {
	if (!Files.exists(path)) {
		return emptyMap()
	}
	return Files.readAllLines(path)
		.filter { it.contains("=") && !it.trimStart().startsWith("#") }
		.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
}
