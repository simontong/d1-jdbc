package nz.co.simontong.d1jdbc

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLInvalidAuthorizationSpecException
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
		if (settings.auth == D1Auth.TOKEN) {
			val client = D1Client(
				accountId = settings.accountId.orEmpty(),
				apiToken = settings.apiToken,
				baseUri = settings.apiBaseUrl,
			)
			return D1Connection(client, settings)
		}
		return signedInConnection(settings, ::openInBrowser)
	}

	/**
	 * Opens a connection with a Cloudflare sign-in, reaching every account the user ticked.
	 *
	 * @param settings connection settings
	 * @param openBrowser opens a web page in the user's browser
	 * @return the connection
	 * @throws SQLException when signing in fails or no account can be reached
	 */
	internal fun signedInConnection(settings: D1Settings, openBrowser: (URI) -> Unit): Connection {
		val signIn = SignInCredentials.shared(
			SignInSettings(SignInCredentials.CLIENT_ID, settings.signInBaseUrl, settings.tokenFile),
			openBrowser,
		)
		// Tools connect in the background too, where a browser opening out of nowhere would
		// confuse.
		if (!signIn.isSignedIn()) {
			if (!settings.openBrowser) {
				throw SQLInvalidAuthorizationSpecException(
					"You need to sign in with Cloudflare. Connect to this data source to open the sign-in page.",
					"28000",
				)
			}
			signIn.signInWithBrowser()
		}
		val accounts = signIn.accounts { D1Client("", signIn, settings.apiBaseUrl).listAccounts() }
		if (accounts.isEmpty()) {
			throw SQLInvalidAuthorizationSpecException(
				"The Cloudflare sign-in reaches no accounts. Delete ${settings.tokenFile}, connect, and tick at least "
					+ "one account on Cloudflare's page. An account admin may also have turned off third-party apps.",
				"28000",
			)
		}
		val chosen = accounts.filter { settings.accountId == null || it.id.equals(settings.accountId, ignoreCase = true) }
		if (chosen.isEmpty()) {
			throw SQLInvalidAuthorizationSpecException(
				"The Cloudflare sign-in doesn't include account ${settings.accountId}. Remove the account ID from the URL, "
					+ "or delete ${settings.tokenFile}, connect, and tick that account on Cloudflare's page.",
				"28000",
			)
		}

		// A sign-in without D1 write permission can only read, so the connection says so up front.
		val canWrite = signIn.canWrite
		val clients = chosen.map { D1Client(it.id, signIn, settings.apiBaseUrl, accountName = it.name) }
		val connection = D1Connection(clients, if (canWrite) settings else settings.copy(readOnly = true))
		if (!canWrite) {
			connection.addWarning(
				"You signed in without D1 write permission, so this connection is read-only. To change data, delete "
					+ "${settings.tokenFile}, connect, and keep D1 Write on Cloudflare's page.",
			)
		}
		return connection
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
		val auth = DriverPropertyInfo("auth", info?.getProperty("auth") ?: "token")
		auth.description = "token to use an API token, or browser to sign in with Cloudflare in the browser."
		auth.choices = arrayOf("token", "browser")
		val tokenFile = DriverPropertyInfo("tokenFile", info?.getProperty("tokenFile") ?: D1Settings.defaultTokenFile().toString())
		tokenFile.description = "File that keeps the Cloudflare sign-in when auth is browser."
		val openBrowser = DriverPropertyInfo("openBrowser", info?.getProperty("openBrowser") ?: "true")
		openBrowser.description = "false stops a connection from opening the browser to sign in."
		openBrowser.choices = arrayOf("true", "false")
		return arrayOf(password, readOnly, schemaCache, auth, tokenFile, openBrowser)
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

/** How a connection proves who it is to Cloudflare. */
enum class D1Auth {
	/** An API token given as the password. */
	TOKEN,

	/** A Cloudflare sign-in in the browser. */
	BROWSER,
}

/**
 * Connection settings read from the JDBC URL and properties.
 *
 * @property accountId Cloudflare account ID, or null to reach every account a sign-in allows
 * @property databaseId ID or name of the D1 database to start on, or null to choose one later
 * @property apiToken Cloudflare API token, or empty when signing in
 * @property apiBaseUrl Cloudflare API base address
 * @property readOnly whether to block statements that change data
 * @property schemaCacheSeconds how long to reuse the schema once loaded
 * @property auth how the connection proves who it is
 * @property signInBaseUrl Cloudflare sign-in base address
 * @property tokenFile file that keeps the Cloudflare sign-in
 * @property openBrowser whether connecting may open the browser to sign in
 */
data class D1Settings(
	val accountId: String?,
	val databaseId: String?,
	val apiToken: String,
	val apiBaseUrl: URI,
	val readOnly: Boolean,
	val schemaCacheSeconds: Long = 30,
	val auth: D1Auth = D1Auth.TOKEN,
	val signInBaseUrl: URI = URI.create(SignInCredentials.CLOUDFLARE_SIGN_IN),
	val tokenFile: Path = defaultTokenFile(),
	val openBrowser: Boolean = true,
) {
	/**
	 * Describes the settings without the API token, so it never ends up in logs.
	 *
	 * @return readable settings text
	 */
	override fun toString(): String {
		return "D1Settings(accountId=$accountId, databaseId=$databaseId, apiBaseUrl=$apiBaseUrl, readOnly=$readOnly, " +
			"schemaCacheSeconds=$schemaCacheSeconds, auth=$auth, signInBaseUrl=$signInBaseUrl, tokenFile=$tokenFile, " +
			"openBrowser=$openBrowser)"
	}

	companion object {
		/** Cloudflare's API address. */
		private const val CLOUDFLARE_API = "https://api.cloudflare.com/client/v4"

		/** Host names that mean this computer. */
		private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

		/** The account part that means every account a sign-in reaches. */
		private const val ALL_ACCOUNTS = "all"

		/** Characters an account ID may hold. */
		private val ID_PATTERN = Regex("""^[A-Za-z0-9-]*$""")

		/** Characters a database ID or name in the URL may hold. */
		private val DATABASE_PATTERN = Regex("""^[A-Za-z0-9._-]*$""")

		private val URL_PATTERN = Regex("""^jdbc:d1://([^/?]*)(?:/([^/?]*))?/?(?:\?(.*))?$""")

		/**
		 * Works out where the sign-in is kept when no tokenFile is set, in the user's config
		 * folder.
		 *
		 * @return the file
		 */
		fun defaultTokenFile(): Path {
			val appData = System.getenv("APPDATA")
			val xdg = System.getenv("XDG_CONFIG_HOME")
			val folder = when {
				!appData.isNullOrBlank() -> Path.of(appData)
				!xdg.isNullOrBlank() -> Path.of(xdg)
				else -> Path.of(System.getProperty("user.home"), ".config")
			}
			return folder.resolve("d1-jdbc").resolve("cloudflare-sign-in.json")
		}

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
				"The D1 URL must look like jdbc:d1://<account id> or jdbc:d1://<account id>/<database id or name>.",
				"08001",
			)
			val (accountPart, databaseId, query) = match.destructured
			// "all" stands for every account a sign-in reaches, as IDEs need some text in the URL.
			val accountId = if (accountPart.equals(ALL_ACCOUNTS, ignoreCase = true)) "" else accountPart
			if (!ID_PATTERN.matches(accountId)) {
				throw SQLNonTransientConnectionException("The account ID can only hold letters, numbers and dashes.", "08001")
			}
			if (!DATABASE_PATTERN.matches(databaseId)) {
				throw SQLNonTransientConnectionException(
					"The database in the URL can only hold letters, numbers, dashes, dots and underscores.",
					"08001",
				)
			}
			val urlOptions = queryOptions(query)
			if ("password" in urlOptions) {
				throw SQLNonTransientConnectionException(
					"Don't put the API token in the URL, as tools save URLs as plain text. Enter it as the password instead.",
					"08001",
				)
			}
			val options = urlOptions + info.stringPropertyNames().associateWith { info.getProperty(it) }
			val auth = auth(options["auth"])

			val apiToken = options["password"].orEmpty().trim()
			// A header can't hold spaces or control characters, which a pasted token can pick up.
			if (apiToken.any { it <= ' ' || it > '~' }) {
				throw SQLNonTransientConnectionException(
					"The API token has spaces, line breaks or other unusual characters in it. Copy it again.",
					"28000",
				)
			}
			if (auth == D1Auth.TOKEN && apiToken.isBlank()) {
				throw SQLNonTransientConnectionException(
					"No Cloudflare API token was given. Enter it as the connection password, or add ?auth=browser to the "
						+ "URL to sign in with Cloudflare.",
					"28000",
				)
			}
			if (auth == D1Auth.TOKEN && accountId.isEmpty()) {
				throw SQLNonTransientConnectionException(
					"An API token needs your Cloudflare account ID. Put it in the Account field, or in the URL like "
						+ "jdbc:d1://<account id>.",
					"08001",
				)
			}

			return D1Settings(
				accountId = accountId.ifEmpty { null },
				databaseId = databaseId.ifEmpty { null },
				apiToken = if (auth == D1Auth.TOKEN) apiToken else "",
				apiBaseUrl = apiBaseUrl(options["apiBaseUrl"]),
				readOnly = options["readOnly"].toBoolean(),
				schemaCacheSeconds = schemaCacheSeconds(options["schemaCacheSeconds"]),
				auth = auth,
				signInBaseUrl = signInBaseUrl(options["signInBaseUrl"]),
				tokenFile = tokenFile(options["tokenFile"]),
				openBrowser = options["openBrowser"]?.trim()?.lowercase() != "false",
			)
		}

		/**
		 * Reads the API address, allowing only Cloudflare, or this computer so tests can use a
		 * stub.
		 *
		 * @param value setting text, or null for Cloudflare's address
		 * @return the address
		 * @throws SQLException when the address could send the token anywhere else
		 */
		private fun apiBaseUrl(value: String?): URI {
			if (value == null || value == CLOUDFLARE_API) {
				return URI.create(CLOUDFLARE_API)
			}
			val uri = try {
				URI(value)
			} catch (error: URISyntaxException) {
				null
			}
			if (uri == null || uri.scheme !in setOf("http", "https") || uri.host !in LOCAL_HOSTS) {
				throw SQLNonTransientConnectionException(
					"apiBaseUrl must be $CLOUDFLARE_API, so the API token is only sent to Cloudflare.",
					"08001",
				)
			}
			return uri
		}

		/**
		 * Reads the sign-in file setting.
		 *
		 * @param value setting text, or null for the default file
		 * @return the file
		 * @throws SQLException when the text isn't a valid path
		 */
		private fun tokenFile(value: String?): Path {
			val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return defaultTokenFile()
			return try {
				Path.of(text)
			} catch (error: InvalidPathException) {
				throw SQLNonTransientConnectionException("tokenFile isn't a valid file path: $text", "08001", error)
			}
		}

		/**
		 * Reads how the connection proves who it is.
		 *
		 * @param value setting text, or null for an API token
		 * @return the choice
		 * @throws SQLException when the value isn't token or browser
		 */
		private fun auth(value: String?): D1Auth {
			return when (value?.trim()?.lowercase()) {
				null, "", "token" -> D1Auth.TOKEN
				"browser" -> D1Auth.BROWSER
				else -> throw SQLNonTransientConnectionException("auth must be token or browser.", "08001")
			}
		}

		/**
		 * Reads the sign-in address, allowing only Cloudflare, or this computer so tests can use a
		 * stub.
		 *
		 * @param value setting text, or null for Cloudflare's address
		 * @return the address
		 * @throws SQLException when the address isn't Cloudflare's or this computer's
		 */
		private fun signInBaseUrl(value: String?): URI {
			val cloudflare = SignInCredentials.CLOUDFLARE_SIGN_IN
			if (value == null || value == cloudflare) {
				return URI.create(cloudflare)
			}
			val uri = try {
				URI(value)
			} catch (error: URISyntaxException) {
				null
			}
			if (uri == null || uri.scheme !in setOf("http", "https") || uri.host !in LOCAL_HOSTS) {
				throw SQLNonTransientConnectionException("signInBaseUrl must be $cloudflare.", "08001")
			}
			return uri
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
			return try {
				query.split("&")
					.filter { it.isNotEmpty() }
					.associate {
						val name = URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8)
						name to URLDecoder.decode(it.substringAfter("=", ""), Charsets.UTF_8)
					}
			} catch (error: IllegalArgumentException) {
				throw SQLNonTransientConnectionException(
					"The settings after ? in the URL aren't valid URL text. Check any % signs.",
					"08001",
					error,
				)
			}
		}
	}
}

/** The version the driver was built as, read from the jar. */
internal val DRIVER_VERSION: String = D1Driver::class.java.getResourceAsStream("version.properties")
	?.use { stream -> java.util.Properties().also { it.load(stream) } }
	?.getProperty("version")
	?: "0.0.0"

/** The first number of [DRIVER_VERSION]. */
internal val DRIVER_MAJOR_VERSION: Int = DRIVER_VERSION.split(".")[0].toInt()

/** The second number of [DRIVER_VERSION]. */
internal val DRIVER_MINOR_VERSION: Int = DRIVER_VERSION.split(".").getOrNull(1)?.toIntOrNull() ?: 0
