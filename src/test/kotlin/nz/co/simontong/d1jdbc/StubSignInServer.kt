package nz.co.simontong.d1jdbc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder

/**
 * A token request the stub sign-in server received.
 *
 * @property form form values sent
 * @property userAgent User-Agent header value
 */
data class TokenRequest(
	val form: Map<String, String>,
	val userAgent: String?,
)

/**
 * A local HTTP server that stands in for Cloudflare's OAuth token endpoint in tests.
 */
class StubSignInServer : AutoCloseable {
	private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

	/** Every token request received, oldest first. */
	val requests = mutableListOf<TokenRequest>()

	/** Scopes the stub grants. */
	var scope = "d1.read d1.write account-settings.read offline_access"

	/** Seconds each access token lasts. */
	var expiresIn = 3599

	/** Status and body to send in place of tokens, or null to send tokens. */
	var failure: Pair<Int, String>? = null

	/** How many tokens the stub has handed out, used to number each new pair. */
	private var issued = 0

	/** Refresh tokens still valid, as each works only once. */
	private val liveRefreshTokens = mutableSetOf<String>()

	/** Base URI to use in place of Cloudflare's sign-in address. */
	val baseUri: URI
		get() = URI.create("http://127.0.0.1:${server.address.port}")

	init {
		server.createContext("/oauth2/token") { exchange ->
			val form = exchange.requestBody.readAllBytes().decodeToString().split("&").filter { it.isNotEmpty() }.associate {
				URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
			}
			requests += TokenRequest(form, exchange.requestHeaders.getFirst("User-Agent"))
			val (status, body) = failure ?: answer(form)
			val bytes = body.encodeToByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	/**
	 * Hands out new tokens for a valid code or refresh token.
	 *
	 * @param form the request's form values
	 * @return HTTP status and body
	 */
	@Synchronized
	private fun answer(form: Map<String, String>): Pair<Int, String> {
		val valid = when (form["grant_type"]) {
			"authorization_code" -> form["code"] == GOOD_CODE
			"refresh_token" -> liveRefreshTokens.remove(form["refresh_token"])
			else -> false
		}
		if (!valid) {
			return 400 to """{"error":"invalid_grant","error_description":"The grant is invalid."}"""
		}
		issued++
		liveRefreshTokens += "refresh-$issued"
		return 200 to """{"access_token":"access-$issued","expires_in":$expiresIn,"refresh_token":"refresh-$issued",""" +
			""""scope":"$scope","token_type":"bearer"}"""
	}

	/** Stops the server. */
	override fun close() {
		server.stop(0)
	}

	companion object {
		/** The code the stub accepts. */
		const val GOOD_CODE = "good-code"
	}
}
