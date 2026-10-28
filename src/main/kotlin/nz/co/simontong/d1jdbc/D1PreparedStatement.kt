package nz.co.simontong.d1jdbc

import java.io.InputStream
import java.io.Reader
import java.math.BigDecimal
import java.net.URL
import java.sql.Array as SqlArray
import java.sql.Blob
import java.sql.Clob
import java.sql.Date
import java.sql.NClob
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.sql.Ref
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.RowId
import java.sql.SQLDataException
import java.sql.SQLException
import java.sql.SQLXML
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
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
			setNull(parameterIndex, java.sql.Types.DECIMAL)
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
			setNull(parameterIndex, java.sql.Types.BLOB)
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

	/** Not supported yet. */
	override fun setObject(parameterIndex: Int, x: Any?) = throw notSupported("setObject")

	/** Not supported yet. */
	override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int) = throw notSupported("setObject")

	/** Not supported yet. */
	override fun setObject(parameterIndex: Int, x: Any?, targetSqlType: Int, scaleOrLength: Int) {
		throw notSupported("setObject")
	}

	/** Not supported yet. */
	override fun setAsciiStream(parameterIndex: Int, x: InputStream?, length: Int) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setAsciiStream(parameterIndex: Int, x: InputStream?, length: Long) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setAsciiStream(parameterIndex: Int, x: InputStream?) = throw notSupported("Streams")

	/** Not supported. */
	@Deprecated("Deprecated in Java")
	override fun setUnicodeStream(parameterIndex: Int, x: InputStream?, length: Int) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setBinaryStream(parameterIndex: Int, x: InputStream?, length: Int) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setBinaryStream(parameterIndex: Int, x: InputStream?, length: Long) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setBinaryStream(parameterIndex: Int, x: InputStream?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setCharacterStream(parameterIndex: Int, reader: Reader?, length: Int) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setCharacterStream(parameterIndex: Int, reader: Reader?, length: Long) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setCharacterStream(parameterIndex: Int, reader: Reader?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setNCharacterStream(parameterIndex: Int, value: Reader?, length: Long) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setNCharacterStream(parameterIndex: Int, value: Reader?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setBlob(parameterIndex: Int, x: Blob?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setBlob(parameterIndex: Int, inputStream: InputStream?, length: Long) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setBlob(parameterIndex: Int, inputStream: InputStream?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setClob(parameterIndex: Int, x: Clob?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setClob(parameterIndex: Int, reader: Reader?, length: Long) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setClob(parameterIndex: Int, reader: Reader?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setNClob(parameterIndex: Int, value: NClob?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setNClob(parameterIndex: Int, reader: Reader?, length: Long) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setNClob(parameterIndex: Int, reader: Reader?) = throw notSupported("Streams")

	/** Not supported yet. */
	override fun setURL(parameterIndex: Int, x: URL?) = throw notSupported("URL values")

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
