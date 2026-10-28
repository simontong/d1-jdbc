package nz.co.simontong.d1jdbc

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.sql.Date
import java.sql.ParameterMetaData
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests how the driver sends and reads values, through the standard JDBC interface.
 */
class D1ValuesTest {
	private val server = StubD1Server()

	/** Stops the stub server after each test. */
	@AfterTest
	fun stopServer() {
		server.close()
	}

	/**
	 * Parses the body of the only request the server received.
	 *
	 * @return the request body as JSON
	 */
	private fun sentJson() = Json.parseToJsonElement(server.requests.single().body)

	/** Checks that the driver sends each kind of value in the form D1 stores correctly. */
	@Test
	fun `sends each kind of value in the form D1 stores correctly`() {
		server.respondWithRows(changes = 1)

		server.connect().use { connection ->
			val statement = connection.prepareStatement("insert into t values (?, ?, ?, ?, ?, ?, ?)")
			statement.setLong(1, Long.MAX_VALUE)
			statement.setString(2, "hello")
			statement.setDouble(3, 2.5)
			statement.setBoolean(4, true)
			statement.setBytes(5, byteArrayOf(1, 2, -1))
			statement.setNull(6, java.sql.Types.VARCHAR)
			statement.setInt(7, -3)

			assertEquals(1, statement.executeUpdate())
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"insert into t values (cast(?1 as integer), ?, ?, cast(?4 as integer), ?, ?, cast(?7 as integer))",
				"params":["9223372036854775807","hello",2.5,"1",[1,2,255],null,"-3"]}
			"""),
			sentJson(),
		)
	}

	/** Checks that numbered placeholders share a value and quoted question marks are left alone. */
	@Test
	fun `numbers placeholders the way SQLite does`() {
		server.respondWithRows(columns = """["a"]""")

		server.connect().use { connection ->
			val statement = connection.prepareStatement(
				"select ?2, '?' as q, \"?\", ?1, ? /* ? */ -- ?\nwhere ?2 > 0",
			)
			statement.setString(1, "one")
			statement.setLong(2, 5)
			statement.setString(3, "three")
			statement.executeQuery()
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"select cast(?2 as integer), '?' as q, \"?\", ?1, ? /* ? */ -- ?\nwhere cast(?2 as integer) > 0",
				"params":["one","5","three"]}
			"""),
			sentJson(),
		)
	}

	/** Checks that the driver names a missing value or a parameter that doesn't exist. */
	@Test
	fun `names a missing value or a parameter that doesn't exist`() {
		server.connect().use { connection ->
			val statement = connection.prepareStatement("select ?, ?")
			statement.setString(1, "a")

			val missing = assertFailsWith<SQLException> { statement.executeQuery() }
			assertEquals("Parameter 2 has no value. Set every parameter before running the SQL.", missing.message)
			assertEquals("07001", missing.sqlState)

			val outOfRange = assertFailsWith<SQLException> { statement.setString(3, "c") }
			assertEquals("There is no parameter 3. The SQL has 2.", outOfRange.message)
			assertEquals("07009", outOfRange.sqlState)

			statement.setString(2, "b")
			statement.clearParameters()
			assertFailsWith<SQLException> { statement.executeQuery() }
		}

		assertTrue(server.requests.isEmpty())
	}

	/** Checks that dates and times are sent as text in the formats SQLite's date functions read. */
	@Test
	fun `sends dates and times in SQLite's formats`() {
		server.respondWithRows(changes = 1)
		val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

		server.connect().use { connection ->
			val statement = connection.prepareStatement("insert into t values (?, ?, ?, ?, ?, ?)")
			statement.setDate(1, Date.valueOf("2026-10-29"))
			statement.setTime(2, Time.valueOf("10:11:12"))
			statement.setTimestamp(3, Timestamp.valueOf("2026-10-29 10:11:12.345"))
			statement.setTimestamp(4, Timestamp.valueOf("2026-10-29 10:11:12"))
			statement.setTimestamp(5, Timestamp.from(Instant.parse("2026-10-29T10:11:12Z")), utc)
			statement.setTimestamp(6, null)
			statement.executeUpdate()
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"insert into t values (?, ?, ?, ?, ?, ?)",
				"params":["2026-10-29","10:11:12","2026-10-29 10:11:12.345","2026-10-29 10:11:12","2026-10-29 10:11:12",null]}
			"""),
			sentJson(),
		)
	}

	/** Checks that setObject sends each Java type the same way as its own setter. */
	@Test
	fun `sends each Java type through setObject`() {
		server.respondWithRows(changes = 1)
		val values = listOf<Any?>(
			42,
			7L,
			BigInteger("-9223372036854775808"),
			true,
			BigInteger("123456789012345678901234567890"),
			2.5,
			1.5f,
			BigDecimal("1.10"),
			"text",
			'c',
			byteArrayOf(9),
			null,
			LocalDate.parse("2026-10-29"),
			LocalTime.parse("10:11:12.5"),
			LocalDateTime.parse("2026-10-29T10:11:12"),
			Instant.parse("2026-10-29T10:11:12Z"),
			OffsetDateTime.parse("2026-10-29T10:11:12+13:00"),
		)

		server.connect().use { connection ->
			val statement = connection.prepareStatement("select " + values.joinToString(", ") { "?" })
			values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
			statement.execute()
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"select cast(?1 as integer), cast(?2 as integer), cast(?3 as integer), cast(?4 as integer), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?",
				"params":["42","7","-9223372036854775808","1",123456789012345678901234567890,2.5,1.5,1.10,"text","c",[9],null,
				"2026-10-29","10:11:12.5","2026-10-29 10:11:12","2026-10-29 10:11:12","2026-10-29 10:11:12+13:00"]}
			"""),
			sentJson(),
		)
	}

	/** Checks that setObject with a target SQL type converts the value to that type first. */
	@Test
	fun `converts values to the target SQL type`() {
		server.respondWithRows(changes = 1)

		server.connect().use { connection ->
			val statement = connection.prepareStatement("select ?, ?, ?, ?, ?, ?")
			statement.setObject(1, "5", Types.INTEGER)
			statement.setObject(2, 3, Types.VARCHAR)
			statement.setObject(3, "2.5", Types.DOUBLE)
			statement.setObject(4, "true", Types.BOOLEAN)
			statement.setObject(5, 1.5, Types.DECIMAL, 2)
			statement.setObject(6, null, Types.BLOB)
			statement.execute()

			val error = assertFailsWith<SQLException> { statement.setObject(1, "five", Types.INTEGER) }
			assertEquals("Parameter 1 can't be sent as INTEGER: five", error.message)
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"select cast(?1 as integer), ?, ?, cast(?4 as integer), ?, ?","params":["5","3",2.5,"1",1.5,null]}
			"""),
			sentJson(),
		)
	}

	/** Checks that streams and readers are read in full and sent as bytes or text. */
	@Test
	fun `sends streams and readers in full`() {
		server.respondWithRows(changes = 1)

		server.connect().use { connection ->
			val statement = connection.prepareStatement("select ?, ?, ?, ?, ?, ?, ?")
			statement.setBinaryStream(1, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
			statement.setBinaryStream(2, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
			statement.setCharacterStream(3, StringReader("long text"))
			statement.setCharacterStream(4, StringReader("long text"), 4L)
			statement.setAsciiStream(5, ByteArrayInputStream("ascii".toByteArray()))
			statement.setClob(6, StringReader("clob"))
			statement.setURL(7, URI("https://example.com/a").toURL())
			statement.execute()
		}

		assertEquals(
			Json.parseToJsonElement("""
				{"sql":"select ?, ?, ?, ?, ?, ?, ?",
				"params":[[1,2,3],[1,2],"long text","long","ascii","clob","https://example.com/a"]}
			"""),
			sentJson(),
		)
	}

	/** Checks that a column's type comes from all its values, so every row reads alike. */
	@Test
	fun `reads a column as one type based on all its values`() {
		server.respondWithRows(
			columns = """["whole","decimal","text","bytes","mixed","empty"]""",
			rows = """[[1,1,"a",[1],1,null],[2,2.5,"b",[2],"x",null]]""",
		)

		server.connect().use { connection ->
			val rows = connection.createStatement().executeQuery("select * from t")
			val metaData = rows.metaData

			assertEquals(
				listOf(Types.BIGINT, Types.DOUBLE, Types.VARCHAR, Types.BLOB, Types.OTHER, Types.NULL),
				(1..6).map { metaData.getColumnType(it) },
			)
			assertEquals(
				listOf("INTEGER", "REAL", "TEXT", "BLOB", "ANY", "NULL"),
				(1..6).map { metaData.getColumnTypeName(it) },
			)
			assertEquals(
				listOf("java.lang.Long", "java.lang.Double", "java.lang.String", "[B", "java.lang.Object", "java.lang.Object"),
				(1..6).map { metaData.getColumnClassName(it) },
			)
			assertTrue(rows.next())
			assertEquals(1L, rows.getObject(1))
			assertEquals(1.0, rows.getObject(2))
			assertEquals(1L, rows.getObject(5))
			assertTrue(rows.next())
			assertEquals(2.5, rows.getObject(2))
			assertEquals("x", rows.getObject(5))
		}
	}

	/** Checks that dates and times stored as text in SQLite's formats read back as dates. */
	@Test
	fun `reads dates and times stored as text`() {
		server.respondWithRows(
			columns = """["d","ts","t","zoned","n"]""",
			rows = """[["2026-10-29","2026-10-29 10:11:12.345","10:11:12","2026-10-29T10:11:12+13:00",5]]""",
		)
		val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

		server.connect().use { connection ->
			val rows = connection.createStatement().executeQuery("select * from t")
			assertTrue(rows.next())

			assertEquals(Date.valueOf("2026-10-29"), rows.getDate("d"))
			assertEquals(Timestamp.valueOf("2026-10-29 00:00:00"), rows.getTimestamp("d"))
			assertEquals(Timestamp.valueOf("2026-10-29 10:11:12.345"), rows.getTimestamp("ts"))
			assertEquals(Instant.parse("2026-10-29T10:11:12.345Z"), rows.getTimestamp("ts", utc).toInstant())
			assertEquals(Date.valueOf("2026-10-29"), rows.getDate("ts"))
			assertEquals(Time.valueOf("10:11:12"), rows.getTime("t"))
			assertEquals(Time.valueOf("10:11:12").time + 345, rows.getTime("ts").time)
			assertEquals(Instant.parse("2026-10-28T21:11:12Z"), rows.getTimestamp("zoned").toInstant())

			assertEquals(LocalDate.parse("2026-10-29"), rows.getObject("d", LocalDate::class.java))
			assertEquals(LocalDateTime.parse("2026-10-29T10:11:12.345"), rows.getObject("ts", LocalDateTime::class.java))
			assertEquals(LocalTime.parse("10:11:12"), rows.getObject("t", LocalTime::class.java))
			assertEquals(Instant.parse("2026-10-29T10:11:12.345Z"), rows.getObject("ts", Instant::class.java))
			assertEquals(
				OffsetDateTime.parse("2026-10-29T10:11:12+13:00"),
				rows.getObject("zoned", OffsetDateTime::class.java),
			)

			val error = assertFailsWith<SQLException> { rows.getDate("n") }
			assertEquals("Column n can't be read as a date.", error.message)
			assertFailsWith<SQLException> { rows.getDate("t") }
		}
	}

	/** Checks that getObject with a class returns that class, and null for SQL NULL. */
	@Test
	fun `reads values as the class asked for`() {
		server.respondWithRows(columns = """["n","d","t","b","z"]""", rows = """[[42,2.5,"hi",[255],null]]""")

		server.connect().use { connection ->
			val rows = connection.createStatement().executeQuery("select * from t")
			assertTrue(rows.next())

			assertEquals(42, rows.getObject("n", Int::class.javaPrimitiveType))
			assertEquals(42L, rows.getObject("n", Long::class.javaObjectType))
			assertEquals(BigInteger("42"), rows.getObject("n", BigInteger::class.java))
			assertEquals("42", rows.getObject("n", String::class.java))
			assertEquals(true, rows.getObject("n", Boolean::class.javaObjectType))
			assertEquals(BigDecimal("2.5"), rows.getObject("d", BigDecimal::class.java))
			assertEquals(2.5f, rows.getObject("d", Float::class.javaObjectType))
			assertEquals(2.5, rows.getObject("d", Any::class.java))
			assertEquals("hi", rows.getObject("t", String::class.java))
			assertEquals(listOf<Byte>(-1), rows.getObject("b", ByteArray::class.java)?.toList())
			assertNull(rows.getObject("z", Long::class.javaObjectType))
			assertNull(rows.getObject("z", Int::class.javaPrimitiveType))

			val error = assertFailsWith<SQLException> { rows.getObject("n", UUID::class.java) }
			assertEquals("Not supported by the D1 driver: Reading java.util.UUID values.", error.message)
		}
	}

	/** Checks that setObject names a Java type the driver can't send. */
	@Test
	fun `names a Java type setObject can't send`() {
		server.connect().use { connection ->
			val statement = connection.prepareStatement("select ?")

			val error = assertFailsWith<SQLFeatureNotSupportedException> { statement.setObject(1, Any()) }

			assertEquals("Not supported by the D1 driver: Sending java.lang.Object values.", error.message)
		}
	}

	/** Checks that the driver says how many values the SQL needs, without asking D1. */
	@Test
	fun `says how many values the SQL needs`() {
		server.connect().use { connection ->
			val parameters = connection.prepareStatement("select ?3, ?").parameterMetaData

			assertEquals(4, parameters.parameterCount)
			assertEquals(ParameterMetaData.parameterModeIn, parameters.getParameterMode(4))
			assertEquals(ParameterMetaData.parameterNullableUnknown, parameters.isNullable(1))
			assertEquals(Types.OTHER, parameters.getParameterType(1))
			assertEquals("java.lang.Object", parameters.getParameterClassName(1))
			assertFailsWith<SQLException> { parameters.getParameterMode(5) }
		}

		assertTrue(server.requests.isEmpty())
	}

	/** Checks that a prepared batch sends each set of values in one request. */
	@Test
	fun `sends each set of values in a prepared batch`() {
		server.respond(200, """
			{"result":[
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":1}},
			{"results":{"columns":[],"rows":[]},"success":true,"meta":{"changes":1,"last_row_id":2}}
			],"errors":[],"messages":[],"success":true}
		""")

		val counts = server.connect().use { connection ->
			val statement = connection.prepareStatement("insert into t values (?, ?)")
			statement.setInt(1, 1)
			statement.setString(2, "a")
			statement.addBatch()
			statement.setDouble(1, 1.5)
			statement.setString(2, "b")
			statement.addBatch()
			statement.executeBatch()
		}

		assertEquals(listOf(1, 1), counts.toList())
		assertEquals(
			Json.parseToJsonElement("""
				{"batch":[
				{"sql":"insert into t values (cast(?1 as integer), ?)","params":["1","a"]},
				{"sql":"insert into t values (?, ?)","params":[1.5,"b"]}
				]}
			"""),
			sentJson(),
		)
	}

	/** Checks that a prepared statement refuses SQL passed to its execute methods. */
	@Test
	fun `refuses SQL passed to a prepared statement's execute methods`() {
		server.connect().use { connection ->
			val statement = connection.prepareStatement("select 1")

			for (call in listOf<() -> Unit>(
				{ statement.executeQuery("select 2") },
				{ statement.executeUpdate("delete from t") },
				{ statement.execute("select 2") },
				{ statement.addBatch("select 2") },
			)) {
				val error = assertFailsWith<SQLException> { call() }
				assertEquals("A prepared statement runs the SQL it was made with. Call the method without SQL.", error.message)
			}
		}

		assertTrue(server.requests.isEmpty())
	}
}
