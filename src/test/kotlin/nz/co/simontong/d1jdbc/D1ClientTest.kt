package nz.co.simontong.d1jdbc

import java.net.ServerSocket
import java.net.URI
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.sql.SQLTransientException
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Tests for the D1 client against a stub Cloudflare API.
 */
class D1ClientTest {
	private val server = StubD1Server()
	private val client = D1Client(
		accountId = "acc123",
		apiToken = "secret-token",
		baseUri = server.baseUri,
	)

	/** Stops the stub server after each test. */
	@AfterTest
	fun stopServer() {
		server.close()
	}

	/** Checks that the client runs one statement and returns its rows. */
	@Test
	fun `runs one statement and returns its rows`() {
		server.respond(
			200,
			"""
			{"result":[{"results":{"columns":["id","name"],"rows":[[1,"a"],[2,"b"]]},"success":true,
			"meta":{"changes":0,"last_row_id":7}}],"errors":[],"messages":[],"success":true}
		""",
		)

		val results = client.execute("db456", listOf(D1Query("select id, name from t where id > ?", listOf(JsonPrimitive(0)))))

		val request = server.requests.single()
		assertEquals("POST", request.method)
		assertEquals("/client/v4/accounts/acc123/d1/database/db456/raw", request.path)
		assertEquals("Bearer secret-token", request.authorization)
		assertEquals("application/json", request.contentType)
		assertEquals(
			Json.parseToJsonElement("""{"sql":"select id, name from t where id > ?","params":[0]}"""),
			Json.parseToJsonElement(request.body),
		)
		val result = results.single()
		assertEquals(listOf("id", "name"), result.columns)
		assertEquals(
			listOf(listOf(JsonPrimitive(1), JsonPrimitive("a")), listOf(JsonPrimitive(2), JsonPrimitive("b"))),
			result.rows,
		)
		assertEquals(0L, result.changes)
		assertEquals(7L, result.lastRowId)
	}

	/** Checks that the client runs several statements as one batch. */
	@Test
	fun `runs several statements as one batch`() {
		server.respond(
			200,
			"""
			{"result":[
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":5}},
			{"results":{"columns":["n"],"rows":[[5]]},"success":true,"meta":{"changes":0,"last_row_id":5}}
			],"errors":[],"messages":[],"success":true}
		""",
		)

		val results = client.execute(
			"db456",
			listOf(
				D1Query("insert into t(name) values (?)", listOf(JsonPrimitive("x"))),
				D1Query("select count(*) as n from t"),
			),
		)

		assertEquals(
			Json.parseToJsonElement(
				"""
				{"batch":[
				{"sql":"insert into t(name) values (?)","params":["x"]},
				{"sql":"select count(*) as n from t","params":[]}
				]}
			""",
			),
			Json.parseToJsonElement(server.requests.single().body),
		)
		assertEquals(listOf(1L, 0L), results.map { it.changes })
		assertEquals(listOf(listOf(JsonPrimitive(5))), results[1].rows)
	}

	/** Checks that the client reports a broken constraint as an integrity error. */
	@Test
	fun `reports a broken constraint as an integrity error`() {
		server.respond(
			400,
			"""
			{"messages":[],"result":[],"success":false,"errors":[{"code":7500,
			"message":"UNIQUE constraint failed: t.id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)"}]}
		""",
		)

		val error = assertFailsWith<SQLIntegrityConstraintViolationException> {
			client.execute("db456", listOf(D1Query("insert into t(id) values (1)")))
		}

		assertEquals(
			"UNIQUE constraint failed: t.id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)",
			error.message,
		)
		assertEquals("23000", error.sqlState)
		assertEquals(7500, error.errorCode)
	}

	/** Checks that the client reports bad SQL as a syntax error. */
	@Test
	fun `reports bad SQL as a syntax error`() {
		server.respond(
			400,
			"""
			{"messages":[],"result":[],"success":false,"errors":[{"code":7500,
			"message":"near \"selec\": syntax error at offset 0: SQLITE_ERROR"}]}
		""",
		)

		val error = assertFailsWith<SQLSyntaxErrorException> {
			client.execute("db456", listOf(D1Query("selec 1")))
		}

		assertEquals("near \"selec\": syntax error at offset 0: SQLITE_ERROR", error.message)
		assertEquals("42000", error.sqlState)
	}

