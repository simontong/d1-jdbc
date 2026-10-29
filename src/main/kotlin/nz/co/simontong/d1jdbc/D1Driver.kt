package nz.co.simontong.d1jdbc

import java.net.URI
import java.net.URLDecoder
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLNonTransientConnectionException
import java.util.Properties
import java.util.logging.Logger

/**
 * JDBC driver for Cloudflare D1, reached through the Cloudflare REST API.
 */
class D1Driver : Driver {
	/**
	 * Opens a connection, or returns null when the URL is not a D1 URL.
	 *
	 * @param url JDBC URL in the form `jdbc:d1://<account id>/<database id>`
	 * @param info connection properties, with the API token as `password`
	 * @return the connection, or null for other drivers' URLs
	 * @throws SQLException when the URL or token is missing or malformed
	 */
	override fun connect(url: String?, info: Properties?): Connection? {
		if (url == null) {
			throw SQLException("The JDBC URL is null.")
		}
		if (!acceptsURL(url)) {
			return null
		}

		val settings = D1Settings.parse(url, info ?: Properties())
		val client = D1Client(
			accountId = settings.accountId,
			databaseId = settings.databaseId,
			apiToken = settings.apiToken,
			baseUri = settings.apiBaseUrl,
		)
		return D1Connection(client, settings)
	}

	/**
	 * Says whether this driver handles the URL.
	 *
	 * @param url JDBC URL
	 * @return true for `jdbc:d1:` URLs
	 * @throws SQLException when the URL is null
	 */
	override fun acceptsURL(url: String?): Boolean {
		if (url == null) {
			throw SQLException("The JDBC URL is null.")
		}
		return url.startsWith(URL_PREFIX)
	}

	/**
	 * Lists the settings a connection dialog should ask for.
	 *
	 * @param url JDBC URL
	 * @param info properties filled in so far
	 * @return one entry per setting
	 */
	override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> {
		val password = DriverPropertyInfo("password", info?.getProperty("password"))
		password.description = "Cloudflare API token with D1 Read or D1 Edit permission."
		password.required = true
		val readOnly = DriverPropertyInfo("readOnly", info?.getProperty("readOnly") ?: "false")
		readOnly.description = "Block statements that change data."
		readOnly.choices = arrayOf("true", "false")
		val schemaCache = DriverPropertyInfo("schemaCacheSeconds", info?.getProperty("schemaCacheSeconds") ?: "30")
		schemaCache.description = "Seconds to reuse the table list before asking D1 again."
		return arrayOf(password, readOnly, schemaCache)
	}

	/** @return the driver's major version */
	override fun getMajorVersion(): Int = DRIVER_MAJOR_VERSION

	/** @return the driver's minor version */
	override fun getMinorVersion(): Int = DRIVER_MINOR_VERSION

	/** @return false, as D1 has no transactions and so can't pass the JDBC compliance tests */
	override fun jdbcCompliant(): Boolean = false

	/**
	 * Not supported, as the driver doesn't use java.util.logging.
	 *
	 * @throws SQLFeatureNotSupportedException always
	 */
	override fun getParentLogger(): Logger {
		throw SQLFeatureNotSupportedException("The D1 driver does not use java.util.logging.")
	}

	companion object {
		/** Start of every URL this driver accepts. */
		const val URL_PREFIX = "jdbc:d1:"

		init {
			DriverManager.registerDriver(D1Driver())
		}
	}
}

/**
 * Connection settings read from the JDBC URL and properties.
 *
 * @property accountId Cloudflare account ID
 * @property databaseId D1 database ID
 * @property apiToken Cloudflare API token
 * @property apiBaseUrl Cloudflare API base address
 * @property readOnly whether to block statements that change data
 * @property schemaCacheSeconds how long to reuse the schema once loaded
 */
data class D1Settings(
	val accountId: String,
	val databaseId: String,
	val apiToken: String,
	val apiBaseUrl: URI,
	val readOnly: Boolean,
	val schemaCacheSeconds: Long = 30,
) {
	/**
	 * Describes the settings without the API token, so it never ends up in logs.
	 *
	 * @return readable settings text
	 */
	override fun toString(): String {
		return "D1Settings(accountId=$accountId, databaseId=$databaseId, apiBaseUrl=$apiBaseUrl, readOnly=$readOnly, " +
			"schemaCacheSeconds=$schemaCacheSeconds)"
	}

	companion object {
		private val URL_PATTERN = Regex("""^jdbc:d1://([^/?]+)/([^/?]+)/?(?:\?(.*))?$""")

		/**
		 * Reads settings from a URL and properties, with properties taking priority.
		 *
		 * @param url JDBC URL
		 * @param info connection properties
		 * @return the settings
		 * @throws SQLException when the URL is malformed or the token is missing
		 */
		fun parse(url: String, info: Properties): D1Settings {
			val match = URL_PATTERN.matchEntire(url) ?: throw SQLNonTransientConnectionException(
				"The D1 URL must look like jdbc:d1://<account id>/<database id>.",
				"08001",
			)
			val (accountId, databaseId, query) = match.destructured
			val options = queryOptions(query) + info.stringPropertyNames().associateWith { info.getProperty(it) }

			val apiToken = options["password"].orEmpty()
			if (apiToken.isBlank()) {
				throw SQLNonTransientConnectionException(
					"No Cloudflare API token was given. Enter it as the connection password.",
					"28000",
				)
			}

			return D1Settings(
				accountId = accountId,
				databaseId = databaseId,
				apiToken = apiToken,
				apiBaseUrl = URI.create(options["apiBaseUrl"] ?: "https://api.cloudflare.com/client/v4"),
				readOnly = options["readOnly"].toBoolean(),
				schemaCacheSeconds = schemaCacheSeconds(options["schemaCacheSeconds"]),
			)
		}

		/**
		 * Reads the schema cache setting.
		 *
		 * @param value setting text, or null for the default
		 * @return seconds to reuse the schema
		 * @throws SQLException when the value isn't a whole number of zero or more
		 */
		private fun schemaCacheSeconds(value: String?): Long {
			if (value == null) {
				return 30
			}
			return value.trim().toLongOrNull()?.takeIf { it >= 0 } ?: throw SQLNonTransientConnectionException(
				"schemaCacheSeconds must be a whole number of seconds, 0 or more.",
				"08001",
			)
		}

		/**
		 * Splits a URL query string into options.
		 *
		 * @param query text after the `?`, or empty
		 * @return options by name
		 */
		private fun queryOptions(query: String): Map<String, String> {
			return query.split("&")
				.filter { it.isNotEmpty() }
				.associate {
					val name = URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8)
					name to URLDecoder.decode(it.substringAfter("=", ""), Charsets.UTF_8)
				}
		}
	}
}

/** The version the driver was built as, read from the jar. */
internal val DRIVER_VERSION: String = D1Driver::class.java.getResourceAsStream("version.properties")
	.use { stream -> java.util.Properties().also { it.load(stream) } }
	.getProperty("version")

/** The first number of [DRIVER_VERSION]. */
internal val DRIVER_MAJOR_VERSION: Int = DRIVER_VERSION.split(".")[0].toInt()

/** The second number of [DRIVER_VERSION]. */
internal val DRIVER_MINOR_VERSION: Int = DRIVER_VERSION.split(".").getOrNull(1)?.toIntOrNull() ?: 0
