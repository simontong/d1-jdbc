package nz.co.simontong.d1jdbc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Settings for signing in with Cloudflare.
 *
 * @property clientId ID of the OAuth client registered with Cloudflare
 * @property signInBaseUri address of Cloudflare's sign-in endpoints
 * @property tokenFile file that keeps the sign-in between connections
 * @property waitForSignIn how long to wait for the user to finish in the browser
 */
internal data class SignInSettings(
	val clientId: String,
	val signInBaseUri: URI,
	val tokenFile: Path,
	val waitForSignIn: Duration = Duration.ofMinutes(5),
)

/**
 * Tokens from a Cloudflare sign-in.
 *
 * @property accessToken token sent with API calls
 * @property refreshToken token that gets a new access token
 * @property expiresAt when the access token stops working
 * @property scopes permissions the user granted
 */
internal data class SignInTokens(
	val accessToken: String,
	val refreshToken: String,
	val expiresAt: Instant,
	val scopes: Set<String>,
)

/**
 * Signs in with Cloudflare in the browser, and keeps the tokens fresh in a file shared by every
 * connection on this computer.
 *
 * @param settings sign-in settings
 * @param openBrowser opens a web page in the user's browser
 * @param clock source of the current time
 */
internal class SignInCredentials(
	private val settings: SignInSettings,
	private val openBrowser: (URI) -> Unit,
	private val clock: Clock = Clock.systemUTC(),
) : D1Credentials {
	/** The tokens in use, or null until loaded or signed in. */
	private var tokens: SignInTokens? = null

	/** Lets only one browser sign-in run at a time, without blocking the token methods. */
	private val browserLock = ReentrantLock()

	/**
	 * The accounts the sign-in reaches and when they were listed, so new connections skip the call.
	 */
	private var accounts: Pair<Instant, List<D1AccountInfo>>? = null

	/** Connections that sign in through the same file share caches. */
	override val cacheKey: String = "sign-in:${settings.tokenFile.toAbsolutePath().normalize()}:${settings.clientId}"

	/**
	 * Returns the accounts the sign-in reaches, listing them again after a minute.
	 *
	 * @param list asks Cloudflare for the accounts
	 * @return the accounts
	 * @throws SQLException when Cloudflare can't be reached
	 */
	@Synchronized
	fun accounts(list: () -> List<D1AccountInfo>): List<D1AccountInfo> {
		val known = accounts
		if (known != null && known.first.isAfter(clock.instant().minus(ACCOUNTS_MAX_AGE))) {
			return known.second
		}
		val listed = list()
		accounts = clock.instant() to listed
		return listed
	}

	/** Whether the user granted D1 write permission. */
	val canWrite: Boolean
		@Synchronized get() = D1_WRITE_SCOPE in currentTokens().scopes

	/**
	 * Says whether a sign-in is saved, so connecting won't need the browser.
	 *
	 * @return true when signed in
	 */
	@Synchronized
	fun isSignedIn(): Boolean {
		return tokens != null || withFileLock { readTokenFile() } != null
	}

	/**
	 * Returns an access token with at least a minute left, refreshing or signing in when needed.
	 *
	 * @return the access token
	 * @throws SQLException when signing in or refreshing fails
	 */
	@Synchronized
	override fun token(): String {
		val current = currentTokens()
		if (current.expiresAt.isAfter(clock.instant().plus(REFRESH_EARLY))) {
			return current.accessToken
		}
		return refresh(current).accessToken
	}

	/**
	 * Refreshes the tokens after Cloudflare called the access token invalid, or when it is about to
	 * end.
	 *
	 * @param rejected the access token Cloudflare turned down
	 * @param code Cloudflare's error code, or null when the reply had none
	 * @return the new access token, or null when a refresh can't help
	 * @throws SQLException when refreshing fails
	 */
	@Synchronized
	override fun renew(rejected: String, code: Int?): String? {
		val current = currentTokens()
		if (current.accessToken != rejected) {
			return current.accessToken
		}
		// Each refresh uses up a refresh token, so refresh only when the token itself is the
		// problem.
		val ending = !current.expiresAt.isAfter(clock.instant().plus(REFRESH_EARLY))
		if (code != INVALID_TOKEN_CODE && !ending) {
			return null
		}
		return refresh(current).accessToken
	}

	/**
	 * Builds the error for a sign-in token Cloudflare turned down.
	 *
	 * @param code Cloudflare error code
	 * @param message Cloudflare error message
	 * @return the exception to throw
	 */
	override fun rejected(code: Int, message: String): SQLException {
		return SQLInvalidAuthorizationSpecException(
			"Cloudflare turned down the sign-in for this account. Make sure you ticked this account on " +
				"Cloudflare's sign-in page. To sign in again, delete ${settings.tokenFile} and connect. ($message)",
			"28000",
			code,
		)
	}

	/**
	 * Returns the tokens in use, loading them from the file or signing in the first time.
	 *
	 * @return the tokens
	 * @throws SQLException when signing in fails
	 */
	private fun currentTokens(): SignInTokens {
		tokens?.let { return it }
		// Only connecting may open the browser, so a background call asks for a sign-in instead.
		val loaded = withFileLock { readTokenFile() } ?: throw signInNeeded()
		tokens = loaded
		return loaded
	}

	/**
	 * Signs in with Cloudflare in the browser, unless a sign-in is already saved. The browser wait
	 * holds
	 * no lock, so other connections keep working, and only one sign-in runs at a time.
	 *
	 * @throws SQLException when the user cancels, the wait runs out, or Cloudflare fails
	 */
	fun signInWithBrowser() {
		val waitUntil = System.nanoTime() + settings.waitForSignIn.toNanos()
		val locked = try {
			browserLock.tryLock(settings.waitForSignIn.toMillis(), TimeUnit.MILLISECONDS)
		} catch (error: InterruptedException) {
			Thread.currentThread().interrupt()
			throw SQLException("The Cloudflare sign-in was stopped before it finished.", "HY008", error)
		}
		if (!locked) {
			throw SQLTimeoutException("Another Cloudflare sign-in is still open in the browser. Finish it, then connect.", "HYT00")
		}
		try {
			if (isSignedIn() || System.nanoTime() >= waitUntil) {
				return
			}
			val fresh = signIn()
			synchronized(this) {
				withFileLock { writeTokenFile(fresh) }
				tokens = fresh
				accounts = null
			}
		} finally {
			browserLock.unlock()
		}
	}

	/**
	 * Builds the error for a connection that needs a sign-in it may not start itself.
	 *
	 * @return the exception to throw
	 */
	private fun signInNeeded(): SQLException {
		return SQLInvalidAuthorizationSpecException(
			"You need to sign in with Cloudflare. Connect to this data source to open the sign-in page.",
			"28000",
		)
	}

	/**
	 * Gets new tokens with the refresh token, unless another connection already did.
	 *
	 * @param stale the tokens this connection last saw
	 * @return the new tokens
	 * @throws SQLException when Cloudflare refuses the refresh token, or can't be reached
	 */
	private fun refresh(stale: SignInTokens): SignInTokens {
		val fresh = withFileLock {
			// Each refresh token works once, so use the one another connection may have just saved.
			val saved = readTokenFile()
			if (saved != null && saved.accessToken != stale.accessToken) {
				return@withFileLock saved
			}
			val refreshed = try {
				requestTokens(mapOf("grant_type" to "refresh_token", "refresh_token" to stale.refreshToken), stale)
			} catch (error: SignInEndedException) {
				deleteTokenFile()
				tokens = null
				throw SQLInvalidAuthorizationSpecException(
					"Your Cloudflare sign-in has ended, as it was revoked or not used for too long. Connect again " +
						"to sign in.",
					"28000",
					error,
				)
			}
			writeTokenFile(refreshed)
			refreshed
		}
		tokens = fresh
		return fresh
	}

	/**
	 * Runs the browser sign-in: opens Cloudflare's page, waits for its reply, then swaps the code
	 * for tokens.
	 *
	 * @return the new tokens
	 * @throws SQLException when the user cancels, the wait runs out, or Cloudflare fails
	 */
	private fun signIn(): SignInTokens {
		val verifier = randomText()
		val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
		val state = randomText()
		val reply = CompletableFuture<Map<String, String>>()
		val server = try {
			// The redirect names 127.0.0.1, so listen there rather than on whatever loopback Java
			// prefers.
			HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0), 0)
		} catch (error: IOException) {
			throw SQLNonTransientConnectionException(
				"Could not open a local port for Cloudflare's sign-in to reply to. Check that a firewall allows " +
					"programs to listen on 127.0.0.1.",
				"08001",
				error,
			)
		}
		server.createContext(CALLBACK_PATH) { exchange -> answerCallback(exchange, state, reply) }
		server.start()
		try {
			val redirectUri = "http://$LOOPBACK_ADDRESS:${server.address.port}$CALLBACK_PATH"
			openSignInPage(authorizeUri(redirectUri, challenge, state))
			val query = waitForReply(reply)
			val code = codeFrom(query)
			return requestTokens(
				mapOf(
					"grant_type" to "authorization_code",
					"code" to code,
					"redirect_uri" to redirectUri,
					"code_verifier" to verifier,
				),
				previous = null,
			)
		} catch (error: SignInEndedException) {
			throw SQLInvalidAuthorizationSpecException("Cloudflare would not finish the sign-in. Connect again to retry.", "28000", error)
		} finally {
			server.stop(0)
		}
	}

	/**
	 * Builds the address of Cloudflare's sign-in page.
	 *
	 * @param redirectUri where Cloudflare sends the user back to
	 * @param challenge hash of the code verifier
	 * @param state random text that ties the reply to this sign-in
	 * @return the page address
	 */
	private fun authorizeUri(redirectUri: String, challenge: String, state: String): URI {
		return URI.create(
			"${settings.signInBaseUri}/oauth2/auth?" + formText(
				mapOf(
					"response_type" to "code",
					"client_id" to settings.clientId,
					"redirect_uri" to redirectUri,
					"scope" to SCOPES.joinToString(" "),
					"state" to state,
					"code_challenge" to challenge,
					"code_challenge_method" to "S256",
				),
			),
		)
	}

	/**
	 * Opens the sign-in page in the browser.
	 *
	 * @param page the page address
	 * @throws SQLException when no browser can be opened
	 */
	private fun openSignInPage(page: URI) {
		try {
			openBrowser(page)
		} catch (error: Exception) {
			throw SQLNonTransientConnectionException(
				"Could not open a browser to sign in with Cloudflare. Use an API token on this computer instead.",
				"08001",
				error,
			)
		}
	}

	/**
	 * Waits for Cloudflare to send the user back to this computer.
	 *
	 * @param reply completes with the query of Cloudflare's reply
	 * @return the query values
	 * @throws SQLException when the wait runs out or is stopped
	 */
	private fun waitForReply(reply: CompletableFuture<Map<String, String>>): Map<String, String> {
		try {
			return reply.get(settings.waitForSignIn.toMillis(), TimeUnit.MILLISECONDS)
		} catch (error: TimeoutException) {
			val minutes = settings.waitForSignIn.toMinutes()
			val wait = if (minutes >= 1) "$minutes minutes" else "${settings.waitForSignIn.toSeconds()} seconds"
			throw SQLTimeoutException(
				"The Cloudflare sign-in wasn't finished within $wait. Connect again to retry.",
				"HYT00",
				error,
			)
		} catch (error: InterruptedException) {
			Thread.currentThread().interrupt()
			throw SQLException("The Cloudflare sign-in was stopped before it finished.", "HY008", error)
		} catch (error: ExecutionException) {
			throw SQLException("The Cloudflare sign-in failed.", "HY000", error.cause)
		}
	}

	/**
	 * Takes the code out of Cloudflare's reply, or throws the error the reply carries.
	 *
	 * @param query the reply's query values
	 * @return the code
	 * @throws SQLException when the user cancelled or Cloudflare reported an error
	 */
	private fun codeFrom(query: Map<String, String>): String {
		val error = query["error"]
		if (error == "access_denied") {
			throw SQLInvalidAuthorizationSpecException("The Cloudflare sign-in was cancelled. Connect again to retry.", "28000")
		}
		if (error != null) {
			throw SQLNonTransientConnectionException(
				"Cloudflare's sign-in failed with \"$error\". ${query["error_description"].orEmpty()}".trim(),
				"08004",
			)
		}
		return query["code"] ?: throw SQLNonTransientConnectionException(
			"Cloudflare's sign-in sent no code back. Connect again to retry.",
			"08004",
		)
	}

	/**
	 * Calls Cloudflare's token endpoint and reads the tokens it sends.
	 *
	 * @param form form values to send, apart from the client ID
	 * @param previous the tokens being refreshed, whose refresh token and scopes stay when the
	 * reply leaves them out
	 * @return the tokens
	 * @throws SignInEndedException when Cloudflare refuses the code or refresh token
	 * @throws SQLException when Cloudflare can't be reached or sends something else
	 */
	private fun requestTokens(form: Map<String, String>, previous: SignInTokens?): SignInTokens {
		val request = HttpRequest.newBuilder(URI.create("${settings.signInBaseUri}/oauth2/token"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			// Cloudflare blocks the token endpoint for unnamed programs with error 1010.
			.header("User-Agent", "d1-jdbc/$DRIVER_VERSION")
			.timeout(TOKEN_TIMEOUT)
			.POST(HttpRequest.BodyPublishers.ofString(formText(form + ("client_id" to settings.clientId))))
			.build()
		val response = D1Client.send(request, TOKEN_TIMEOUT)
		val body = try {
			Json.parseToJsonElement(response.body()).jsonObject
		} catch (error: SerializationException) {
			null
		} catch (error: IllegalArgumentException) {
			null
		}
		val error = body?.get("error")?.jsonPrimitive?.content
		if (error == "invalid_grant") {
			throw SignInEndedException()
		}
		if (response.statusCode() != 200 || body == null || error != null) {
			val detail = error ?: response.body().take(MAX_DETAIL_LENGTH).trim()
			throw SQLNonTransientConnectionException(
				"Cloudflare's sign-in failed with HTTP ${response.statusCode()}: $detail",
				"08004",
			)
		}
		return tokensFrom(body, previous)
	}

	/**
	 * Reads the tokens from the token endpoint's reply.
	 *
	 * @param body parsed reply
	 * @param previous the tokens being refreshed, or null after a sign-in
	 * @return the tokens
	 * @throws SQLException when a field is missing
	 */
	private fun tokensFrom(body: JsonObject, previous: SignInTokens?): SignInTokens {
		val accessToken = textField(body, "access_token")
		// OAuth lets a refresh reply leave out the refresh token, which then stays the same.
		val refreshToken = textField(body, "refresh_token") ?: previous?.refreshToken
		if (accessToken == null || refreshToken == null) {
			throw SQLNonTransientConnectionException("Cloudflare's sign-in sent a reply with no tokens in it.", "08004")
		}
		// A very short or odd lifetime would make every call refresh, so keep it within sensible
		// bounds.
		val lifetime = ((body["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_LIFETIME_SECONDS)
			.coerceIn(MIN_LIFETIME_SECONDS, MAX_LIFETIME_SECONDS)
		return SignInTokens(
			accessToken = accessToken,
			refreshToken = refreshToken,
			expiresAt = clock.instant().plusSeconds(lifetime),
			scopes = textField(body, "scope")?.split(" ")?.filter { it.isNotEmpty() }?.toSet() ?: previous?.scopes.orEmpty(),
		)
	}

	/**
	 * Reads a text field from a reply, ignoring one that isn't text.
	 *
	 * @param body parsed reply
	 * @param name field name
	 * @return the text, or null when missing or not text
	 */
	private fun textField(body: JsonObject, name: String): String? {
		return (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
	}

	/**
	 * Reads the saved tokens.
	 *
	 * @return the tokens, or null when there are none or the file can't be read
	 */
	private fun readTokenFile(): SignInTokens? {
		val text = try {
			Files.readString(settings.tokenFile)
		} catch (error: IOException) {
			return null
		}
		return try {
			val saved = Json.parseToJsonElement(text).jsonObject.getValue(settings.clientId).jsonObject
			SignInTokens(
				accessToken = saved.getValue("accessToken").jsonPrimitive.content,
				refreshToken = saved.getValue("refreshToken").jsonPrimitive.content,
				expiresAt = Instant.ofEpochSecond(saved.getValue("expiresAt").jsonPrimitive.content.toLong()),
				scopes = saved.getValue("scopes").jsonPrimitive.content.split(" ").filter { it.isNotEmpty() }.toSet(),
			)
		} catch (error: SerializationException) {
			null
		} catch (error: IllegalArgumentException) {
			null
		} catch (error: NoSuchElementException) {
			null
		}
	}

	/**
	 * Saves the tokens in a file only this user can read, replacing it in one step.
	 *
	 * @param saving tokens to save
	 * @throws SQLException when the file can't be written
	 */
	private fun writeTokenFile(saving: SignInTokens) {
		val entry = JsonObject(
			mapOf(
				"accessToken" to JsonPrimitive(saving.accessToken),
				"refreshToken" to JsonPrimitive(saving.refreshToken),
				"expiresAt" to JsonPrimitive(saving.expiresAt.epochSecond.toString()),
				"scopes" to JsonPrimitive(saving.scopes.sorted().joinToString(" ")),
			),
		)
		// Keep the sign-ins of other OAuth clients that share the file.
		val json = JsonObject(otherClientsInFile() + (settings.clientId to entry))
		var temp: Path? = null
		try {
			val folder = settings.tokenFile.toAbsolutePath().parent
			Files.createDirectories(folder)
			temp = Files.createTempFile(folder, ".sign-in", ".tmp")
			restrictToOwner(temp)
			Files.writeString(temp, json.toString())
			try {
				Files.move(temp, settings.tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			} catch (error: AtomicMoveNotSupportedException) {
				Files.move(temp, settings.tokenFile, StandardCopyOption.REPLACE_EXISTING)
			}
			temp = null
		} catch (error: IOException) {
			throw SQLNonTransientConnectionException(
				"Could not save the Cloudflare sign-in to ${settings.tokenFile}.",
				"08001",
				error,
			)
		} finally {
			temp?.let { runCatching { Files.deleteIfExists(it) } }
		}
	}

	/**
	 * Reads the sign-ins of other OAuth clients from the token file.
	 *
	 * @return each other client's entry by client ID, or none when the file is missing or
	 * unreadable
	 */
	private fun otherClientsInFile(): Map<String, JsonElement> {
		val saved = try {
			Json.parseToJsonElement(Files.readString(settings.tokenFile)) as? JsonObject
		} catch (error: IOException) {
			null
		} catch (error: SerializationException) {
			null
		}
		return saved.orEmpty().filterKeys { it != settings.clientId }
	}

	/**
	 * Runs a step while holding a lock on the token file, so connections in other programs wait
	 * their turn.
	 *
	 * @param step the step to run
	 * @return the step's result
	 * @throws SQLException when the lock file can't be opened
	 */
	private fun <T> withFileLock(step: () -> T): T {
		val tokenFile = settings.tokenFile.toAbsolutePath().normalize()
		val lockFile = tokenFile.resolveSibling("${tokenFile.fileName}.lock")
		val channel = try {
			Files.createDirectories(lockFile.parent)
			FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
		} catch (error: IOException) {
			throw SQLNonTransientConnectionException("Could not open the sign-in lock file $lockFile.", "08001", error)
		} catch (error: InvalidPathException) {
			throw SQLNonTransientConnectionException("The sign-in file path $tokenFile isn't valid.", "08001", error)
		}
		return channel.use {
			val lock = waitForLock(it, lockFile)
			lock.use { step() }
		}
	}

	/**
	 * Takes the lock on the lock file, waiting a limited time for other programs to finish with it.
	 *
	 * @param channel the open lock file
	 * @param lockFile its path, for the error message
	 * @return the lock
	 * @throws SQLException when the lock isn't free in time, or the file can't be locked
	 */
	private fun waitForLock(channel: FileChannel, lockFile: Path): FileLock {
		val waitUntil = System.nanoTime() + FILE_LOCK_WAIT.toNanos()
		while (true) {
			val lock = try {
				channel.tryLock()
			} catch (error: OverlappingFileLockException) {
				null
			} catch (error: IOException) {
				throw SQLNonTransientConnectionException("Could not lock the sign-in file $lockFile.", "08001", error)
			}
			if (lock != null) {
				return lock
			}
			if (System.nanoTime() >= waitUntil) {
				throw SQLTimeoutException(
					"Another program held the Cloudflare sign-in file for too long. Try again in a moment.",
					"HYT00",
				)
			}
			try {
				Thread.sleep(FILE_LOCK_POLL.toMillis())
			} catch (error: InterruptedException) {
				Thread.currentThread().interrupt()
				throw SQLException("Waiting for the Cloudflare sign-in file was stopped.", "HY008", error)
			}
		}
	}

	/**
	 * Deletes the saved sign-in, so the next connection signs in again.
	 *
	 * @throws SQLException when the file can't be deleted
	 */
	private fun deleteTokenFile() {
		try {
			Files.deleteIfExists(settings.tokenFile)
		} catch (error: IOException) {
			throw SQLNonTransientConnectionException(
				"Could not delete the ended Cloudflare sign-in at ${settings.tokenFile}. Delete it, then connect.",
				"08001",
				error,
			)
		}
	}

	internal companion object {
		/** The OAuth client registered in Cloudflare for this driver. */
		const val CLIENT_ID = "05265ccc0ff46e3dfeba1fc9f05ee8e2"

		/** Cloudflare's sign-in address. */
		const val CLOUDFLARE_SIGN_IN = "https://dash.cloudflare.com"

		/** The permission that lets the driver change data. */
		const val D1_WRITE_SCOPE = "d1.write"

		/** Permissions asked for: D1, the account list, and a refresh token. */
		private val SCOPES = listOf("d1.read", D1_WRITE_SCOPE, "account-settings.read", "offline_access")

		/** Address Cloudflare sends the user back to, which RFC 8252 prefers over localhost. */
		private const val LOOPBACK_ADDRESS = "127.0.0.1"

		/** Cloudflare's error code for an access token that is invalid, revoked or ended. */
		private const val INVALID_TOKEN_CODE = 9109

		/** How long the account list is reused by new connections. */
		private val ACCOUNTS_MAX_AGE = Duration.ofMinutes(1)

		/** Path Cloudflare sends the user back to. */
		private const val CALLBACK_PATH = "/callback"

		/** How long before the access token ends that the driver gets a new one. */
		private val REFRESH_EARLY = Duration.ofMinutes(1)

		/** How long to wait for another program to finish with the sign-in file. */
		private val FILE_LOCK_WAIT = Duration.ofSeconds(45)

		/** How often to check whether the sign-in file is free. */
		private val FILE_LOCK_POLL = Duration.ofMillis(50)

		/** How long to wait for the token endpoint. */
		private val TOKEN_TIMEOUT = Duration.ofSeconds(30)

		/**
		 * Shortest access token lifetime the driver accepts, so refreshes can't run on every call.
		 */
		private const val MIN_LIFETIME_SECONDS = 300L

		/** Longest access token lifetime the driver accepts. */
		private const val MAX_LIFETIME_SECONDS = 86_400L

		/**
		 * Access token lifetime to assume when Cloudflare doesn't say, matching what it sends
		 * today.
		 */
		private const val DEFAULT_LIFETIME_SECONDS = 3599L

		/** Most characters of an unreadable reply to show in an error. */
		private const val MAX_DETAIL_LENGTH = 200

		/** One sign-in per token file and client, so connections in one program share it. */
		private val shared = ConcurrentHashMap<Pair<Path, String>, SignInCredentials>()

		private val random = SecureRandom()

		/**
		 * Returns the sign-in shared by every connection in this program that uses the same file.
		 *
		 * @param settings sign-in settings
		 * @param openBrowser opens a web page in the user's browser
		 * @return the shared sign-in
		 */
		fun shared(settings: SignInSettings, openBrowser: (URI) -> Unit): SignInCredentials {
			val key = settings.tokenFile.toAbsolutePath().normalize() to settings.clientId
			return shared.computeIfAbsent(key) { SignInCredentials(settings, openBrowser) }
		}

		/**
		 * Answers Cloudflare's redirect in the browser and passes its query on.
		 *
		 * @param exchange the browser's request
		 * @param state the state this sign-in sent
		 * @param reply completes with the query when it belongs to this sign-in
		 */
		private fun answerCallback(exchange: HttpExchange, state: String, reply: CompletableFuture<Map<String, String>>) {
			// A malformed reply must not stop the listener, so any failure gets a plain error page.
			try {
				answerCallbackOrThrow(exchange, state, reply)
			} catch (error: Exception) {
				runCatching { exchange.sendResponseHeaders(400, -1) }
			} finally {
				exchange.close()
			}
		}

		/**
		 * Answers Cloudflare's redirect, throwing on a malformed request.
		 *
		 * @param exchange the browser's request
		 * @param state the state this sign-in sent
		 * @param reply completes with the query when it belongs to this sign-in
		 * @throws IllegalArgumentException when the query isn't valid URL text
		 * @throws IOException when the browser goes away
		 */
		private fun answerCallbackOrThrow(exchange: HttpExchange, state: String, reply: CompletableFuture<Map<String, String>>) {
			val query = queryValues(exchange.requestURI.rawQuery.orEmpty())
			val matches = query["state"] == state
			val (title, detail) = when {
				!matches -> "Sign-in not recognised" to "This reply doesn't belong to the sign-in that the D1 driver started, so it was ignored."
				query["error"] != null -> "Sign-in not finished" to "You can close this tab and connect again to retry."
				else -> "You're signed in" to "You can close this tab and go back to your database tool."
			}
			val bytes = callbackPage(title, detail, success = matches && query["error"] == null).toByteArray()
			exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
			exchange.sendResponseHeaders(if (matches) 200 else 400, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
			if (matches) {
				reply.complete(query)
			}
		}

		/**
		 * Builds the page the browser shows after Cloudflare sends the user back.
		 *
		 * @param title the page heading
		 * @param detail one sentence under the heading
		 * @param success whether to show a tick or a cross
		 * @return the HTML
		 */
		private fun callbackPage(title: String, detail: String, success: Boolean): String {
			val mark = if (success) "&#10003;" else "&#10005;"
			val markColour = if (success) "#16a34a" else "#dc2626"
			return """
				<!doctype html>
				<html lang="en">
				<meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<title>$title · D1 JDBC Connector</title>
				<style>
					:root { color-scheme: light dark; }
					body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f4f4f5;
						font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: #18181b; }
					main { max-width: 26rem; margin: 1.5rem; padding: 2.5rem 2rem; text-align: center; background: #fff;
						border: 1px solid #e4e4e7; border-radius: 12px; box-shadow: 0 1px 3px rgb(0 0 0 / 0.06); }
					.mark { width: 3rem; height: 3rem; margin: 0 auto 1.25rem; border-radius: 50%; display: grid;
						place-items: center; font-size: 1.4rem; color: #fff; background: $markColour; }
					h1 { margin: 0 0 0.5rem; font-size: 1.25rem; font-weight: 600; }
					p { margin: 0; color: #52525b; }
					footer { margin-top: 1.75rem; font-size: 0.8rem; color: #a1a1aa; }
					@media (prefers-color-scheme: dark) {
						body { background: #18181b; color: #f4f4f5; }
						main { background: #27272a; border-color: #3f3f46; box-shadow: none; }
						p { color: #a1a1aa; }
						footer { color: #71717a; }
					}
				</style>
				<main>
					<div class="mark">$mark</div>
					<h1>$title</h1>
					<p>$detail</p>
					<footer>D1 JDBC Connector</footer>
				</main>
				</html>
			""".trimIndent()
		}

		/**
		 * Splits a raw URL query into values.
		 *
		 * @param query raw text after the `?`
		 * @return values by name
		 */
		private fun queryValues(query: String): Map<String, String> {
			return query.split("&").filter { it.isNotEmpty() }.associate {
				URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8) to
					URLDecoder.decode(it.substringAfter("=", ""), Charsets.UTF_8)
			}
		}

		/**
		 * Encodes values as form or query text.
		 *
		 * @param values values by name
		 * @return the encoded text
		 */
		private fun formText(values: Map<String, String>): String {
			return values.entries.joinToString("&") {
				URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
			}
		}

		/**
		 * Makes 32 random bytes as URL-safe text, for the code verifier and state.
		 *
		 * @return the text
		 */
		private fun randomText(): String {
			return base64Url(ByteArray(32).also { random.nextBytes(it) })
		}

		/**
		 * Encodes bytes as URL-safe base64 with no padding, as PKCE asks.
		 *
		 * @param bytes bytes to encode
		 * @return the text
		 */
		private fun base64Url(bytes: ByteArray): String {
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
		}

		/**
		 * Lets only the file's owner read and write it, where the file system supports that.
		 *
		 * @param file the file
		 * @throws IOException when the permissions can't be set
		 */
		private fun restrictToOwner(file: Path) {
			if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
			}
		}
	}
}

/**
 * Thrown when Cloudflare refuses a code or refresh token, so the user must sign in again.
 */
internal class SignInEndedException : Exception()

/**
 * Opens a web page in the user's browser, using the desktop when Java can reach it, or the system's
 * own open command when it can't.
 *
 * @param page the page address
 * @throws IOException when no browser can be opened
 */
internal fun openInBrowser(page: URI) {
	// IDEs pass a driver's desktop browse call on to their own browser opener.
	if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
		Desktop.getDesktop().browse(page)
		return
	}
	val os = System.getProperty("os.name").lowercase()
	val command = when {
		"win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", page.toString())
		"mac" in os -> listOf("open", page.toString())
		else -> listOf("xdg-open", page.toString())
	}
	ProcessBuilder(command).inheritIO().start()
}
