package nz.co.simontong.d1jdbc

import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests the driver through the standard JDBC interface, the way an IDE uses it.
 */
class D1DriverTest {
	private val server = StubD1Server()

	/** Stops the stub server after each test. */
	@AfterTest
	fun stopServer() {
		server.close()
	}

	/**
	 * Opens a connection to the stub server through DriverManager.
	 *
	 * @param url JDBC URL to connect to
	 * @param extra extra connection properties
	 * @return the open connection
	 */
	private fun connect(url: String = "jdbc:d1://acc123/db456", extra: Map<String, String> = emptyMap()): Connection {
		return server.connect(url, extra)
	}

	/** Checks that the driver runs a query and reads its rows. */
	@Test
	fun `runs a query and reads its rows`() {
		server.respond(200, """
			{"result":[{"results":{"columns":["id","name","score"],"rows":[[1,"a",2.5],[2,null,3]]},"success":true,
			"meta":{"changes":0,"last_row_id":0}}],"errors":[],"messages":[],"success":true}
		""")

		connect().use { connection ->
			connection.createStatement().use { statement ->
				val rows = statement.executeQuery("select id, name, score from t")

				val metaData = rows.metaData
				assertEquals(3, metaData.columnCount)
				assertEquals(listOf("id", "name", "score"), (1..3).map { metaData.getColumnLabel(it) })
				assertTrue(rows.next())
				assertEquals(1L, rows.getLong("id"))
				assertEquals("a", rows.getString(2))
				assertEquals(2.5, rows.getDouble("score"))
				assertTrue(rows.next())
				assertEquals(2, rows.getInt(1))
				assertNull(rows.getString("name"))
				assertTrue(rows.wasNull())
				assertEquals(3.0, rows.getDouble(3))
				assertFalse(rows.next())
			}
		}

		val request = server.requests.single()
		assertEquals("/client/v4/accounts/acc123/d1/database/db456/raw", request.path)
		assertEquals("Bearer secret-token", request.authorization)
		assertEquals(
			Json.parseToJsonElement("""{"sql":"select id, name, score from t","params":[]}"""),
			Json.parseToJsonElement(request.body),
		)
	}

	/** Checks that the driver leaves other drivers' URLs alone. */
	@Test
	fun `ignores URLs for other databases`() {
		val driver = D1Driver()

		assertFalse(driver.acceptsURL("jdbc:sqlite:test.db"))
		assertNull(driver.connect("jdbc:sqlite:test.db", Properties()))
		assertTrue(driver.acceptsURL("jdbc:d1://acc123/db456"))
	}

	/** Checks that the driver explains the URL format when the URL is malformed. */
	@Test
	fun `explains the URL format when the URL is malformed`() {
		val error = assertFailsWith<SQLNonTransientConnectionException> {
			connect(url = "jdbc:d1://acc123")
		}

		assertEquals("The D1 URL must look like jdbc:d1://<account id>/<database id>.", error.message)
		assertTrue(server.requests.isEmpty())
	}

	/** Checks that the driver asks for the token when none is given. */
	@Test
	fun `asks for the token when none is given`() {
		val error = assertFailsWith<SQLException> {
			DriverManager.getConnection("jdbc:d1://acc123/db456", Properties())
		}

		assertEquals("No Cloudflare API token was given. Enter it as the connection password.", error.message)
	}

	/** Checks that the driver lists the settings an IDE should ask for. */
	@Test
	fun `lists the settings an IDE should ask for`() {
		val settings = D1Driver().getPropertyInfo("jdbc:d1://acc123/db456", Properties())

		assertEquals(listOf("password", "readOnly"), settings.map { it.name })
		assertTrue(settings.first().required)
	}

	/** Checks that the driver returns the changed-row count for an update. */
	@Test
	fun `returns the changed row count for an update`() {
		server.respond(200, """
			{"result":[{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":3,"last_row_id":3}}],
			"errors":[],"messages":[],"success":true}
		""")

		val changed = connect().use { it.createStatement().executeUpdate("update t set name = 'x'") }

		assertEquals(3, changed)
	}

	/** Checks that the driver refuses to treat an update as a query. */
	@Test
	fun `refuses to treat an update as a query`() {
		server.respond(200, """
			{"result":[{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":1}}],
			"errors":[],"messages":[],"success":true}
		""")

		val error = assertFailsWith<SQLException> {
			connect().use { it.createStatement().executeQuery("delete from t where id = 1") }
		}

		assertEquals("The SQL did not return rows. Use executeUpdate or execute instead.", error.message)
	}

