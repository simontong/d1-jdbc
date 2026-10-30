package nz.co.simontong.d1jdbc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * A request the stub server received.
 *
 * @property method HTTP method
 * @property path request path
 * @property authorization Authorization header value
 * @property contentType Content-Type header value
 * @property body request body text
 */
data class RecordedRequest(
	val method: String,
	val path: String,
	val authorization: String?,
	val contentType: String?,
	val body: String,
)

/**
 * A local HTTP server that stands in for the Cloudflare API in tests.
 */
class StubD1Server : AutoCloseable {
	private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private var status = 200
	private var responseBody = ""
	private var delayMillis = 0L
	private var handler: ((String) -> Pair<Int, String>)? = null

	/** Every SQL request received, oldest first. */
	val requests = mutableListOf<RecordedRequest>()

	/** Database IDs and names the account lists, where the default ID matches [connect]'s URL. */
	var databases = listOf("db456" to "test-db")

	/** Extra headers to send with each SQL reply. */
	var replyHeaders = emptyMap<String, String>()

	/** Whether to close each SQL request's connection without replying, like a dropped network. */
	var dropConnections = false

	/** How many database list requests to fail with HTTP 500 before answering. */
	var databaseListFailures = 0

	/** Databases the stub lists per page, standing in for Cloudflare's page size. */
	var databasesPerPage = 10000

	/** Page queries of each database list request received, like `page=1&per_page=10000`. */
	val databaseListRequests = mutableListOf<String>()

	/** Account IDs and names the account list returns. */
	var accounts = listOf("acc123" to "Main")

	/** Databases per account ID, used in place of [databases] for the accounts it names. */
	var databasesByAccount = emptyMap<String, List<Pair<String, String>>>()

	/** Tokens the stub accepts, or null to accept any. Others get HTTP 403 with code 9109. */
	var acceptedTokens: Set<String>? = null

	/** Authorization header of every request received, oldest first. */
	val authorizations = mutableListOf<String?>()

	/** Base URI to pass to the client in place of the Cloudflare API. */
	val baseUri: URI
		get() = URI.create("http://127.0.0.1:${server.address.port}/client/v4")

	init {
		server.createContext("/") { exchange ->
			val authorization = exchange.requestHeaders.getFirst("Authorization")
			authorizations += authorization
			val allowed = acceptedTokens
			if (allowed != null && authorization?.removePrefix("Bearer ") !in allowed) {
				reply(exchange, 403, """{"success":false,"errors":[{"code":9109,"message":"Invalid access token"}],"messages":[],"result":null}""")
				return@createContext
			}
			if (exchange.requestMethod == "GET" && exchange.requestURI.path == "/client/v4/accounts") {
				val items = accounts.joinToString(",") { (id, name) -> """{"id":"$id","name":"$name"}""" }
				reply(exchange, 200, """{"result":[$items],"errors":[],"messages":[],"success":true}""")
				return@createContext
			}
			if (exchange.requestMethod == "GET" && exchange.requestURI.path.endsWith("/d1/database")) {
				databaseListRequests += exchange.requestURI.query.orEmpty()
				if (databaseListFailures > 0) {
					databaseListFailures--
					exchange.sendResponseHeaders(500, -1)
					exchange.close()
					return@createContext
				}
				val page = Regex("""(?:^|&)page=(\d+)""").find(exchange.requestURI.query.orEmpty())?.groupValues?.get(1)?.toInt() ?: 1
				val account = exchange.requestURI.path.substringAfter("/accounts/").substringBefore("/")
				reply(exchange, 200, databaseListJson(databasesByAccount[account] ?: databases, page))
				return@createContext
			}
			val body = exchange.requestBody.readAllBytes().decodeToString()
			requests += RecordedRequest(
				method = exchange.requestMethod,
				path = exchange.requestURI.path,
				authorization = exchange.requestHeaders.getFirst("Authorization"),
				contentType = exchange.requestHeaders.getFirst("Content-Type"),
				body = body,
			)
			if (delayMillis > 0) {
				Thread.sleep(delayMillis)
			}
			if (dropConnections) {
				exchange.close()
				return@createContext
			}
			val (replyStatus, replyBody) = handler?.invoke(body) ?: (status to responseBody)
			val bytes = replyBody.encodeToByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			replyHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
			exchange.sendResponseHeaders(replyStatus, if (bytes.isEmpty()) -1 else bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	/**
	 * Sends a JSON reply.
	 *
	 * @param exchange the request
	 * @param status HTTP status code
	 * @param body reply body
	 */
	private fun reply(exchange: HttpExchange, status: Int, body: String) {
		val bytes = body.encodeToByteArray()
		exchange.responseHeaders.add("Content-Type", "application/json")
		exchange.sendResponseHeaders(status, bytes.size.toLong())
		exchange.responseBody.use { it.write(bytes) }
	}

	/**
	 * Builds Cloudflare's reply listing one page of databases.
	 *
	 * @param listed every database in the account
	 * @param page page number, starting at 1
	 * @return the reply body
	 */
	private fun databaseListJson(listed: List<Pair<String, String>>, page: Int): String {
		val onPage = listed.drop((page - 1) * databasesPerPage).take(databasesPerPage)
		val items = onPage.joinToString(",") { (id, name) -> """{"uuid":"$id","name":"$name","version":"production"}""" }
		return """{"result":[$items],"result_info":{"page":$page,"per_page":$databasesPerPage,""" +
			""""count":${onPage.size},"total_count":${listed.size}},"errors":[],"messages":[],"success":true}"""
	}

	/**
	 * Sets the reply for every following request.
	 *
	 * @param status HTTP status code
	 * @param body response body text
	 * @param delayMillis wait before replying
	 */
	fun respond(status: Int, body: String, delayMillis: Long = 0) {
		this.handler = null
		this.status = status
		this.responseBody = body
		this.delayMillis = delayMillis
	}

	/**
	 * Works out the reply to each following request from its body.
	 *
	 * @param handler takes the request body and returns the HTTP status and reply body
	 */
	fun respondWith(handler: (String) -> Pair<Int, String>) {
		this.handler = handler
	}

	/**
	 * Opens a JDBC connection to this server through DriverManager.
	 *
	 * @param url JDBC URL to connect to
	 * @param extra extra connection properties
	 * @return the open connection
	 */
	fun connect(url: String = "jdbc:d1://acc123/db456", extra: Map<String, String> = emptyMap()): Connection {
		val properties = Properties()
		properties["password"] = "secret-token"
		properties["apiBaseUrl"] = baseUri.toString()
		extra.forEach { (key, value) -> properties[key] = value }
		return DriverManager.getConnection(url, properties)
	}

	/**
	 * Replies to every following request with one successful result.
	 *
	 * @param columns column names as JSON, like `["a","b"]`
	 * @param rows rows as JSON, like `[[1,"x"]]`
	 * @param changes changed-row count
	 */
	fun respondWithRows(columns: String = "[]", rows: String = "[]", changes: Int = 0) {
		respond(200, """
			{"result":[{"results":{"columns":$columns,"rows":$rows},"success":true,
			"meta":{"changes":$changes,"last_row_id":0}}],"errors":[],"messages":[],"success":true}
		""")
	}

	/** Stops the server. */
	override fun close() {
		server.stop(0)
	}
}
