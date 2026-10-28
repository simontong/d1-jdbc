package nz.co.simontong.d1jdbc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI

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

	/** Every request received, oldest first. */
	val requests = mutableListOf<RecordedRequest>()

	/** Base URI to pass to the client in place of the Cloudflare API. */
	val baseUri: URI
		get() = URI.create("http://127.0.0.1:${server.address.port}/client/v4")

	init {
		server.createContext("/") { exchange ->
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
			val bytes = responseBody.encodeToByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	/**
	 * Sets the reply for every following request.
	 *
	 * @param status HTTP status code
	 * @param body response body text
	 * @param delayMillis wait before replying
	 */
	fun respond(status: Int, body: String, delayMillis: Long = 0) {
		this.status = status
		this.responseBody = body
		this.delayMillis = delayMillis
	}

	/** Stops the server. */
	override fun close() {
		server.stop(0)
	}
}
