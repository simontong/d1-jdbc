package nz.co.simontong.d1jdbc

import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException

/**
 * Supplies the token sent with each Cloudflare API call.
 */
internal interface D1Credentials {
	/**
	 * Returns a token that is still valid.
	 *
	 * @return the token
	 * @throws SQLException when no valid token can be had
	 */
	fun token(): String

	/**
	 * Gets a new token after Cloudflare turned one down.
	 *
	 * @param rejected the token Cloudflare turned down
	 * @return a new token to try once, or null when there is none
	 * @throws SQLException when getting a new token fails
	 */
	fun renew(rejected: String): String?

	/**
	 * Builds the error for a token Cloudflare turned down.
	 *
	 * @param code Cloudflare error code
	 * @param message Cloudflare error message
	 * @return the exception to throw
	 */
	fun rejected(code: Int, message: String): SQLException
}

/**
 * A fixed API token that the user made in the Cloudflare dashboard.
 *
 * @param apiToken the token
 */
internal class ApiTokenCredentials(private val apiToken: String) : D1Credentials {
	/** @return the token */
	override fun token(): String = apiToken

	/** @return null, as a fixed token can't be renewed */
	override fun renew(rejected: String): String? = null

	/**
	 * Builds the error for a token Cloudflare turned down.
	 *
	 * @param code Cloudflare error code
	 * @param message Cloudflare error message
	 * @return the exception to throw
	 */
	override fun rejected(code: Int, message: String): SQLException {
		return SQLInvalidAuthorizationSpecException(
			"Cloudflare rejected the API token. Check it is valid and has D1 access. ($message)",
			"28000",
			code,
		)
	}
}