	/** Checks that the driver walks through every result of SQL with several statements. */
	@Test
	fun `walks through every result of several statements`() {
		server.respond(200, """
			{"result":[
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":2,"last_row_id":2}},
			{"results":{"columns":["n"],"rows":[[2]]},"success":true,"meta":{"changes":0,"last_row_id":2}}
			],"errors":[],"messages":[],"success":true}
		""")

		connect().use { connection ->
			val statement = connection.createStatement()

			assertFalse(statement.execute("insert into t values (1), (2); select count(*) as n from t"))
			assertNull(statement.resultSet)
			assertEquals(2, statement.updateCount)
			assertTrue(statement.moreResults)
			assertEquals(-1, statement.updateCount)
			val rows = statement.resultSet
			assertTrue(rows.next())
			assertEquals(2, rows.getInt("n"))
			assertFalse(statement.moreResults)
			assertEquals(-1, statement.updateCount)
			assertTrue(rows.isClosed)
		}
	}

	/** Checks that the driver applies the statement's query time limit. */
	@Test
	fun `applies the query time limit`() {
		server.respond(200, "{}", delayMillis = 2_000)

		val error = assertFailsWith<SQLTimeoutException> {
			connect().use { connection ->
				val statement = connection.createStatement()
				statement.queryTimeout = 1
				statement.execute("select 1")
			}
		}

		assertEquals("Cloudflare did not reply within 1 second.", error.message)
	}

	/** Checks that the driver stays in auto-commit and warns when asked to turn it off. */
	@Test
	fun `stays in auto-commit and warns when asked to turn it off`() {
		connect().use { connection ->
			connection.autoCommit = false

			assertTrue(connection.autoCommit)
			assertEquals(
				"D1 has no transactions, so every statement is saved as soon as it runs.",
				connection.warnings.message,
			)
			connection.commit()
		}
	}

	/** Checks that the driver says changes can't be undone when asked to roll back. */
	@Test
	fun `says changes can't be undone when asked to roll back`() {
		val error = assertFailsWith<SQLException> {
			connect().use { it.rollback() }
		}

		assertEquals(
			"D1 has no transactions, so changes were saved as each statement ran and can't be rolled back.",
			error.message,
		)
	}

	/** Checks that a read-only connection blocks writes before they reach D1. */
	@Test
	fun `blocks writes on a read-only connection`() {
		val writes = listOf(
			"insert into t values (1)",
			"  UPDATE t SET a = 1",
			"delete from t",
			"replace into t values (1)",
			"create table x (id integer)",
			"drop table t",
			"alter table t add column b",
			"with old as (select id from t) delete from t where id in old",
			"select 1; drop table t",
			"pragma foreign_keys = off",
			"/* note */ insert into t values (1)",
			"vacuum",
		)

		connect(extra = mapOf("readOnly" to "true")).use { connection ->
			assertTrue(connection.isReadOnly)
			for (sql in writes) {
				val error = assertFailsWith<SQLException>(sql) { connection.createStatement().execute(sql) }
				assertEquals("This connection is read-only, so statements that change data are blocked.", error.message)
				assertEquals("25006", error.sqlState)
			}
		}

		assertTrue(server.requests.isEmpty())
	}

	/** Checks that a read-only connection still runs reads. */
	@Test
	fun `runs reads on a read-only connection`() {
		server.respond(200, """
			{"result":[{"results":{"columns":["a"],"rows":[]},"success":true,"meta":{"changes":0,"last_row_id":0}}],
			"errors":[],"messages":[],"success":true}
		""")
		val reads = listOf(
			"select * from t",
			"SELECT 'delete from t' AS note",
			"select \"update\" from t",
			"select 1 -- drop table t",
			"with x as (select 1) select * from x",
			"pragma table_info(t)",
			"pragma table_list",
			"explain query plan select * from t",
			"values (1)",
		)

		connect(url = "jdbc:d1://acc123/db456?readOnly=true").use { connection ->
			for (sql in reads) {
				connection.createStatement().execute(sql)
			}
		}

		assertEquals(reads.size, server.requests.size)
	}

	/** Checks that the IDE can't turn read-only off when the connection settings ask for it. */
	@Test
	fun `lets the IDE turn read-only on but not off when the settings ask for it`() {
		connect().use { connection ->
			assertFalse(connection.isReadOnly)
			connection.isReadOnly = true
			assertTrue(connection.isReadOnly)
			connection.isReadOnly = false
			assertFalse(connection.isReadOnly)
		}

		connect(extra = mapOf("readOnly" to "true")).use { connection ->
			connection.isReadOnly = false

			assertTrue(connection.isReadOnly)
			assertEquals(
				"The connection settings make this connection read-only, so it stays read-only.",
				connection.warnings.message,
			)
		}
	}

