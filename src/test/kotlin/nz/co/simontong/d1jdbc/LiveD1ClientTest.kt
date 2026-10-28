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
			databaseId = settings.getValue("D1_TEST_DATABASE_ID"),
			apiToken = token,
		)

		val result = client.execute(listOf(D1Query("select ? as answer", listOf(JsonPrimitive("hello"))))).single()

		assertEquals(listOf("answer"), result.columns)
		assertEquals(listOf(listOf(JsonPrimitive("hello"))), result.rows)
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
