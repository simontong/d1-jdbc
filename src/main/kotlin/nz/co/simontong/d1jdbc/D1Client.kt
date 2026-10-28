package nz.co.simontong.d1jdbc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
			.POST(HttpRequest.BodyPublishers.ofString(statementJson(statements.single()).toString()))
			.build()
		val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

		val body = Json.parseToJsonElement(response.body()).jsonObject
		return body.getValue("result").jsonArray.map { parseResult(it.jsonObject) }
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
}
