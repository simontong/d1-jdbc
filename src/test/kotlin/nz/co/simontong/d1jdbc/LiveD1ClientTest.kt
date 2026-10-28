package nz.co.simontong.d1jdbc

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
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
