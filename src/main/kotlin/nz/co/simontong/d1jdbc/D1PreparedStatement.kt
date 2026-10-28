package nz.co.simontong.d1jdbc

import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URL
import java.sql.Array as SqlArray
import java.sql.Blob
import java.sql.Clob
import java.sql.Date
import java.sql.JDBCType
import java.sql.NClob
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.Ref
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.RowId
import java.sql.SQLDataException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLXML
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Calendar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A value bound to one parameter.
 *
 * @property json value as sent to D1
 * @property wholeNumber whether it's a whole number sent as text, which the SQL casts back
 */
private data class BoundValue(
	val json: JsonElement,
	val wholeNumber: Boolean = false,
)

/**
 * A JDBC prepared statement that sends SQL and its values to D1.
 *
 * @param connection connection the statement belongs to
 * @param sql SQL text with `?` or `?NNN` placeholders
 */
class D1PreparedStatement(
	connection: D1Connection,
	sql: String,
) : D1Statement(connection), PreparedStatement {
	private val preparedSql = PreparedSql(sql)
	private val values = mutableMapOf<Int, BoundValue>()

	/**
	 * Stores a value for a parameter.
	 *
	 * @param parameterIndex parameter number, starting at 1
	 * @param value value to store
	 * @throws SQLException when the statement is closed or there is no such parameter
	 */
	private fun bind(parameterIndex: Int, value: BoundValue) {
		checkOpen()
		if (parameterIndex < 1 || parameterIndex > preparedSql.parameterCount) {
			throw SQLException(
				"There is no parameter $parameterIndex. The SQL has ${preparedSql.parameterCount}.",
				"07009",
			)
		}
		values[parameterIndex] = value
	}

	/**
	 * Stores a whole number, sent as text so D1 keeps every digit.
	 *
	 * @param parameterIndex parameter number
	 * @param value the number
	 */
	private fun bindWholeNumber(parameterIndex: Int, value: Long) {
		bind(parameterIndex, BoundValue(JsonPrimitive(value.toString()), wholeNumber = true))
	}

	/**
	 * Stores a decimal, sent as a JSON number.
	 *
	 * @param parameterIndex parameter number
	 * @param value the number
	 * @throws SQLDataException when the value is NaN or infinite
	 */
	private fun bindDecimal(parameterIndex: Int, value: Number) {
		val double = value.toDouble()
		if (double.isNaN() || double.isInfinite()) {
			throw SQLDataException("D1 can't store NaN or infinity, so parameter $parameterIndex can't be sent.", "22003")
		}
		bind(parameterIndex, BoundValue(JsonPrimitive(value)))
	}

	/**
	 * Builds the query to send from the SQL and the bound values.
	 *
	 * @return the query
	 * @throws SQLException when the statement is closed or a parameter has no value
	 */
	private fun query(): D1Query {
		checkOpen()
		val params = (1..preparedSql.parameterCount).map { number ->
			val value = values[number] ?: throw SQLException(
				"Parameter $number has no value. Set every parameter before running the SQL.",
				"07001",
			)
			value.json
		}
		val wholeNumbers = values.filterValues { it.wholeNumber }.keys
		return D1Query(preparedSql.render(wholeNumbers), params)
	}

	/**
	 * Runs the SQL, which must return rows.
	 *
	 * @return the rows
	 * @throws SQLException when the SQL doesn't return rows or D1 reports an error
	 */
	override fun executeQuery(): ResultSet = runQuery(query())

	/**
	 * Runs the SQL, which must change data.
	 *
	 * @return number of rows changed
	 * @throws SQLException when the SQL returns rows or D1 reports an error
	 */
	override fun executeUpdate(): Int = runUpdate(query())

	/**
	 * Runs the SQL.
	 *
	 * @return true when the first result has rows to read
	 * @throws SQLException when D1 reports an error
	 */
	override fun execute(): Boolean = run(listOf(query()))

	/**
	 * Adds the SQL with the current values to the batch.
	 *
	 * @throws SQLException when the statement is closed
	 */
	override fun addBatch() = addToBatch(query())

	/**
	 * Builds the error for SQL passed to a method that runs the prepared SQL.
	 *
	 * @return the exception to throw
	 */
	private fun sqlNotAllowed(): SQLException {
		return SQLException(
			"A prepared statement runs the SQL it was made with. Call the method without SQL.",
			"HY000",
		)
	}

	/** Refused, as a prepared statement runs its own SQL. */
	override fun executeQuery(sql: String): ResultSet = throw sqlNotAllowed()

	/** Refused, as a prepared statement runs its own SQL. */
	override fun executeUpdate(sql: String): Int = throw sqlNotAllowed()

	/** Refused, as a prepared statement runs its own SQL. */
	override fun execute(sql: String): Boolean = throw sqlNotAllowed()

	/** Refused, as a prepared statement runs its own SQL. */
	override fun addBatch(sql: String) = throw sqlNotAllowed()

	/**
	 * Stores SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param sqlType SQL type, ignored
	 */
	override fun setNull(parameterIndex: Int, sqlType: Int) = bind(parameterIndex, BoundValue(JsonNull))

	/**
	 * Stores SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param sqlType SQL type, ignored
	 * @param typeName type name, ignored
	 */
	override fun setNull(parameterIndex: Int, sqlType: Int, typeName: String?) = setNull(parameterIndex, sqlType)

	/**
	 * Stores a boolean as 1 or 0, as D1 stores sent booleans as text.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	override fun setBoolean(parameterIndex: Int, x: Boolean) = bindWholeNumber(parameterIndex, if (x) 1 else 0)

	/**
	 * Stores a whole number.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	override fun setByte(parameterIndex: Int, x: Byte) = bindWholeNumber(parameterIndex, x.toLong())

	/**
	 * Stores a whole number.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	override fun setShort(parameterIndex: Int, x: Short) = bindWholeNumber(parameterIndex, x.toLong())

	/**
	 * Stores a whole number.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	override fun setInt(parameterIndex: Int, x: Int) = bindWholeNumber(parameterIndex, x.toLong())

	/**
	 * Stores a whole number.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	override fun setLong(parameterIndex: Int, x: Long) = bindWholeNumber(parameterIndex, x)

	/**
	 * Stores a decimal.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 * @throws SQLDataException when the value is NaN or infinite
	 */
	override fun setFloat(parameterIndex: Int, x: Float) = bindDecimal(parameterIndex, x)

	/**
	 * Stores a decimal.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 * @throws SQLDataException when the value is NaN or infinite
	 */
	override fun setDouble(parameterIndex: Int, x: Double) = bindDecimal(parameterIndex, x)

	/**
	 * Stores a decimal, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	override fun setBigDecimal(parameterIndex: Int, x: BigDecimal?) {
		if (x == null) {
			setNull(parameterIndex, Types.DECIMAL)
			return
		}
		bindDecimal(parameterIndex, x)
	}

	/**
	 * Stores text, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	override fun setString(parameterIndex: Int, x: String?) = bind(parameterIndex, BoundValue(JsonPrimitive(x)))

	/**
	 * Stores text, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param value the value
	 */
	override fun setNString(parameterIndex: Int, value: String?) = setString(parameterIndex, value)

	/**
	 * Stores binary data as a list of byte values, which D1 stores as a BLOB.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value, or null for SQL NULL
	 */
	override fun setBytes(parameterIndex: Int, x: ByteArray?) {
		if (x == null) {
			setNull(parameterIndex, Types.BLOB)
			return
		}
		bind(parameterIndex, BoundValue(JsonArray(x.map { JsonPrimitive(it.toInt() and 0xFF) })))
	}

	/**
	 * Stores a date as `YYYY-MM-DD` text, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the date
	 */
	override fun setDate(parameterIndex: Int, x: Date?) = setString(parameterIndex, x?.let { sqlDate(it.toLocalDate()) })

	/**
	 * Stores a date as `YYYY-MM-DD` text in the calendar's time zone, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the date
	 * @param cal calendar whose time zone to use, or null for the JVM's
	 */
	override fun setDate(parameterIndex: Int, x: Date?, cal: Calendar?) {
		if (x == null || cal == null) {
			setDate(parameterIndex, x)
			return
		}
		setString(parameterIndex, sqlDate(Instant.ofEpochMilli(x.time).atZone(cal.timeZone.toZoneId()).toLocalDate()))
	}

	/**
	 * Stores a time as `HH:MM:SS` text, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the time
	 */
	override fun setTime(parameterIndex: Int, x: Time?) = setString(parameterIndex, x?.let { sqlTime(it.toLocalTime()) })

	/**
	 * Stores a time as `HH:MM:SS` text in the calendar's time zone, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the time
	 * @param cal calendar whose time zone to use, or null for the JVM's
	 */
	override fun setTime(parameterIndex: Int, x: Time?, cal: Calendar?) {
		if (x == null || cal == null) {
			setTime(parameterIndex, x)
			return
		}
		setString(parameterIndex, sqlTime(Instant.ofEpochMilli(x.time).atZone(cal.timeZone.toZoneId()).toLocalTime()))
	}

	/**
	 * Stores a timestamp as `YYYY-MM-DD HH:MM:SS` text, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the timestamp
	 */
	override fun setTimestamp(parameterIndex: Int, x: Timestamp?) {
		setString(parameterIndex, x?.let { sqlDateTime(it.toLocalDateTime()) })
	}

	/**
	 * Stores a timestamp as `YYYY-MM-DD HH:MM:SS` text in the calendar's time zone, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the timestamp
	 * @param cal calendar whose time zone to use, or null for the JVM's
	 */
	override fun setTimestamp(parameterIndex: Int, x: Timestamp?, cal: Calendar?) {
		if (x == null || cal == null) {
			setTimestamp(parameterIndex, x)
			return
		}
		setString(parameterIndex, sqlDateTime(x.toInstant().atZone(cal.timeZone.toZoneId()).toLocalDateTime()))
	}

	/**
	 * Stores a value using the setter that matches its Java type.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value, or null for SQL NULL
	 * @throws SQLFeatureNotSupportedException for a Java type D1 can't store
	 */
	override fun setObject(parameterIndex: Int, x: Any?) {
		when (x) {
			null -> setNull(parameterIndex, Types.NULL)
			is Byte, is Short, is Int, is Long -> setLong(parameterIndex, x.toLong())
			is BigInteger -> setBigInteger(parameterIndex, x)
			is Boolean -> setBoolean(parameterIndex, x)
			is Float, is Double -> bindDecimal(parameterIndex, x)
			is BigDecimal -> setBigDecimal(parameterIndex, x)
			is String -> setString(parameterIndex, x)
			is Char -> setString(parameterIndex, x.toString())
			is ByteArray -> setBytes(parameterIndex, x)
			is Date -> setDate(parameterIndex, x)
			is Time -> setTime(parameterIndex, x)
			is Timestamp -> setTimestamp(parameterIndex, x)
			is LocalDate -> setString(parameterIndex, sqlDate(x))
			is LocalTime -> setString(parameterIndex, sqlTime(x))
			is LocalDateTime -> setString(parameterIndex, sqlDateTime(x))
			is Instant -> setString(parameterIndex, sqlDateTime(LocalDateTime.ofInstant(x, ZoneOffset.UTC)))
			is OffsetDateTime -> setString(parameterIndex, sqlDateTime(x.toLocalDateTime()) + x.offset.id)
			else -> throw notSupported("Sending ${x.javaClass.name} values")
		}
	}

	/**
	 * Stores a whole number, or a decimal when it's too big for SQLite's 64-bit integers.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	private fun setBigInteger(parameterIndex: Int, x: BigInteger) {
		// SQLite's cast clamps bigger values, and it reads a literal that big as a decimal too.
		if (x.bitLength() < 64) {
			setLong(parameterIndex, x.toLong())
			return
		}
		bindDecimal(parameterIndex, x)
	}

	/**
	 * Stores a value after converting it to the target SQL type.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value, or null for SQL NULL
	 * @param targetSqlType a [Types] constant
	 * @throws SQLDataException when the value can't be converted
	 */
	override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int) {
		if (x == null) {
			setNull(parameterIndex, targetSqlType)
			return
		}

		try {
			when (targetSqlType) {
				Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
					setBigInteger(parameterIndex, decimalOf(x).toBigIntegerExact())
				}

				Types.BOOLEAN, Types.BIT -> {
					setBoolean(parameterIndex, booleanOf(x))
				}

				Types.REAL, Types.FLOAT, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> {
					bindDecimal(parameterIndex, decimalOf(x))
				}

				Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> {
					setTextOf(parameterIndex, x)
				}

				else -> {
					setObject(parameterIndex, x)
				}
			}
		} catch (error: ArithmeticException) {
			throw conversionError(parameterIndex, x, targetSqlType, error)
		} catch (error: NumberFormatException) {
			throw conversionError(parameterIndex, x, targetSqlType, error)
		}
	}

	/**
	 * Stores a value after converting it to the target SQL type. The scale is ignored, as D1 stores decimals as REAL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value, or null for SQL NULL
	 * @param targetSqlType a [Types] constant
	 * @param scaleOrLength digits after the point, or length, ignored
	 * @throws SQLDataException when the value can't be converted
	 */
	override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int, scaleOrLength: Int) {
		setObject(parameterIndex, x, targetSqlType)
	}

	/**
	 * Reads a number, boolean or text as a decimal.
	 *
	 * @param x the value
	 * @return the decimal
	 * @throws NumberFormatException when it isn't a number
	 */
	private fun decimalOf(x: Any): BigDecimal {
		return when (x) {
			is BigDecimal -> x
			is Boolean -> if (x) BigDecimal.ONE else BigDecimal.ZERO
			else -> BigDecimal(x.toString().trim())
		}
	}

	/**
	 * Reads a boolean, number or text as a boolean.
	 *
	 * @param x the value
	 * @return the boolean
	 * @throws NumberFormatException when it's neither a boolean nor a number
	 */
	private fun booleanOf(x: Any): Boolean {
		val text = x.toString().trim()
		return when {
			x is Boolean -> x
			text.equals("true", ignoreCase = true) -> true
			text.equals("false", ignoreCase = true) -> false
			else -> BigDecimal(text).signum() != 0
		}
	}

	/**
	 * Stores a value as text, keeping SQLite's formats for dates and times.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 */
	private fun setTextOf(parameterIndex: Int, x: Any) {
		if (x is java.util.Date || x is java.time.temporal.Temporal) {
			setObject(parameterIndex, x)
			return
		}
		setString(parameterIndex, if (x is BigDecimal) x.toPlainString() else x.toString())
	}

	/**
	 * Builds the error for a value that can't be converted to the target SQL type.
	 *
	 * @param parameterIndex parameter number
	 * @param x the value
	 * @param targetSqlType a [Types] constant
	 * @param cause the conversion failure
	 * @return the exception to throw
	 */
	private fun conversionError(parameterIndex: Int, x: Any, targetSqlType: Int, cause: Throwable): SQLException {
		val typeName = JDBCType.entries.firstOrNull { it.vendorTypeNumber == targetSqlType }?.name ?: "type $targetSqlType"
		return SQLDataException("Parameter $parameterIndex can't be sent as $typeName: $x", "22018", cause)
	}

	/**
	 * Reads up to a number of bytes from a stream.
	 *
	 * @param parameterIndex parameter number, for the error message
	 * @param stream stream to read
	 * @param length most bytes to read, or null for all of them
	 * @return the bytes read
	 * @throws SQLException when the stream fails
	 */
	private fun readBytes(parameterIndex: Int, stream: InputStream, length: Long?): ByteArray {
		try {
			return if (length == null) stream.readAllBytes() else stream.readNBytes(length.toInt())
		} catch (error: IOException) {
			throw SQLException("Could not read the value for parameter $parameterIndex. ${error.message}", "HY000", error)
		}
	}

	/**
	 * Reads up to a number of characters from a reader.
	 *
	 * @param parameterIndex parameter number, for the error message
	 * @param reader reader to read
	 * @param length most characters to read, or null for all of them
	 * @return the text read
	 * @throws SQLException when the reader fails
	 */
	private fun readText(parameterIndex: Int, reader: Reader, length: Long?): String {
		try {
			if (length == null) {
				return reader.readText()
			}
			val buffer = CharArray(length.toInt())
			var read = 0
			while (read < buffer.size) {
				val count = reader.read(buffer, read, buffer.size - read)
				if (count < 0) {
					break
				}
				read += count
			}
			return String(buffer, 0, read)
		} catch (error: IOException) {
			throw SQLException("Could not read the value for parameter $parameterIndex. ${error.message}", "HY000", error)
		}
	}

	/**
	 * Stores the bytes read from a stream, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param stream stream to read, or null
	 * @param length most bytes to read, or null for all of them
	 * @throws SQLException when the stream fails
	 */
	private fun setBytesFrom(parameterIndex: Int, stream: InputStream?, length: Long?) {
		setBytes(parameterIndex, stream?.let { readBytes(parameterIndex, it, length) })
	}

	/**
	 * Stores the text read from a reader, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param reader reader to read, or null
	 * @param length most characters to read, or null for all of them
	 * @throws SQLException when the reader fails
	 */
	private fun setTextFrom(parameterIndex: Int, reader: Reader?, length: Long?) {
		setString(parameterIndex, reader?.let { readText(parameterIndex, it, length) })
	}

	/**
	 * Stores the ASCII text read from a stream, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param stream stream to read, or null
	 * @param length most bytes to read, or null for all of them
	 * @throws SQLException when the stream fails
	 */
	private fun setAsciiFrom(parameterIndex: Int, stream: InputStream?, length: Long?) {
		setString(parameterIndex, stream?.let { readBytes(parameterIndex, it, length).toString(Charsets.US_ASCII) })
	}

	/** Stores ASCII text read from a stream. See [setAsciiFrom]. */
	override fun setAsciiStream(parameterIndex: Int, x: InputStream?, length: Int) {
		setAsciiFrom(parameterIndex, x, length.toLong())
	}

	/** Stores ASCII text read from a stream. See [setAsciiFrom]. */
	override fun setAsciiStream(parameterIndex: Int, x: InputStream?, length: Long) = setAsciiFrom(parameterIndex, x, length)

	/** Stores ASCII text read from a stream. See [setAsciiFrom]. */
	override fun setAsciiStream(parameterIndex: Int, x: InputStream?) = setAsciiFrom(parameterIndex, x, null)

	/** Not supported, as Java deprecated it. Use setCharacterStream. */
	@Deprecated("Deprecated in Java")
	override fun setUnicodeStream(parameterIndex: Int, x: InputStream?, length: Int) = throw notSupported("Unicode streams")

	/** Stores bytes read from a stream. See [setBytesFrom]. */
	override fun setBinaryStream(parameterIndex: Int, x: InputStream?, length: Int) {
		setBytesFrom(parameterIndex, x, length.toLong())
	}

	/** Stores bytes read from a stream. See [setBytesFrom]. */
	override fun setBinaryStream(parameterIndex: Int, x: InputStream?, length: Long) = setBytesFrom(parameterIndex, x, length)

	/** Stores bytes read from a stream. See [setBytesFrom]. */
	override fun setBinaryStream(parameterIndex: Int, x: InputStream?) = setBytesFrom(parameterIndex, x, null)

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setCharacterStream(parameterIndex: Int, reader: Reader?, length: Int) {
		setTextFrom(parameterIndex, reader, length.toLong())
	}

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setCharacterStream(parameterIndex: Int, reader: Reader?, length: Long) {
		setTextFrom(parameterIndex, reader, length)
	}

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setCharacterStream(parameterIndex: Int, reader: Reader?) = setTextFrom(parameterIndex, reader, null)

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setNCharacterStream(parameterIndex: Int, value: Reader?, length: Long) {
		setTextFrom(parameterIndex, value, length)
	}

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setNCharacterStream(parameterIndex: Int, value: Reader?) = setTextFrom(parameterIndex, value, null)

	/**
	 * Stores the bytes of a Blob, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the Blob
	 */
	override fun setBlob(parameterIndex: Int, x: Blob?) = setBytes(parameterIndex, x?.let { it.getBytes(1, it.length().toInt()) })

	/** Stores bytes read from a stream. See [setBytesFrom]. */
	override fun setBlob(parameterIndex: Int, inputStream: InputStream?, length: Long) {
		setBytesFrom(parameterIndex, inputStream, length)
	}

	/** Stores bytes read from a stream. See [setBytesFrom]. */
	override fun setBlob(parameterIndex: Int, inputStream: InputStream?) = setBytesFrom(parameterIndex, inputStream, null)

	/**
	 * Stores the text of a Clob, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the Clob
	 */
	override fun setClob(parameterIndex: Int, x: Clob?) = setString(parameterIndex, x?.let { it.getSubString(1, it.length().toInt()) })

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setClob(parameterIndex: Int, reader: Reader?, length: Long) = setTextFrom(parameterIndex, reader, length)

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setClob(parameterIndex: Int, reader: Reader?) = setTextFrom(parameterIndex, reader, null)

	/**
	 * Stores the text of an NClob, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param value the NClob
	 */
	override fun setNClob(parameterIndex: Int, value: NClob?) = setClob(parameterIndex, value)

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setNClob(parameterIndex: Int, reader: Reader?, length: Long) = setTextFrom(parameterIndex, reader, length)

	/** Stores text read from a reader. See [setTextFrom]. */
	override fun setNClob(parameterIndex: Int, reader: Reader?) = setTextFrom(parameterIndex, reader, null)

	/**
	 * Stores a URL as text, or SQL NULL.
	 *
	 * @param parameterIndex parameter number
	 * @param x the URL
	 */
	override fun setURL(parameterIndex: Int, x: URL?) = setString(parameterIndex, x?.toString())

	/** Not supported. */
	override fun setRef(parameterIndex: Int, x: Ref?) = throw notSupported("Ref values")

	/** Not supported. */
	override fun setArray(parameterIndex: Int, x: SqlArray?) = throw notSupported("Arrays")

	/** Not supported. */
	override fun setRowId(parameterIndex: Int, x: RowId?) = throw notSupported("Row IDs")

	/** Not supported. */
	override fun setSQLXML(parameterIndex: Int, xmlObject: SQLXML?) = throw notSupported("SQLXML values")

	/**
	 * Removes every bound value.
	 *
	 * @throws SQLException when the statement is closed
	 */
	override fun clearParameters() {
		checkOpen()
		values.clear()
	}

	/** @return null, as the columns aren't known until D1 runs the SQL */
	override fun getMetaData(): ResultSetMetaData? {
		checkOpen()
		return null
	}

	/**
	 * Describes the parameters, which D1 only lets the driver count.
	 *
	 * @return the parameter details
	 * @throws SQLException when the statement is closed
	 */
	override fun getParameterMetaData(): ParameterMetaData {
		checkOpen()
		return D1ParameterMetaData(preparedSql.parameterCount)
	}
}
