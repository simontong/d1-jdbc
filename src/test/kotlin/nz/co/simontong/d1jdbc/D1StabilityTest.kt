package nz.co.simontong.d1jdbc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests that the driver stays responsive and bounded when SQL or Cloudflare's replies are unusual.
 */
class D1StabilityTest {
	private val server = StubD1Server()

	/** Stops the stub server after each test. */
	@AfterTest
	fun stop() {
		server.close()
	}

	/** Checks that SQL with a very long text value is read without running out of stack. */
	@Test
	fun `reads SQL with very long text values`() {
		val longText = "x".repeat(1_000_000)

		assertFalse(isReadOnlySql("insert into t values ('$longText')"))
		assertTrue(isReadOnlySql("select '$longText', \"${longText}\""))
		assertFalse(isReadOnlySql("((("))
	}

	/** Checks that a reply that sends its headers then stalls times out instead of hanging. */
	@Test
	fun `times out a reply that stalls after its headers`() {
		val stalling = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		stalling.createContext("/") { exchange ->
			exchange.sendResponseHeaders(200, 1_000)
			exchange.responseBody.write("{\"success\":".toByteArray())
			exchange.responseBody.flush()
			Thread.sleep(10_000)
			exchange.close()
		}
		stalling.start()
		try {
			val client = D1Client(
				accountId = "acc123",
				apiToken = "secret-token",
				baseUri = URI.create("http://127.0.0.1:${stalling.address.port}/client/v4"),
				requestTimeout = Duration.ofMillis(500),
			)
			val started = System.nanoTime()

			assertFailsWith<SQLTimeoutException> { client.execute("db456", listOf(D1Query("select 1"))) }
			assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
		} finally {
			stalling.stop(0)
		}
	}

	/**
	 * Checks that a reply larger than the limit fails with a clear error instead of filling the
	 * memory.
	 */
	@Test
	fun `refuses a reply larger than the limit`() {
		server.respondWithRows("""["a"]""", "[[\"${"x".repeat(5_000)}\"]]")
		val request = HttpRequest.newBuilder(URI.create("${server.baseUri}/accounts/acc123/d1/database/db456/raw"))
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build()

		val reply = HttpClient.newHttpClient().sendAsync(request) { LimitedStringSubscriber(1_000) }

		val error = assertFailsWith<java.util.concurrent.ExecutionException> { reply.get() }
		assertTrue(error.cause is ReplyTooLargeException)
	}

	/** Checks that cancel from another thread stops a statement's request. */
	@Test
	fun `stops a statement from another thread`() {
		server.respondWithRows(delayMillis = 5_000)

		server.connect().use { connection ->
			val statement = connection.createStatement()
			val stopper = Thread {
				Thread.sleep(300)
				statement.cancel()
			}
			stopper.start()
			val started = System.nanoTime()

			val error = assertFailsWith<SQLException> { statement.executeQuery("select 1") }

			assertEquals("HY008", error.sqlState)
			assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
			stopper.join()
		}
	}
}
