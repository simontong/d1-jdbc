package nz.co.simontong.d1jdbc

import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests how the driver sends and reads values, through the standard JDBC interface.
 */
class D1ValuesTest {
	private val server = StubD1Server()

	/** Stops the stub server after each test. */
	@AfterTest
	fun stopServer() {
		server.close()
	}

	/**
	 * Parses the body of the only request the server received.
	 *
	 * @return the request body as JSON
	 */
	private fun sentJson() = Json.parseToJsonElement(server.requests.single().body)

	/** Checks that the driver sends each kind of value in the form D1 stores correctly. */
	@Test
	fun `sends each kind of value in the form D1 stores correctly`() {
		server.respondWithRows(changes = 1)

		server.connect().use { connection ->
			val statement = connection.prepareStatement("insert into t values (?, ?, ?, ?, ?, ?, ?)")
			statement.setLong(1, Long.MAX_VALUE)
			statement.setString(2, "hello")
			statement.setDouble(3, 2.5)
			statement.setBoolean(4, true)
			statement.setBytes(5, byteArrayOf(1, 2, -1))
			statement.setNull(6, java.sql.Types.VARCHAR)
			statement.setInt(7, -3)

			assertEquals(1, statement.executeUpdate())
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"insert into t values (cast(?1 as integer), ?, ?, cast(?4 as integer), ?, ?, cast(?7 as integer))",
				"params":["9223372036854775807","hello",2.5,"1",[1,2,255],null,"-3"]}
			"""),
			sentJson(),
		)
	}

	/** Checks that numbered placeholders share a value and quoted question marks are left alone. */
	@Test
	fun `numbers placeholders the way SQLite does`() {
		server.respondWithRows(columns = """["a"]""")

		server.connect().use { connection ->
			val statement = connection.prepareStatement(
				"select ?2, '?' as q, \"?\", ?1, ? /* ? */ -- ?\nwhere ?2 > 0",
			)
			statement.setString(1, "one")
			statement.setLong(2, 5)
			statement.setString(3, "three")
			statement.executeQuery()
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"select cast(?2 as integer), '?' as q, \"?\", ?1, ? /* ? */ -- ?\nwhere cast(?2 as integer) > 0",
				"params":["one","5","three"]}
			"""),
			sentJson(),
		)
	}

	/** Checks that the driver names a missing value or a parameter that doesn't exist. */
	@Test
	fun `names a missing value or a parameter that doesn't exist`() {
		server.connect().use { connection ->
			val statement = connection.prepareStatement("select ?, ?")
			statement.setString(1, "a")

			val missing = assertFailsWith<SQLException> { statement.executeQuery() }
			assertEquals("Parameter 2 has no value. Set every parameter before running the SQL.", missing.message)
			assertEquals("07001", missing.sqlState)

			val outOfRange = assertFailsWith<SQLException> { statement.setString(3, "c") }
			assertEquals("There is no parameter 3. The SQL has 2.", outOfRange.message)
			assertEquals("07009", outOfRange.sqlState)

			statement.setString(2, "b")
			statement.clearParameters()
			assertFailsWith<SQLException> { statement.executeQuery() }
		}

		assertTrue(server.requests.isEmpty())
	}

	/** Checks that a prepared batch sends each set of values in one request. */
	@Test
	fun `sends each set of values in a prepared batch`() {
		server.respond(200, """
			{"result":[
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":1}},
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":2}}
			],"errors":[],"messages":[],"success":true}
		""")

		val counts = server.connect().use { connection ->
			val statement = connection.prepareStatement("insert into t values (?, ?)")
			statement.setInt(1, 1)
			statement.setString(2, "a")
			statement.addBatch()
			statement.setDouble(1, 1.5)
			statement.setString(2, "b")
			statement.addBatch()
			statement.executeBatch()
		}

		assertEquals(listOf(1, 1), counts.toList())
		assertEquals(
			Json.parseToJsonElement("""
				{"batch":[
				{"sql":"insert into t values (cast(?1 as integer), ?)","params":["1","a"]},
				{"sql":"insert into t values (?, ?)","params":[1.5,"b"]}
				]}
			"""),
			sentJson(),
		)
	}

	/** Checks that a prepared statement refuses SQL passed to its execute methods. */
	@Test
	fun `refuses SQL passed to a prepared statement's execute methods`() {
		server.connect().use { connection ->
			val statement = connection.prepareStatement("select 1")

			for (call in listOf<() -> Unit>(
				{ statement.executeQuery("select 2") },
				{ statement.executeUpdate("delete from t") },
				{ statement.execute("select 2") },
				{ statement.addBatch("select 2") },
			)) {
				val error = assertFailsWith<SQLException> { call() }
				assertEquals("A prepared statement runs the SQL it was made with. Call the method without SQL.", error.message)
			}
		}

		assertTrue(server.requests.isEmpty())
	}
}