	/** Checks that the driver sends a batch in one request and returns each count. */
	@Test
	fun `sends a batch in one request and returns each count`() {
		server.respond(200, """
			{"result":[
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":1}},
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":4,"last_row_id":1}}
			],"errors":[],"messages":[],"success":true}
		""")

		val counts = connect().use { connection ->
			val statement = connection.createStatement()
			statement.addBatch("insert into t values (1)")
			statement.addBatch("update t set a = 2")
			statement.executeBatch()
		}

		assertEquals(listOf(1, 4), counts.toList())
		assertEquals(
			Json.parseToJsonElement("""
				{"batch":[{"sql":"insert into t values (1)","params":[]},{"sql":"update t set a = 2","params":[]}]}
			"""),
			Json.parseToJsonElement(server.requests.single().body),
		)
	}

	/** Checks that a failed batch says nothing was saved. */
	@Test
	fun `says nothing was saved when a batch fails`() {
		server.respond(400, """
			{"messages":[],"result":[],"success":false,"errors":[{"code":7500,
			"message":"UNIQUE constraint failed: t.id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)"}]}
		""")

		val error = assertFailsWith<BatchUpdateException> {
			connect().use { connection ->
				val statement = connection.createStatement()
				statement.addBatch("insert into t values (1)")
				statement.addBatch("insert into t values (1)")
				statement.executeBatch()
			}
		}

		assertEquals(
			"The batch failed and D1 saved none of it. "
				+ "UNIQUE constraint failed: t.id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)",
			error.message,
		)
		assertEquals("23000", error.sqlState)
		assertEquals(0, error.updateCounts.size)
	}

	/** Checks that an empty batch sends nothing. */
	@Test
	fun `sends nothing for an empty batch`() {
		val counts = connect().use { it.createStatement().executeBatch() }

		assertEquals(0, counts.size)
		assertTrue(server.requests.isEmpty())
	}

	/** Checks that the driver refuses work on a closed connection. */
	@Test
	fun `refuses work on a closed connection`() {
		val connection = connect()
		val statement = connection.createStatement()
		connection.close()
		connection.close()

		assertTrue(connection.isClosed)
		val error = assertFailsWith<SQLNonTransientConnectionException> { statement.execute("select 1") }
		assertEquals("The connection is closed.", error.message)
		assertFailsWith<SQLNonTransientConnectionException> { connection.createStatement() }
		assertTrue(server.requests.isEmpty())
	}

	/** Checks that the driver confirms a live connection by running a query. */
	@Test
	fun `confirms a live connection by running a query`() {
		server.respond(200, """
			{"result":[{"results":{"columns":["1"],"rows":[[1]]},"success":true,"meta":{"changes":0,"last_row_id":0}}],
			"errors":[],"messages":[],"success":true}
		""")

		connect().use { connection ->
			assertTrue(connection.isValid(5))
			assertEquals(
				Json.parseToJsonElement("""{"sql":"select 1","params":[]}"""),
				Json.parseToJsonElement(server.requests.single().body),
			)

			server.respond(401, """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""")
			assertFalse(connection.isValid(5))
			assertFailsWith<SQLException> { connection.isValid(-1) }
		}
	}

	/** Checks that the driver describes D1 to the IDE. */
	@Test
	fun `describes D1 to the IDE`() {
		connect(extra = mapOf("readOnly" to "true")).use { connection ->
			val metaData = connection.metaData

			assertEquals("Cloudflare D1", metaData.databaseProductName)
			assertEquals("jdbc:d1://acc123/db456", metaData.url)
			assertTrue(metaData.isReadOnly)
			assertFalse(metaData.supportsTransactions())
			assertEquals(Connection.TRANSACTION_NONE, metaData.defaultTransactionIsolation)
			assertTrue(metaData.supportsBatchUpdates())

			val schemas = metaData.schemas
			assertTrue(schemas.next())
			assertEquals("main", schemas.getString("TABLE_SCHEM"))
			assertNull(schemas.getString("TABLE_CATALOG"))
			assertFalse(schemas.next())

			val tableTypes = metaData.tableTypes
			val types = generateSequence { if (tableTypes.next()) tableTypes.getString(1) else null }.toList()
			assertEquals(listOf("TABLE", "VIEW"), types)

			val tables = metaData.getTables(null, null, "%", null)
			assertEquals(10, tables.metaData.columnCount)
			assertEquals("TABLE_NAME", tables.metaData.getColumnLabel(3))
		}
		assertTrue(server.requests.isEmpty())
	}
}
