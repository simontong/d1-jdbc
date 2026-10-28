package nz.co.simontong.d1jdbc

import java.math.BigDecimal
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * @param requestTimeout how long to wait for each reply
 */
class D1Client(
	private val accountId: String,
	private val databaseId: String,
	private val apiToken: String,
	private val baseUri: URI = URI.create("https://api.cloudflare.com/client/v4"),
	private val requestTimeout: Duration = Duration.ofSeconds(60),
) {
	private val httpClient = HttpClient.newHttpClient()

	/**
	 * Runs statements on D1 and returns one result per statement.
	 *
	 * @param statements statements to run in order
	 * @return results in the same order
	 * @throws SQLException when Cloudflare can't be reached or the SQL fails
	 */
	fun execute(statements: List<D1Statement>): List<D1Result> {
		val request = HttpRequest.newBuilder(URI.create("$baseUri/accounts/$accountId/d1/database/$databaseId/raw"))
			.header("Authorization", "Bearer $apiToken")
			.header("Content-Type", "application/json")
			.timeout(requestTimeout)
			.POST(HttpRequest.BodyPublishers.ofString(requestJson(statements).toString()))
			.build()
		val response = send(request)
		if (response.statusCode() == 429) {
			throw SQLTransientConnectionException(
				"Cloudflare is limiting requests, either because the account made too many API calls "
					+ "or because the database is overloaded. Wait a few minutes and try again.",
				"08001",
			)
		}

		if (response.statusCode() >= 500) {
			throw SQLTransientConnectionException(
				"Cloudflare's API failed with HTTP ${response.statusCode()}. Try again shortly.",
				"08001",
			)
		}

		val body = parseBody(response)
		try {
			return readReply(response.statusCode(), body)
		} catch (error: NoSuchElementException) {
			throw unreadableReply(response, error)
		} catch (error: IllegalArgumentException) {
			throw unreadableReply(response, error)
		}
	}

	/**
	 * Reads the results from a parsed reply, or throws the error it carries.
	 *
	 * @param status HTTP status code of the reply
	 * @param body parsed reply body
	 * @return results in statement order
	 * @throws SQLException when the reply reports a failure
	 * @throws NoSuchElementException when a field is missing
	 * @throws IllegalArgumentException when a field has the wrong type
	 */
	private fun readReply(status: Int, body: JsonObject): List<D1Result> {
		if (!body.getValue("success").jsonPrimitive.boolean) {
			throw errorFor(status, body.getValue("errors").jsonArray.first().jsonObject)
		}
		return body.getValue("result").jsonArray.map { parseResult(it.jsonObject) }
	}

	/**
	 * Sends a request and turns network failures into SQL exceptions.
	 *
	 * @param request request to send
	 * @return the reply
	 * @throws SQLException when the request times out or can't connect
	 */
	private fun send(request: HttpRequest): HttpResponse<String> {
		try {
			return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
		} catch (error: HttpTimeoutException) {
			val seconds = BigDecimal(requestTimeout.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString()
			throw SQLTimeoutException("Cloudflare did not reply within $seconds seconds.", error)
		} catch (error: ConnectException) {
			throw SQLTransientConnectionException(
				"Could not reach Cloudflare's API. Check your network connection.",
				"08001",
				error,
			)
		}
	}

	/**
	 * Parses the reply body as a JSON object.
	 *
	 * @param response reply to parse
	 * @return the JSON object
	 * @throws SQLNonTransientConnectionException when the body isn't a JSON object
	 */
	private fun parseBody(response: HttpResponse<String>): JsonObject {
		val element = try {
			Json.parseToJsonElement(response.body())
		} catch (error: SerializationException) {
			throw unreadableReply(response, error)
		}
		return element as? JsonObject ?: throw unreadableReply(response, null)
	}

	/**
	 * Builds the error for a reply the driver can't read.
	 *
	 * @param response the unreadable reply
	 * @param cause parse failure, if any
	 * @return the exception to throw
	 */
	private fun unreadableReply(response: HttpResponse<String>, cause: Throwable?): SQLException {
		return SQLNonTransientConnectionException(
			"Cloudflare sent a reply the driver could not read (HTTP ${response.statusCode()}).",
			"08000",
			cause,
		)
	}

	/**
	 * Builds the request body, using `batch` when there is more than one statement.
	 *
	 * @param statements statements to send
	 * @return JSON request body
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
	 * @return JSON object with `sql` and `params`
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
	 * @return the parsed result
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
	 * @return the exception to throw
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
	 * @return the exception to throw
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
