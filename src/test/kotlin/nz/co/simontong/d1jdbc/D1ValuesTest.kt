package nz.co.simontong.d1jdbc

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
