package nz.co.simontong.d1jdbc

import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLSyntaxErrorException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

	@Test
	fun `reports a broken constraint as an integrity error`() {
		server.respond(400, """
			{"messages":[],"result":[],"success":false,"errors":[{"code":7500,
			"message":"UNIQUE constraint failed: t.id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)"}]}
		""")

		val error = assertFailsWith<SQLIntegrityConstraintViolationException> {
			client.execute(listOf(D1Statement("insert into t(id) values (1)")))
		}

		assertEquals(
			"UNIQUE constraint failed: t.id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)",
			error.message,
		)
		assertEquals("23000", error.sqlState)
		assertEquals(7500, error.errorCode)
	}

	@Test
	fun `reports bad SQL as a syntax error`() {
		server.respond(400, """
			{"messages":[],"result":[],"success":false,"errors":[{"code":7500,
			"message":"near \"selec\": syntax error at offset 0: SQLITE_ERROR"}]}
		""")

		val error = assertFailsWith<SQLSyntaxErrorException> {
			client.execute(listOf(D1Statement("selec 1")))
		}

		assertEquals("near \"selec\": syntax error at offset 0: SQLITE_ERROR", error.message)
		assertEquals("42000", error.sqlState)
	}

	@Test
	fun `reports other SQL failures as a general database error`() {
		server.respond(400, """
			{"messages":[],"result":[],"success":false,"errors":[{"code":7500,"message":"not authorized: SQLITE_AUTH"}]}
		""")

		val error = assertFailsWith<SQLException> {
			client.execute(listOf(D1Statement("pragma query_only = 1")))
		}

		assertEquals(SQLException::class, error::class)
		assertEquals("not authorized: SQLITE_AUTH", error.message)
		assertEquals("HY000", error.sqlState)
	}
}
