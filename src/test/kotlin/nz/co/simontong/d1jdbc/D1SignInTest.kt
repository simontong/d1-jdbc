package nz.co.simontong.d1jdbc

import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the Cloudflare sign-in against stub sign-in and API servers.
 */
class D1SignInTest {
	private val api = StubD1Server()
	private val signIn = StubSignInServer()
	private val folder: Path = createTempDirectory("d1-sign-in")
	private val tokenFile: Path = folder.resolve("sign-in.json")

	/** Sign-in pages the fake browser was asked to open. */
	private val opened = mutableListOf<URI>()

	/** Stops the servers and removes the temporary folder after each test. */
	@AfterTest
	fun stop() {
		api.close()
		signIn.close()
		folder.toFile().deleteRecursively()
	}

	/**
	 * Reads the query values of an address.
	 *
	 * @param uri the address
	 * @return values by name
	 */
	private fun query(uri: URI): Map<String, String> {
		return uri.rawQuery.split("&").associate {
			URLDecoder.decode(it.substringBefore("="), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
		}
	}

	/**
	 * Makes a fake browser that answers the sign-in page by sending the user straight back.
	 *
	 * @param reply builds the values to send back from the sign-in page's values
	 * @return the fake browser
	 */
	private fun browser(reply: (Map<String, String>) -> Map<String, String> = { mapOf("code" to StubSignInServer.GOOD_CODE, "state" to it.getValue("state")) }): (URI) -> Unit {
		return { page ->
			opened += page
			val values = reply(query(page))
			val back = query(page).getValue("redirect_uri") + "?" + values.entries.joinToString("&") { "${it.key}=${it.value}" }
			HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(back)).build(), HttpResponse.BodyHandlers.discarding())
		}
	}

	/**
	 * Makes sign-in credentials that use the stub servers.
	 *
	 * @param openBrowser fake browser
	 * @param wait how long to wait for the browser
	 * @return the credentials
	 */
	private fun credentials(
		openBrowser: (URI) -> Unit = browser(),
		wait: Duration = Duration.ofSeconds(10),
		clock: Clock = Clock.systemUTC(),
	): SignInCredentials {
		return SignInCredentials(SignInSettings(SignInCredentials.CLIENT_ID, signIn.baseUri, tokenFile, wait), openBrowser, clock)
	}

	/**
	 * Opens a signed-in connection through the driver.
	 *
	 * @param url JDBC URL
	 * @return the connection
	 */
	private fun connect(url: String = "jdbc:d1://?auth=browser"): Connection {
		val properties = Properties()
		properties["apiBaseUrl"] = api.baseUri.toString()
		properties["signInBaseUrl"] = signIn.baseUri.toString()
		properties["tokenFile"] = tokenFile.toString()
		return D1Driver().signedInConnection(D1Settings.parse(url, properties), browser())
	}

	/** Checks the sign-in page gets PKCE, state and scopes, and the token request names the driver. */
	@Test
	fun `signs in with PKCE and saves the tokens for this user only`() {
		val token = credentials().token()

		assertEquals("access-1", token)
		val page = query(opened.single())
		assertEquals("code", page["response_type"])
		assertEquals(SignInCredentials.CLIENT_ID, page["client_id"])
		assertEquals("d1.read d1.write account-settings.read offline_access", page["scope"])
		assertEquals("S256", page["code_challenge_method"])
		assertTrue(page.getValue("redirect_uri").matches(Regex("""http://127\.0\.0\.1:\d+/callback""")))
		assertTrue(page.getValue("state").length >= 32)

		val exchange = signIn.requests.single()
		val verifier = exchange.form.getValue("code_verifier")
		val challenge = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
		assertEquals(page["code_challenge"], challenge)
		assertEquals(page["redirect_uri"], exchange.form["redirect_uri"])
		assertTrue(exchange.userAgent.orEmpty().startsWith("d1-jdbc/"))

		assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile)))
		assertFalse(Files.readString(tokenFile).contains("code_verifier"))
	}

	/** Checks a saved sign-in is reused by a new connection without opening the browser. */
	@Test
	fun `reuses the saved sign-in`() {
		credentials().token()
		opened.clear()

		assertEquals("access-1", credentials().token())
		assertEquals(emptyList(), opened)
	}

	/** Checks an access token near its end is swapped for a new one, and the new refresh token is saved. */
	@Test
	fun `refreshes the token before it ends`() {
		val start = Instant.parse("2026-10-31T00:00:00Z")
		var now = start
		val credentials = credentials(clock = object : Clock() {
			override fun getZone(): ZoneId = ZoneOffset.UTC
			override fun withZone(zone: ZoneId): Clock = this
			override fun instant(): Instant = now
		})
		assertEquals("access-1", credentials.token())
		now = start.plusSeconds(3500)
		assertEquals("access-1", credentials.token())

		now = start.plusSeconds(3560)
		assertEquals("access-2", credentials.token())
		assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-1"), signIn.requests.last().form - "client_id")
		assertTrue(Files.readString(tokenFile).contains("refresh-2"))
	}

	/** Checks a query Cloudflare turns down is sent once more with a new token. */
	@Test
	fun `renews the token once when Cloudflare turns it down`() {
		connect().use { connection ->
			api.respondWithRows("""["one"]""", "[[1]]")
			api.acceptedTokens = setOf("access-2")

			connection.createStatement().executeQuery("select 1")

			// The database list goes first, gets turned down, then works with the new token.
			assertEquals(listOf("Bearer access-1", "Bearer access-2", "Bearer access-2"), api.authorizations.takeLast(3))
		}
	}

	/** Checks a refresh token Cloudflare no longer accepts deletes the saved sign-in and asks for a new one. */
	@Test
	fun `asks to sign in again when the sign-in has ended`() {
		signIn.expiresIn = 30
		val credentials = credentials()
		credentials.token()
		signIn.failure = 400 to """{"error":"invalid_grant","error_description":"revoked"}"""

		val error = assertFailsWith<SQLException> { credentials.token() }

		assertEquals("28000", error.sqlState)
		assertEquals("Your Cloudflare sign-in has ended, as it was revoked or not used for too long. Connect again to sign in.", error.message)
		assertFalse(Files.exists(tokenFile))
	}

	/** Checks pressing Cancel on Cloudflare's page gives a clear message. */
	@Test
	fun `reports a cancelled sign-in`() {
		val cancel = browser { mapOf("error" to "access_denied", "state" to it.getValue("state")) }

		val error = assertFailsWith<SQLException> { credentials(cancel).token() }

		assertEquals("The Cloudflare sign-in was cancelled. Connect again to retry.", error.message)
		assertFalse(Files.exists(tokenFile))
	}

	/** Checks a reply with the wrong state is ignored, so the sign-in times out. */
	@Test
	fun `ignores a reply for another sign-in and times out`() {
		val forged = browser { mapOf("code" to StubSignInServer.GOOD_CODE, "state" to "someone-else") }

		val error = assertFailsWith<SQLException> { credentials(forged, Duration.ofSeconds(2)).token() }

		assertEquals("HYT00", error.sqlState)
		assertEquals("The Cloudflare sign-in wasn't finished within 2 seconds. Connect again to retry.", error.message)
		assertEquals(emptyList(), signIn.requests)
	}

	/** Checks a computer with no browser gets told to use an API token. */
	@Test
	fun `reports when no browser can be opened`() {
		val error = assertFailsWith<SQLException> { credentials({ throw java.io.IOException("no browser") }).token() }

		assertEquals("Could not open a browser to sign in with Cloudflare. Use an API token on this computer instead.", error.message)
	}

	/** Checks an error from the token endpoint, such as Cloudflare's block page, is shown with its status. */
	@Test
	fun `reports a failed token request`() {
		signIn.failure = 403 to "error code: 1010"

		val error = assertFailsWith<SQLException> { credentials().token() }

		assertEquals("Cloudflare's sign-in failed with HTTP 403: error code: 1010", error.message)
	}

	/** Checks every ticked account's databases show, each named with its account. */
	@Test
	fun `lists the databases of every ticked account`() {
		api.accounts = listOf("acc-a" to "Alpha", "acc-b" to "Beta")
		api.databasesByAccount = mapOf("acc-a" to listOf("id-1" to "shop"), "acc-b" to listOf("id-2" to "blog"))

		connect().use { connection ->
			val catalogs = connection.metaData.catalogs
			val names = generateSequence { if (catalogs.next()) catalogs.getString(1) else null }.toList()
			assertEquals(listOf("Alpha / shop", "Beta / blog"), names)

			api.respondWithRows("""["one"]""", "[[1]]")
			connection.catalog = "Beta / blog"
			connection.createStatement().executeQuery("select 1")
			assertEquals("/client/v4/accounts/acc-b/d1/database/id-2/raw", api.requests.last().path)
		}
	}

	/** Checks an account ID in the URL limits the connection to that account. */
	@Test
	fun `keeps to the account in the URL`() {
		api.accounts = listOf("acc-a" to "Alpha", "acc-b" to "Beta")
		api.databasesByAccount = mapOf("acc-a" to listOf("id-1" to "shop"), "acc-b" to listOf("id-2" to "blog"))

		connect("jdbc:d1://acc-b?auth=browser").use { connection ->
			assertEquals("blog", connection.catalog)
		}
		connect("jdbc:d1://all?auth=browser").use { connection ->
			assertEquals("Alpha / shop", connection.catalog)
		}
		val error = assertFailsWith<SQLException> { connect("jdbc:d1://acc-c?auth=browser") }
		assertEquals("28000", error.sqlState)
		assertTrue(error.message.orEmpty().startsWith("The Cloudflare sign-in doesn't include account acc-c."))
	}

	/** Checks a connection that may not open the browser asks for a sign-in instead, then uses a saved one. */
	@Test
	fun `waits for a sign-in when the browser may not open`() {
		val error = assertFailsWith<SQLException> { connect("jdbc:d1://?auth=browser&openBrowser=false") }
		assertEquals("You need to sign in with Cloudflare. Connect to this data source to open the sign-in page.", error.message)
		assertEquals(emptyList(), opened)

		connect().close()
		connect("jdbc:d1://?auth=browser&openBrowser=false").close()
		assertEquals(1, opened.size)
	}

	/** Checks an API token with "all" in place of an account ID says to give the ID. */
	@Test
	fun `asks for the account ID when an API token has none`() {
		val error = assertFailsWith<SQLException> { api.connect("jdbc:d1://all") }

		assertEquals(
			"An API token needs your Cloudflare account ID. Put it in the Account field, or in the URL like "
				+ "jdbc:d1://<account id>.",
			error.message,
		)
	}

	/** Checks a sign-in with no ticked accounts says what to do. */
	@Test
	fun `reports a sign-in that reaches no accounts`() {
		api.accounts = emptyList()

		val error = assertFailsWith<SQLException> { connect() }

		assertTrue(error.message.orEmpty().startsWith("The Cloudflare sign-in reaches no accounts."))
	}

	/** Checks a sign-in without D1 write permission makes the connection read-only, with a warning. */
	@Test
	fun `makes the connection read-only without write permission`() {
		signIn.scope = "d1.read account-settings.read offline_access"

		connect().use { connection ->
			assertTrue(connection.isReadOnly)
			assertTrue(connection.warnings.message.orEmpty().startsWith("You signed in without D1 write permission"))
			val error = assertFailsWith<SQLException> { connection.createStatement().executeUpdate("delete from item") }
			assertNull(error.message?.let { Regex("(?i)\\b(access|denied|password|user)\\b").find(it) })
		}
	}
}
