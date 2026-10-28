package nz.co.simontong.d1jdbc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLSyntaxErrorException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One SQL statement and its bound values, as sent to D1.
 *
 * @property sql SQL text
 * @property params bound values in order
 */
data class D1Statement(
	val sql: String,
	val params: List<JsonElement> = emptyList(),
)

/**
 * The outcome of one statement run on D1.
 *
 * @property columns column names in order
 * @property rows row values, one list per row
 * @property changes rows changed by the statement
 * @property lastRowId row ID of the last insert on the database
 */
data class D1Result(
	val columns: List<String>,
	val rows: List<List<JsonElement>>,
	val changes: Long,
	val lastRowId: Long,
)

/**
 * Sends SQL to one D1 database through the Cloudflare REST API.
 *
 * @param accountId Cloudflare account ID
 * @param databaseId D1 database ID
 * @param apiToken API token with D1 access
 * @param baseUri Cloudflare API base address
 */
class D1Client(
	private val accountId: String,
	private val databaseId: String,
	private val apiToken: String,
	private val baseUri: URI = URI.create("https://api.cloudflare.com/client/v4"),
) {
	private val httpClient = HttpClient.newHttpClient()

	/**
	 * Runs statements on D1 and returns one result per statement.
	 *
	 * @param statements statements to run in order
	 * @returns results in the same order
	 */
	fun execute(statements: List<D1Statement>): List<D1Result> {
		val request = HttpRequest.newBuilder(URI.create("$baseUri/accounts/$accountId/d1/database/$databaseId/raw"))
			.header("Authorization", "Bearer $apiToken")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(requestJson(statements).toString()))
			.build()
		val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

		val body = Json.parseToJsonElement(response.body()).jsonObject
		if (!body.getValue("success").jsonPrimitive.boolean) {
			throw errorFor(response.statusCode(), body.getValue("errors").jsonArray.first().jsonObject)
		}
		return body.getValue("result").jsonArray.map { parseResult(it.jsonObject) }
	}

	/**
	 * Builds the request body, using `batch` when there is more than one statement.
	 *
	 * @param statements statements to send
	 * @returns JSON request body
	 */
	private fun requestJson(statements: List<D1Statement>): JsonObject {
		if (statements.size == 1) {
			return statementJson(statements.single())
		}
		return JsonObject(mapOf("batch" to JsonArray(statements.map { statementJson(it) })))
	}

	/**
	 * Builds the JSON body for one statement.
	 *
	 * @param statement statement to encode
	 * @returns JSON object with `sql` and `params`
	 */
	private fun statementJson(statement: D1Statement): JsonObject {
		return JsonObject(mapOf(
			"sql" to JsonPrimitive(statement.sql),
			"params" to JsonArray(statement.params),
		))
	}

	/**
	 * Reads one statement result from the `/raw` reply.
	 *
	 * @param result one entry of the reply's `result` array
	 * @returns the parsed result
	 */
	private fun parseResult(result: JsonObject): D1Result {
		val results = result.getValue("results").jsonObject
		val meta = result.getValue("meta").jsonObject
		return D1Result(
			columns = results.getValue("columns").jsonArray.map { it.jsonPrimitive.content },
			rows = results.getValue("rows").jsonArray.map { it.jsonArray.toList() },
			changes = meta.getValue("changes").jsonPrimitive.long,
			lastRowId = meta.getValue("last_row_id").jsonPrimitive.long,
		)
	}

	/**
	 * Turns a Cloudflare error entry into the matching SQL exception.
	 *
	 * @param status HTTP status code of the reply
	 * @param error one entry of the reply's `errors` array
	 * @returns the exception to throw
	 */
	private fun errorFor(status: Int, error: JsonObject): SQLException {
		val code = error.getValue("code").jsonPrimitive.int
		val message = error.getValue("message").jsonPrimitive.content
		if (code != SQL_ERROR_CODE) {
			return requestErrorFor(status, code, message)
		}

		// SQLite ends each message with its error name, so match on that.
		return when {
			message.contains("SQLITE_CONSTRAINT") -> {
				SQLIntegrityConstraintViolationException(message, "23000", code)
			}

			message.endsWith("SQLITE_ERROR") -> {
				SQLSyntaxErrorException(message, "42000", code)
			}

			else -> {
				SQLException(message, "HY000", code)
			}
		}
	}

	/**
	 * Turns a Cloudflare error that is not about the SQL into the matching SQL exception.
	 *
	 * @param status HTTP status code of the reply
	 * @param code Cloudflare error code
	 * @param message Cloudflare error message
	 * @returns the exception to throw
	 */
	private fun requestErrorFor(status: Int, code: Int, message: String): SQLException {
		return when (status) {
			401, 403 -> {
				SQLInvalidAuthorizationSpecException(
					"Cloudflare rejected the API token. Check it is valid and has D1 access. ($message)",
					"28000",
					code,
				)
			}

			404 -> {
				SQLNonTransientConnectionException(
					"Cloudflare could not find this D1 database. Check the account ID and database ID. ($message)",
					"08001",
					code,
				)
			}

			else -> {
				SQLException(message, "HY000", code)
			}
		}
	}

	private companion object {
		/** Cloudflare's error code for a failed SQL statement. */
		const val SQL_ERROR_CODE = 7500
	}
}