	/** Checks that the client reports other SQL failures as a general database error. */
	@Test
	fun `reports other SQL failures as a general database error`() {
		server.respond(
			400,
			"""
			{"messages":[],"result":[],"success":false,"errors":[{"code":7500,"message":"not authorized: SQLITE_AUTH"}]}
		""",
		)

		val error = assertFailsWith<SQLException> {
			client.execute("db456", listOf(D1Query("pragma query_only = 1")))
		}

		assertEquals(SQLException::class, error::class)
		assertEquals("not authorized: SQLITE_AUTH", error.message)
		assertEquals("HY000", error.sqlState)
	}

	/** Checks that the client tells the user to check the token when Cloudflare rejects it. */
	@Test
	fun `tells the user to check the token when Cloudflare rejects it`() {
		server.respond(
			401,
			"""
			{"result":null,"success":false,"errors":[{"code":10000,"message":"Authentication error"}],"messages":[]}
		""",
		)

		val error = assertFailsWith<SQLInvalidAuthorizationSpecException> {
			client.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals(
			"Cloudflare rejected the API token. Check it is valid and has D1 access. (Authentication error)",
			error.message,
		)
		assertEquals("28000", error.sqlState)
		assertEquals(10000, error.errorCode)
	}

	/** Checks that the client tells the user when the database does not exist. */
	@Test
	fun `tells the user when the database does not exist`() {
		server.respond(
			404,
			"""
			{"messages":[],"result":null,"success":false,"errors":[{"code":7404,
			"message":"The database db456 could not be found"}]}
		""",
		)

		val error = assertFailsWith<SQLException> {
			client.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals(
			"Cloudflare could not find this D1 database. Check the account ID and database ID. " +
				"(The database db456 could not be found)",
			error.message,
		)
		assertEquals("3D000", error.sqlState)
		assertEquals(7404, error.errorCode)
	}

	/** Checks that the client tells the user to wait when Cloudflare rate limits the account. */
	@Test
	fun `tells the user to wait when Cloudflare rate limits the account`() {
		server.respond(429, "Too Many Requests")
		server.replyHeaders = mapOf("Retry-After" to "73")

		val error = assertFailsWith<SQLTransientException> {
			client.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals(
			"Cloudflare is limiting requests, either because your Cloudflare user made too many API calls " +
				"or because the database is overloaded. Try again in 73 seconds.",
			error.message,
		)
		assertEquals("HY000", error.sqlState)

		server.replyHeaders = emptyMap()
		val noWait = assertFailsWith<SQLTransientException> { client.execute("db456", listOf(D1Query("select 1"))) }
		assertTrue(noWait.message!!.endsWith("Wait a few minutes and try again."))
	}

	/** Checks that the client reports a Cloudflare outage as a temporary connection error. */
	@Test
	fun `reports a Cloudflare outage as a temporary connection error`() {
		server.respond(502, "<html>Bad gateway</html>")

		val error = assertFailsWith<SQLTransientConnectionException> {
			client.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals("Cloudflare's API failed with HTTP 502. Try again shortly.", error.message)
		assertEquals("08S01", error.sqlState)
	}

	/** Checks that the client reports a reply it cannot read as a connection error. */
	@Test
	fun `reports a reply it cannot read as a connection error`() {
		server.respond(200, "<html>not json</html>")

		val error = assertFailsWith<SQLNonTransientConnectionException> {
			client.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals("Cloudflare sent a reply the driver could not read (HTTP 200).", error.message)
		assertEquals("08000", error.sqlState)
	}

	/** Checks that the client gives up when Cloudflare takes too long. */
	@Test
	fun `gives up when Cloudflare takes too long`() {
		server.respond(200, "{}", delayMillis = 2_000)
		val impatientClient = D1Client(
			accountId = "acc123",
			apiToken = "secret-token",
			baseUri = server.baseUri,
			requestTimeout = Duration.ofMillis(200),
		)

		val error = assertFailsWith<SQLTimeoutException> {
			impatientClient.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals(
			"Cloudflare did not reply within 0.2 seconds. A statement that changes data may or may not have been saved.",
			error.message,
		)
		assertEquals("HYT00", error.sqlState)
	}

	/** Checks that the client reports an unreachable API as a temporary connection error. */
	@Test
	fun `reports an unreachable API as a temporary connection error`() {
		val closedPort = ServerSocket(0).use { it.localPort }
		val offlineClient = D1Client(
			accountId = "acc123",
			apiToken = "secret-token",
			baseUri = URI.create("http://127.0.0.1:$closedPort/client/v4"),
		)

		val error = assertFailsWith<SQLTransientConnectionException> {
			offlineClient.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals("Could not reach Cloudflare's API. Check your network connection.", error.message)
		assertEquals("08001", error.sqlState)
	}

	/** Checks that a read is tried again when Cloudflare's servers fail for a moment. */
	@Test
	fun `tries a read again after a Cloudflare server error`() {
		var calls = 0
		server.respondWith {
			calls++
			if (calls < 3) {
				500 to ""
			} else {
				200 to """{"result":[{"results":{"columns":["a"],"rows":[[1]]},"success":true,""" +
					""""meta":{"changes":0,"last_row_id":0}}],"errors":[],"messages":[],"success":true}"""
			}
		}

		val result = client.execute("db456", listOf(D1Query("select 1 as a")), retry = true).single()

		assertEquals(listOf("a"), result.columns)
		assertEquals(3, server.requests.size)
	}

	/** Checks that a write isn't sent again after a server error, as D1 may have saved it. */
	@Test
	fun `doesn't send a write again after a server error`() {
		server.respond(500, "")

		assertFailsWith<SQLTransientConnectionException> {
			client.execute("db456", listOf(D1Query("insert into t values (1)")))
		}
		assertEquals(1, server.requests.size)
	}

	/**
	 * Checks that the database list is asked for again when Cloudflare's servers fail for a moment.
	 */
	@Test
	fun `tries the database list again after a server error`() {
		server.databaseListFailures = 2

		assertEquals(listOf(D1DatabaseInfo("db456", "test-db", "acc123")), client.listDatabases())
		assertEquals(3, server.databaseListRequests.size)
	}

	/** Checks that a connection dropped mid-request is reported as a lost connection. */
	@Test
	fun `reports a dropped connection as a lost connection`() {
		server.dropConnections = true

		val error = assertFailsWith<SQLTransientConnectionException> {
			client.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals(
			"Lost the connection to Cloudflare's API before it replied. Check your network connection and try again. " +
				"A statement that changes data may or may not have been saved.",
			error.message,
		)
		assertEquals("08S01", error.sqlState)
	}

	/**
	 * Checks that stopping the thread while waiting gives an SQL error and keeps the stop request.
	 */
	@Test
	fun `reports a stopped request and keeps the thread's stop flag`() {
		server.respond(200, "{}", delayMillis = 5_000)
		var error: Throwable? = null
		var stillInterrupted = false
		val worker = Thread {
			error = runCatching { client.execute("db456", listOf(D1Query("select 1"))) }.exceptionOrNull()
			stillInterrupted = Thread.currentThread().isInterrupted
		}

		worker.start()
		Thread.sleep(300)
		worker.interrupt()
		worker.join(5_000)

		assertTrue(error is SQLException, "Got $error")
		assertEquals(
			"The request to Cloudflare was stopped. A statement that changes data may or may not have been saved.",
			error?.message,
		)
		assertEquals("HY008", (error as SQLException).sqlState)
		assertTrue(stillInterrupted)
	}

	/** Checks that fields Cloudflare marks optional can be missing from a reply. */
	@Test
	fun `reads a reply without its optional fields`() {
		server.respond(200, """{"success":true,"result":[{"results":{"columns":["a"]}}]}""")

		val result = client.execute("db456", listOf(D1Query("select 1"))).single()

		assertEquals(D1Result(listOf("a"), emptyList(), 0, 0), result)
	}

	/** Checks that the client reports a reply in the wrong shape as a connection error. */
	@Test
	fun `reports a reply in the wrong shape as a connection error`() {
		server.respond(200, """{"success":true,"result":[1]}""")

		val error = assertFailsWith<SQLNonTransientConnectionException> {
			client.execute("db456", listOf(D1Query("select 1")))
		}

		assertEquals("Cloudflare sent a reply the driver could not read (HTTP 200).", error.message)
	}

	/** Checks that retries after server errors stay within the request's time limit. */
	@Test
	fun `keeps retries within the time limit`() {
		server.respond(500, "", delayMillis = 150)
		val impatientClient = D1Client(
			accountId = "acc123",
			apiToken = "secret-token",
			baseUri = server.baseUri,
			requestTimeout = Duration.ofMillis(400),
		)

		val started = System.nanoTime()
		assertFailsWith<SQLException> { impatientClient.execute("db456", listOf(D1Query("select 1")), retry = true) }

		// Without one time limit, three tries and two waits would take about 1.7 seconds.
		assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(1000))
	}
}
