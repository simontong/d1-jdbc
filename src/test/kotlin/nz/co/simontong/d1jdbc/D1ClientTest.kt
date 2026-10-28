package nz.co.simontong.d1jdbc

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
		databaseId = "db456",
		apiToken = "secret-token",
		baseUri = server.baseUri,
	)

	/** Stops the stub server after each test. */
	@AfterTest
	fun stopServer() {
		server.close()
	}

	@Test
	fun `runs one statement and returns its rows`() {
		server.respond(200, """
			{"result":[{"results":{"columns":["id","name"],"rows":[[1,"a"],[2,"b"]]},"success":true,
			"meta":{"changes":0,"last_row_id":7}}],"errors":[],"messages":[],"success":true}
		""")

		val results = client.execute(listOf(D1Statement("select id, name from t where id > ?", listOf(JsonPrimitive(0)))))

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

	@Test
	fun `runs several statements as one batch`() {
		server.respond(200, """
			{"result":[
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":5}},
			{"results":{"columns":["n"],"rows":[[5]]},"success":true,"meta":{"changes":0,"last_row_id":5}}
			],"errors":[],"messages":[],"success":true}
		""")

		val results = client.execute(listOf(
			D1Statement("insert into t(name) values (?)", listOf(JsonPrimitive("x"))),
			D1Statement("select count(*) as n from t"),
		))

		assertEquals(
			Json.parseToJsonElement("""
				{"batch":[
				{"sql":"insert into t(name) values (?)","params":["x"]},
				{"sql":"select count(*) as n from t","params":[]}
				]}
			"""),
			Json.parseToJsonElement(server.requests.single().body),
		)
		assertEquals(listOf(1L, 0L), results.map { it.changes })
		assertEquals(listOf(listOf(JsonPrimitive(5))), results[1].rows)
	}
}
