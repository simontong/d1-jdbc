package nz.co.simontong.d1jdbc

import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.sql.SQLTransientException
import java.time.Duration
import java.util.Random
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
data class D1Query(
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
 * One D1 database in an account.
 *
 * @property id database ID
 * @property name database name
 * @property accountId ID of the account it belongs to, or empty when not yet known
 */
data class D1DatabaseInfo(
	val id: String,
	val name: String,
	val accountId: String = "",
)

/**
 * One Cloudflare account a token can reach.
 *
 * @property id account ID
 * @property name account name
 */
data class D1AccountInfo(
	val id: String,
	val name: String,
)

/**
 * Sends SQL to the D1 databases of one account through the Cloudflare REST API.
 *
 * @param accountId Cloudflare account ID
 * @param credentials supplies the token for each call
 * @param baseUri Cloudflare API base address
 * @param requestTimeout how long to wait for each reply
 * @param accountName account name, shown when a connection reaches several accounts
 */
class D1Client internal constructor(
	internal val accountId: String,
	private val credentials: D1Credentials,
	private val baseUri: URI = URI.create("https://api.cloudflare.com/client/v4"),
	private val requestTimeout: Duration = Duration.ofSeconds(60),
	internal val accountName: String = accountId,
) {
	/** Tells apart the people behind two clients, so only one person's connections share caches. */
	internal val cacheKey: String
		get() = "${credentials.cacheKey}@$baseUri"

	/**
	 * Makes a client that sends a fixed API token.
	 *
	 * @param accountId Cloudflare account ID
	 * @param apiToken API token with D1 access
	 * @param baseUri Cloudflare API base address
	 * @param requestTimeout how long to wait for each reply
	 */
	constructor(
		accountId: String,
		apiToken: String,
		baseUri: URI = URI.create("https://api.cloudflare.com/client/v4"),
		requestTimeout: Duration = Duration.ofSeconds(60),
	) : this(accountId, ApiTokenCredentials(apiToken), baseUri, requestTimeout)


	/**
	 * Runs statements on a D1 database and returns one result per statement.
	 *
	 * @param databaseId database to run them on
	 * @param statements statements to run in order
	 * @param timeout how long to wait for the reply
	 * @param retry whether to try again after a Cloudflare server error, which is only safe for reads
	 * @return results in the same order
	 * @throws SQLException when Cloudflare can't be reached or the SQL fails
	 */
	fun execute(
		databaseId: String,
		statements: List<D1Query>,
		timeout: Duration = requestTimeout,
		retry: Boolean = false,
	): List<D1Result> {
		val uri = URI.create("$baseUri/accounts/$accountId/d1/database/$databaseId/raw")
		val body = requestJson(statements).toString()
		val response = sendWithToken(timeout, retry) { token, timeLeft ->
			HttpRequest.newBuilder(uri)
				.header("Authorization", "Bearer $token")
				.header("Content-Type", "application/json")
				.timeout(timeLeft)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build()
		}
		val reply = checkedBody(response)
		val results = try {
			readReply(response.statusCode(), reply)
		} catch (error: NoSuchElementException) {
			throw unreadableReply(response, error)
		} catch (error: IllegalArgumentException) {
			throw unreadableReply(response, error)
		}

		// A single statement can hold several, but a batch gives exactly one result per statement.
		if (statements.size > 1 && results.size != statements.size) {
			throw unreadableReply(response, null)
		}
		return results
	}

	/**
	 * Lists every D1 database in the account, reading every page of Cloudflare's list.
	 *
	 * @param timeout how long to wait for each page
	 * @return the databases in the order Cloudflare lists them
	 * @throws SQLException when Cloudflare can't be reached or rejects the token
	 */
	fun listDatabases(timeout: Duration = requestTimeout): List<D1DatabaseInfo> {
		val databases = mutableListOf<D1DatabaseInfo>()
		var page = 1
		while (true) {
			val uri = URI.create("$baseUri/accounts/$accountId/d1/database?page=$page&per_page=$DATABASES_PER_PAGE")
			val body = getPage(uri, timeout)
			val (found, total) = try {
				readDatabasePage(body)
			} catch (error: NoSuchElementException) {
				throw unreadableList("database", error)
			} catch (error: IllegalArgumentException) {
				throw unreadableList("database", error)
			}
			databases += found
			if (found.isEmpty() || databases.size >= total) {
				return databases
			}
			page++
		}
	}

	/**
	 * Reads one page of the database list.
	 *
	 * @param body parsed reply body that reports success
	 * @return the page's databases and the account's total count
	 * @throws NoSuchElementException when a field is missing
	 * @throws IllegalArgumentException when a field has the wrong type
	 */
	private fun readDatabasePage(body: JsonObject): Pair<List<D1DatabaseInfo>, Int> {
		// The API reference marks uuid and name optional, so skip a database with no ID.
		val found = body.getValue("result").jsonArray.mapNotNull { item ->
			val database = item.jsonObject
			val id = database["uuid"]?.jsonPrimitive?.content ?: return@mapNotNull null
			D1DatabaseInfo(id, database["name"]?.jsonPrimitive?.content ?: id, accountId)
		}
		val total = body["result_info"]?.jsonObject?.get("total_count")?.jsonPrimitive?.int ?: found.size
		return found to total
	}

	/**
	 * Lists the accounts the token can reach, reading every page of Cloudflare's list.
	 *
	 * @param timeout how long to wait for each page
	 * @return the accounts in the order Cloudflare lists them
	 * @throws SQLException when Cloudflare can't be reached or rejects the token
	 */
	fun listAccounts(timeout: Duration = requestTimeout): List<D1AccountInfo> {
		val accounts = mutableListOf<D1AccountInfo>()
		var page = 1
		while (true) {
			val body = getPage(URI.create("$baseUri/accounts?page=$page&per_page=$ACCOUNTS_PER_PAGE"), timeout)
			val found = try {
				body.getValue("result").jsonArray.mapNotNull { item ->
					val account = item.jsonObject
					val id = account["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
					D1AccountInfo(id, account["name"]?.jsonPrimitive?.content ?: id)
				}
			} catch (error: IllegalArgumentException) {
				throw unreadableList("account", error)
			} catch (error: NoSuchElementException) {
				throw unreadableList("account", error)
			}
			accounts += found
			if (found.size < ACCOUNTS_PER_PAGE) {
				return accounts
			}
			page++
		}
	}

	/**
	 * Reads one page of a Cloudflare list, trying again after a server error as reading is safe.
	 *
	 * @param uri page address
	 * @param timeout how long to wait
	 * @return the parsed reply, which reports success
	 * @throws SQLException when Cloudflare can't be reached or the reply reports a failure
	 */
	private fun getPage(uri: URI, timeout: Duration): JsonObject {
		val response = sendWithToken(timeout, retry = true) { token, timeLeft ->
			HttpRequest.newBuilder(uri)
				.header("Authorization", "Bearer $token")
				.timeout(timeLeft)
				.GET()
				.build()
		}
		val body = checkedBody(response)
		val success = try {
			body.getValue("success").jsonPrimitive.boolean
		} catch (error: IllegalArgumentException) {
			throw unreadableReply(response, error)
		} catch (error: NoSuchElementException) {
			throw unreadableReply(response, error)
		}
		if (!success) {
			throw errorFor(response.statusCode(), body.getValue("errors").jsonArray.first().jsonObject)
		}
		return body
	}

	/**
	 * Sends a request with the current token, and once more with a new token if Cloudflare turns it
	 * down, all within one time limit.
	 *
	 * @param timeout time limit for every try together
	 * @param retry whether sending it again after a server error is safe
	 * @param build builds the request around a token and the time left
	 * @return the last reply
	 * @throws SQLException when the request fails, runs out of time, or no token can be had
	 */
	private fun sendWithToken(
		timeout: Duration,
		retry: Boolean,
		build: (String, Duration) -> HttpRequest,
	): HttpResponse<String> {
		val deadline = System.nanoTime() + timeout.toNanos()
		val token = credentials.token()
		val response = sendAndRetry(deadline, timeout, retry) { build(token, it) }
		if (response.statusCode() != 401 && response.statusCode() != 403) {
			return response
		}
		// A sign-in token can end early, so get a new one and try once more.
		val renewed = credentials.renew(token, errorCode(response)) ?: return response
		return sendAndRetry(deadline, timeout, retry) { build(renewed, it) }
	}

	/**
	 * Reads the first Cloudflare error code from a reply.
	 *
	 * @param response the reply
	 * @return the code, or null when the reply has none
	 */
	private fun errorCode(response: HttpResponse<String>): Int? {
		return try {
			val errors = Json.parseToJsonElement(response.body()).jsonObject["errors"]?.jsonArray
			errors?.firstOrNull()?.jsonObject?.get("code")?.jsonPrimitive?.int
		} catch (error: SerializationException) {
			null
		} catch (error: IllegalArgumentException) {
			null
		}
	}

	/**
	 * Throws for replies that mean Cloudflare is busy or failing, then parses the body.
	 *
	 * @param response the reply
	 * @return the parsed body
	 * @throws SQLException when Cloudflare is limiting requests, failing, or sent unreadable JSON
	 */
	private fun checkedBody(response: HttpResponse<String>): JsonObject {
		if (response.statusCode() == 429) {
			// Cloudflare sends retry-after, in seconds, only when the rate limit was hit.
			val wait = response.headers().firstValue("retry-after").orElse(null)?.trim()?.toLongOrNull()
			val advice = if (wait != null) "Try again in $wait seconds." else "Wait a few minutes and try again."
			throw SQLTransientException(
				"Cloudflare is limiting requests, either because your Cloudflare user made too many API calls "
					+ "or because the database is overloaded. $advice",
				"HY000",
			)
		}

		if (response.statusCode() >= 500) {
			throw SQLTransientConnectionException(
				"Cloudflare's API failed with HTTP ${response.statusCode()}. Try again shortly.",
				"08S01",
			)
		}

		return parseBody(response)
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
	 * Sends a request, and when asked, sends it again after short waits if Cloudflare's servers fail.
	 *
	 * @param deadline when the time limit ends, from [System.nanoTime]
	 * @param timeout the whole time limit, for the error message
	 * @param retry whether sending it again is safe
	 * @param build builds the request with the time left
	 * @return the last reply
	 * @throws SQLException when the request times out, can't connect, is cut off or is stopped
	 */
	private fun sendAndRetry(
		deadline: Long,
		timeout: Duration,
		retry: Boolean,
		build: (Duration) -> HttpRequest,
	): HttpResponse<String> {
		val waits = if (retry) RETRY_WAITS else emptyList()
		for (wait in waits) {
			val response = send(build(timeLeft(deadline, timeout)), timeout)
			if (response.statusCode() < 500) {
				return response
			}
			// Cloudflare's retry guide asks for a random spread, so many clients don't retry at once.
			val spread = wait.toMillis() + (wait.toMillis() * random.nextDouble() * RETRY_SPREAD).toLong()
			if (System.nanoTime() + Duration.ofMillis(spread).toNanos() >= deadline) {
				return response
			}
			try {
				Thread.sleep(spread)
			} catch (error: InterruptedException) {
				Thread.currentThread().interrupt()
				throw SQLException("The request to Cloudflare was stopped before it replied.", "HY008", error)
			}
		}
		return send(build(timeLeft(deadline, timeout)), timeout)
	}

	/**
	 * Works out the time left before a deadline.
	 *
	 * @param deadline when the time limit ends, from [System.nanoTime]
	 * @param timeout the whole time limit, for the error message
	 * @return the time left
	 * @throws SQLTimeoutException when no time is left
	 */
	private fun timeLeft(deadline: Long, timeout: Duration): Duration {
		val left = deadline - System.nanoTime()
		if (left <= 0) {
			throw timedOut(timeout, null)
		}
		return Duration.ofNanos(left)
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
	 * Builds the error for a list the driver can't read.
	 *
	 * @param item what the list holds, like "database"
	 * @param cause parse failure
	 * @return the exception to throw
	 */
	private fun unreadableList(item: String, cause: Throwable): SQLException {
		return SQLNonTransientConnectionException("Cloudflare sent a $item list the driver could not read.", "08000", cause)
	}

	/**
	 * Builds the request body, using `batch` when there is more than one statement.
	 *
	 * @param statements statements to send
	 * @return JSON request body
	 */
	private fun requestJson(statements: List<D1Query>): JsonObject {
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
	private fun statementJson(statement: D1Query): JsonObject {
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
		// Cloudflare's API reference marks every one of these fields optional.
		val results = result["results"]?.jsonObject
		val meta = result["meta"]?.jsonObject
		return D1Result(
			columns = results?.get("columns")?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
			rows = results?.get("rows")?.jsonArray.orEmpty().map { it.jsonArray.toList() },
			changes = meta?.get("changes")?.jsonPrimitive?.long ?: 0,
			lastRowId = meta?.get("last_row_id")?.jsonPrimitive?.long ?: 0,
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
				credentials.rejected(code, message)
			}

			404 -> {
				SQLException(
					"Cloudflare could not find this D1 database. Check the account ID and database ID. ($message)",
					"3D000",
					code,
				)
			}

			else -> {
				SQLException(message, "HY000", code)
			}
		}
	}

	internal companion object {
		/** Cloudflare's error code for a failed SQL statement. */
		const val SQL_ERROR_CODE = 7500

		/** One HTTP client for every connection, as it's safe to share and holds the open sockets. */
		private val httpClient: HttpClient = HttpClient.newHttpClient()

		/** How long to wait before each new try of a read after a Cloudflare server error. */
		private val RETRY_WAITS = listOf(Duration.ofMillis(250), Duration.ofSeconds(1))

		/** Most extra wait added to each retry, as a share of the wait, as in Cloudflare's own SDK. */
		private const val RETRY_SPREAD = 0.25

		/** Picks each retry's extra wait. */
		private val random = Random()

		/**
		 * Builds the error for a request that ran out of time.
		 *
		 * @param timeout the time limit
		 * @param cause the HTTP client's timeout, if any
		 * @return the exception to throw
		 */
		private fun timedOut(timeout: Duration, cause: Throwable?): SQLTimeoutException {
			val seconds = BigDecimal(timeout.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString()
			val unit = if (seconds == "1") "second" else "seconds"
			return SQLTimeoutException(
				"Cloudflare did not reply within $seconds $unit. A statement that changes data may or may "
					+ "not have been saved.",
				"HYT00",
				cause,
			)
		}

		/** Databases asked for per page of the list, which is Cloudflare's most. */
		private const val DATABASES_PER_PAGE = 10000

		/** Accounts asked for per page of the list, which is Cloudflare's most. */
		private const val ACCOUNTS_PER_PAGE = 50

		/**
		 * Sends a request and turns network failures into SQL exceptions.
		 *
		 * @param request request to send
		 * @param timeout time limit set on the request, for the error message
		 * @return the reply
		 * @throws SQLException when the request times out, can't connect, is cut off or is stopped
		 */
		internal fun send(request: HttpRequest, timeout: Duration): HttpResponse<String> {
			try {
				return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			} catch (error: HttpTimeoutException) {
				throw timedOut(timeout, error)
			} catch (error: ConnectException) {
				throw SQLTransientConnectionException(
					"Could not reach Cloudflare's API. Check your network connection.",
					"08001",
					error,
				)
			} catch (error: IOException) {
				throw SQLTransientConnectionException(
					"Lost the connection to Cloudflare's API before it replied. Check your network connection and try "
						+ "again. A statement that changes data may or may not have been saved.",
					"08S01",
					error,
				)
			} catch (error: InterruptedException) {
				// Keep the stop request so the caller's thread still sees it.
				Thread.currentThread().interrupt()
				throw SQLException("The request to Cloudflare was stopped before it replied.", "HY008", error)
			}
		}
	}
}
