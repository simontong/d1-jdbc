package nz.co.simontong.d1jdbc

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URL
import java.sql.Array as SqlArray
import java.sql.Blob
import java.sql.Clob
import java.sql.Date
import java.sql.NClob
import java.sql.Ref
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.RowId
import java.sql.SQLDataException
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.SQLXML
import java.sql.Statement
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/**
 * Forward-only, read-only rows returned by D1.
 *
 * @param statement statement that made the rows, or null for metadata results
 * @param columns column names in order
 * @param rows row values, one list per row
 */
class D1ResultSet(
	private val statement: D1Statement?,
	private val columns: List<String>,
	private val rows: List<List<JsonElement>>,
) : ResultSet {
	private var rowIndex = -1
	private var closed = false
	private var lastWasNull = false
	private val kinds by lazy { columns.indices.map { index -> columnKindOf(rows.map { it[index] }) } }

	/**
	 * Throws when the result set is closed.
	 *
	 * @throws SQLException when closed
	 */
	private fun checkOpen() {
		if (closed) {
			throw SQLException("The result set is closed.", "HY010")
		}
	}

	/**
	 * Reads a value from the current row and remembers whether it was null.
	 *
	 * @param columnIndex column number, starting at 1
	 * @return the value, or null for SQL NULL
	 * @throws SQLException when there is no current row or the column doesn't exist
	 */
	private fun value(columnIndex: Int): JsonElement? {
		checkOpen()
		val row = rows.getOrNull(rowIndex) ?: throw SQLException("There is no current row. Call next() first.", "24000")
		if (columnIndex < 1 || columnIndex > columns.size) {
			throw SQLException("Column $columnIndex doesn't exist. There are ${columns.size} columns.", "42S22")
		}
		val element = row[columnIndex - 1]
		lastWasNull = element is JsonNull
		return if (lastWasNull) null else element
	}

	/**
	 * Builds the error for a value that can't be read as the asked type.
	 *
	 * @param columnIndex column number
	 * @param typeName type that was asked for
	 * @return the exception to throw
	 */
	private fun conversionError(columnIndex: Int, typeName: String): SQLException {
		return SQLDataException("Column ${columns[columnIndex - 1]} can't be read as $typeName.", "22018")
	}

	/**
	 * Reads a value as a number.
	 *
	 * @param columnIndex column number
	 * @param typeName type that was asked for, for error messages
	 * @return the number, or null for SQL NULL
	 * @throws SQLException when the value isn't a number
	 */
	private fun decimal(columnIndex: Int, typeName: String): BigDecimal? {
		val element = value(columnIndex) ?: return null
		if (element !is JsonPrimitive) {
			throw conversionError(columnIndex, typeName)
		}
		return element.content.trim().toBigDecimalOrNull() ?: throw conversionError(columnIndex, typeName)
	}

	/**
	 * Moves to the next row.
	 *
	 * @return true when there is a row to read
	 */
	override fun next(): Boolean {
		checkOpen()
		if (rowIndex < rows.size) {
			rowIndex++
		}
		return rowIndex < rows.size
	}

	/** Closes the result set. */
	override fun close() {
		if (!closed) {
			closed = true
			statement?.resultSetClosed()
		}
	}

	/** @return whether the last value read was SQL NULL */
	override fun wasNull(): Boolean {
		checkOpen()
		return lastWasNull
	}

	/**
	 * Finds a column by name, ignoring case.
	 *
	 * @param columnLabel column name
	 * @return column number, starting at 1
	 * @throws SQLException when there is no such column
	 */
	override fun findColumn(columnLabel: String?): Int {
		checkOpen()
		val index = columns.indexOfFirst { it.equals(columnLabel, ignoreCase = true) }
		if (index < 0) {
			throw SQLException("There is no column named $columnLabel.", "42S22")
		}
		return index + 1
	}

	/** @return the value as text, or null for SQL NULL */
	override fun getString(columnIndex: Int): String? {
		return when (val element = value(columnIndex)) {
			null -> null
			is JsonPrimitive -> element.content
			else -> element.toString()
		}
	}

	/** @return the value as a boolean, where 0, NULL and "false" are false */
	override fun getBoolean(columnIndex: Int): Boolean {
		val element = value(columnIndex) ?: return false
		val text = (element as? JsonPrimitive)?.content?.trim() ?: throw conversionError(columnIndex, "a boolean")
		if (text.equals("true", ignoreCase = true)) {
			return true
		}
		if (text.equals("false", ignoreCase = true)) {
			return false
		}
		val number = text.toBigDecimalOrNull() ?: throw conversionError(columnIndex, "a boolean")
		return number.signum() != 0
	}

	/** @return the value as a byte, or 0 for SQL NULL */
	override fun getByte(columnIndex: Int): Byte = getLong(columnIndex).toByte()

	/** @return the value as a short, or 0 for SQL NULL */
	override fun getShort(columnIndex: Int): Short = getLong(columnIndex).toShort()

	/** @return the value as an int, or 0 for SQL NULL */
	override fun getInt(columnIndex: Int): Int = getLong(columnIndex).toInt()

	/** @return the value as a long, or 0 for SQL NULL */
	override fun getLong(columnIndex: Int): Long = decimal(columnIndex, "a whole number")?.toLong() ?: 0L

	/** @return the value as a float, or 0 for SQL NULL */
	override fun getFloat(columnIndex: Int): Float = decimal(columnIndex, "a decimal")?.toFloat() ?: 0f

	/** @return the value as a double, or 0 for SQL NULL */
	override fun getDouble(columnIndex: Int): Double = decimal(columnIndex, "a decimal")?.toDouble() ?: 0.0

	/** @return the value as a BigDecimal, or null for SQL NULL */
	override fun getBigDecimal(columnIndex: Int): BigDecimal? = decimal(columnIndex, "a decimal")

	/** @return the value as a BigDecimal with the given scale, or null for SQL NULL */
	@Deprecated("Deprecated in Java")
	override fun getBigDecimal(columnIndex: Int, scale: Int): BigDecimal? {
		return decimal(columnIndex, "a decimal")?.setScale(scale, java.math.RoundingMode.HALF_UP)
	}

	/** @return the value as bytes, or null for SQL NULL */
	override fun getBytes(columnIndex: Int): ByteArray? {
		return when (val element = value(columnIndex)) {
			null -> null
			is JsonArray -> ByteArray(element.size) { (element[it] as JsonPrimitive).int.toByte() }
			is JsonPrimitive -> element.content.encodeToByteArray()
			else -> throw conversionError(columnIndex, "bytes")
		}
	}

	/**
	 * Reads a value as SQLite date and time text.
	 *
	 * @param columnIndex column number
	 * @param typeName type that was asked for, for error messages
	 * @param needsDate whether the text must hold a date
	 * @param needsTime whether the text must hold a time
	 * @return the parts read, or null for SQL NULL
	 * @throws SQLException when the value isn't date or time text with the needed parts
	 */
	private fun dateTime(columnIndex: Int, typeName: String, needsDate: Boolean, needsTime: Boolean): SqlDateTime? {
		val element = value(columnIndex) ?: return null
		val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
		val parsed = text?.let { parseSqlDateTime(it) } ?: throw conversionError(columnIndex, typeName)
		if ((needsDate && parsed.date == null) || (needsTime && parsed.time == null)) {
			throw conversionError(columnIndex, typeName)
		}
		return parsed
	}

	/**
	 * Returns the time zone to read times without one in.
	 *
	 * @param cal calendar the caller passed, or null
	 * @return the calendar's time zone, or the JVM's
	 */
	private fun zoneOf(cal: Calendar?): ZoneId = cal?.timeZone?.toZoneId() ?: ZoneId.systemDefault()

	/**
	 * Reads the moment a value describes.
	 *
	 * @param parsed date and time read from the value
	 * @param zone time zone to use when the text has none
	 * @return the moment
	 */
	private fun instantOf(parsed: SqlDateTime, zone: ZoneId): Instant {
		val local = LocalDateTime.of(parsed.date!!, parsed.time ?: LocalTime.MIDNIGHT)
		return parsed.offset?.let { local.toInstant(it) } ?: local.atZone(zone).toInstant()
	}

	/** @return the date part of SQLite date text, or null for SQL NULL */
	override fun getDate(columnIndex: Int): Date? = getDate(columnIndex, null)

	/**
	 * Reads the date part of SQLite date text, as midnight in the calendar's time zone.
	 *
	 * @param columnIndex column number
	 * @param cal calendar whose time zone to use, or null for the JVM's
	 * @return the date, or null for SQL NULL
	 * @throws SQLException when the value isn't date text
	 */
	override fun getDate(columnIndex: Int, cal: Calendar?): Date? {
		val parsed = dateTime(columnIndex, "a date", needsDate = true, needsTime = false) ?: return null
		return Date(parsed.date!!.atStartOfDay(zoneOf(cal)).toInstant().toEpochMilli())
	}

	/** @return the time part of SQLite time text, or null for SQL NULL */
	override fun getTime(columnIndex: Int): Time? = getTime(columnIndex, null)

	/**
	 * Reads the time part of SQLite time text, on 1 January 1970 in the calendar's time zone.
	 *
	 * @param columnIndex column number
	 * @param cal calendar whose time zone to use, or null for the JVM's
	 * @return the time, or null for SQL NULL
	 * @throws SQLException when the value isn't time text
	 */
	override fun getTime(columnIndex: Int, cal: Calendar?): Time? {
		val parsed = dateTime(columnIndex, "a time", needsDate = false, needsTime = true) ?: return null
		return Time(LocalDateTime.of(LocalDate.EPOCH, parsed.time!!).atZone(zoneOf(cal)).toInstant().toEpochMilli())
	}

	/** @return the moment SQLite date text describes, or null for SQL NULL */
	override fun getTimestamp(columnIndex: Int): Timestamp? = getTimestamp(columnIndex, null)

	/**
	 * Reads the moment SQLite date text describes, in the calendar's time zone if it has none.
	 *
	 * @param columnIndex column number
	 * @param cal calendar whose time zone to use, or null for the JVM's
	 * @return the timestamp, or null for SQL NULL
	 * @throws SQLException when the value isn't date text
	 */
	override fun getTimestamp(columnIndex: Int, cal: Calendar?): Timestamp? {
		val parsed = dateTime(columnIndex, "a timestamp", needsDate = true, needsTime = false) ?: return null
		return Timestamp.from(instantOf(parsed, zoneOf(cal)))
	}

	/** @return the value as ASCII bytes, or null for SQL NULL */
	override fun getAsciiStream(columnIndex: Int): InputStream? {
		return getString(columnIndex)?.let { ByteArrayInputStream(it.toByteArray(Charsets.US_ASCII)) }
	}

	/** @return the value as UTF-16 bytes, or null for SQL NULL */
	@Deprecated("Deprecated in Java")
	override fun getUnicodeStream(columnIndex: Int): InputStream? {
		return getString(columnIndex)?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_16BE)) }
	}

	/** @return the value as a byte stream, or null for SQL NULL */
	override fun getBinaryStream(columnIndex: Int): InputStream? = getBytes(columnIndex)?.let { ByteArrayInputStream(it) }

	/** @return the value as a character stream, or null for SQL NULL */
	override fun getCharacterStream(columnIndex: Int): Reader? = getString(columnIndex)?.let { StringReader(it) }

	/** @return the value as a character stream, or null for SQL NULL */
	override fun getNCharacterStream(columnIndex: Int): Reader? = getCharacterStream(columnIndex)

	/** @return the value as text, or null for SQL NULL */
	override fun getNString(columnIndex: Int): String? = getString(columnIndex)

	/**
	 * Reads the value as the Java type of its column, so every row gives the same class.
	 *
	 * @param columnIndex column number
	 * @return Long, Double, Boolean, String or ByteArray, or null for SQL NULL
	 */
	override fun getObject(columnIndex: Int): Any? {
		val element = value(columnIndex) ?: return null
		val kind = kinds[columnIndex - 1].takeIf { it != ColumnKind.ANY } ?: valueKindOf(element)
		return when (kind) {
			ColumnKind.INTEGER -> getLong(columnIndex)
			ColumnKind.REAL -> getDouble(columnIndex)
			ColumnKind.BLOB -> getBytes(columnIndex)
			ColumnKind.BOOLEAN -> getBoolean(columnIndex)
			else -> getString(columnIndex)
		}
	}

	/**
	 * Reads the value as the given Java type.
	 *
	 * @param columnIndex column number
	 * @param type Java class to return
	 * @return the value, or null for SQL NULL
	 * @throws SQLException when the value can't be read as that type
	 */
	override fun <T : Any?> getObject(columnIndex: Int, type: Class<T>?): T? {
		if (type == null) {
			throw SQLException("A type must be given.", "HY009")
		}
		// Primitive classes like int.class become their boxed classes, so cast() works.
		@Suppress("UNCHECKED_CAST")
		val boxed = (type as Class<Any>).kotlin.javaObjectType
		val result: Any? = when (boxed) {
			Any::class.java -> getObject(columnIndex)
			String::class.java -> getString(columnIndex)
			Long::class.javaObjectType -> getLong(columnIndex)
			Int::class.javaObjectType -> getInt(columnIndex)
			Short::class.javaObjectType -> getShort(columnIndex)
			Byte::class.javaObjectType -> getByte(columnIndex)
			Double::class.javaObjectType -> getDouble(columnIndex)
			Float::class.javaObjectType -> getFloat(columnIndex)
			Boolean::class.javaObjectType -> getBoolean(columnIndex)
			BigDecimal::class.java -> getBigDecimal(columnIndex)
			BigInteger::class.java -> getBigDecimal(columnIndex)?.toBigInteger()
			ByteArray::class.java -> getBytes(columnIndex)
			Date::class.java -> getDate(columnIndex)
			Time::class.java -> getTime(columnIndex)
			Timestamp::class.java -> getTimestamp(columnIndex)
			LocalDate::class.java -> dateTime(columnIndex, "a date", needsDate = true, needsTime = false)?.date
			LocalTime::class.java -> dateTime(columnIndex, "a time", needsDate = false, needsTime = true)?.time
			LocalDateTime::class.java -> localDateTime(columnIndex)
			OffsetDateTime::class.java -> offsetDateTime(columnIndex)
			Instant::class.java -> offsetDateTime(columnIndex)?.toInstant()
			else -> throw notSupported("Reading ${type.name} values")
		}
		if (lastWasNull) {
			return null
		}
		@Suppress("UNCHECKED_CAST")
		return boxed.cast(result) as T
	}

	/**
	 * Reads SQLite date text as a date and time, ignoring any time zone.
	 *
	 * @param columnIndex column number
	 * @return the date and time, or null for SQL NULL
	 * @throws SQLException when the value isn't date text
	 */
	private fun localDateTime(columnIndex: Int): LocalDateTime? {
		val parsed = dateTime(columnIndex, "a date and time", needsDate = true, needsTime = false) ?: return null
		return LocalDateTime.of(parsed.date!!, parsed.time ?: LocalTime.MIDNIGHT)
	}

	/**
	 * Reads SQLite date text with its time zone, using UTC when it has none, as SQLite does.
	 *
	 * @param columnIndex column number
	 * @return the date and time, or null for SQL NULL
	 * @throws SQLException when the value isn't date text
	 */
	private fun offsetDateTime(columnIndex: Int): OffsetDateTime? {
		val local = localDateTime(columnIndex) ?: return null
		val parsed = dateTime(columnIndex, "a date and time", needsDate = true, needsTime = false)!!
		return local.atOffset(parsed.offset ?: ZoneOffset.UTC)
	}

	/** Not supported. */
	override fun getObject(columnIndex: Int, map: MutableMap<String, Class<*>>?): Any? = throw notSupported("Type maps")

	/** Not supported. */
	override fun getRef(columnIndex: Int): Ref? = throw notSupported("Ref values")

	/** Not supported. */
	override fun getBlob(columnIndex: Int): Blob? = throw notSupported("Blob objects, use getBytes")

	/** Not supported. */
	override fun getClob(columnIndex: Int): Clob? = throw notSupported("Clob objects, use getString")

	/** Not supported. */
	override fun getArray(columnIndex: Int): SqlArray? = throw notSupported("Arrays")

	/** Not supported. */
	override fun getURL(columnIndex: Int): URL? = throw notSupported("URL values")

	/** Not supported. */
	override fun getRowId(columnIndex: Int): RowId? = throw notSupported("Row IDs")

	/** Not supported. */
	override fun getNClob(columnIndex: Int): NClob? = throw notSupported("NClob objects, use getString")

	/** Not supported. */
	override fun getSQLXML(columnIndex: Int): SQLXML? = throw notSupported("SQLXML values")

	// Label versions look the column up by name, then read it by number.

	/** Reads a column by name. See the column number version. */
	override fun getString(columnLabel: String?): String? = getString(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getBoolean(columnLabel: String?): Boolean = getBoolean(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getByte(columnLabel: String?): Byte = getByte(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getShort(columnLabel: String?): Short = getShort(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getInt(columnLabel: String?): Int = getInt(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getLong(columnLabel: String?): Long = getLong(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getFloat(columnLabel: String?): Float = getFloat(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getDouble(columnLabel: String?): Double = getDouble(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getBigDecimal(columnLabel: String?): BigDecimal? = getBigDecimal(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getBytes(columnLabel: String?): ByteArray? = getBytes(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getDate(columnLabel: String?): Date? = getDate(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getTime(columnLabel: String?): Time? = getTime(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getTimestamp(columnLabel: String?): Timestamp? = getTimestamp(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getDate(columnLabel: String?, cal: Calendar?): Date? = getDate(findColumn(columnLabel), cal)

	/** Reads a column by name. See the column number version. */
	override fun getTime(columnLabel: String?, cal: Calendar?): Time? = getTime(findColumn(columnLabel), cal)

	/** Reads a column by name. See the column number version. */
	override fun getTimestamp(columnLabel: String?, cal: Calendar?): Timestamp? = getTimestamp(findColumn(columnLabel), cal)

	/** Reads a column by name. See the column number version. */
	override fun getAsciiStream(columnLabel: String?): InputStream? = getAsciiStream(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getBinaryStream(columnLabel: String?): InputStream? = getBinaryStream(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getCharacterStream(columnLabel: String?): Reader? = getCharacterStream(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getNCharacterStream(columnLabel: String?): Reader? = getNCharacterStream(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getNString(columnLabel: String?): String? = getNString(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getObject(columnLabel: String?): Any? = getObject(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getObject(columnLabel: String?, map: MutableMap<String, Class<*>>?): Any? = getObject(findColumn(columnLabel), map)

	/** Reads a column by name. See the column number version. */
	override fun getRef(columnLabel: String?): Ref? = getRef(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getBlob(columnLabel: String?): Blob? = getBlob(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getClob(columnLabel: String?): Clob? = getClob(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getArray(columnLabel: String?): SqlArray? = getArray(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getURL(columnLabel: String?): URL? = getURL(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getRowId(columnLabel: String?): RowId? = getRowId(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getNClob(columnLabel: String?): NClob? = getNClob(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun getSQLXML(columnLabel: String?): SQLXML? = getSQLXML(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	@Deprecated("Deprecated in Java")
	@Suppress("DEPRECATION")
	override fun getBigDecimal(columnLabel: String?, scale: Int): BigDecimal? = getBigDecimal(findColumn(columnLabel), scale)

	/** Reads a column by name. See the column number version. */
	@Deprecated("Deprecated in Java")
	@Suppress("DEPRECATION")
	override fun getUnicodeStream(columnLabel: String?): InputStream? = getUnicodeStream(findColumn(columnLabel))

	/** Reads a column by name. See the column number version. */
	override fun <T : Any?> getObject(columnLabel: String?, type: Class<T>?): T? = getObject(findColumn(columnLabel), type)
	/** @return null, as result sets raise no warnings */
	override fun getWarnings(): SQLWarning? {
		checkOpen()
		return null
	}

	/** Does nothing, as result sets raise no warnings. */
	override fun clearWarnings() {
		checkOpen()
	}

	/** Not supported. */
	override fun getCursorName(): String = throw notSupported("Named cursors")

	/** @return column details for these rows */
	override fun getMetaData(): ResultSetMetaData {
		checkOpen()
		return D1ResultSetMetaData(columns, kinds)
	}

	/** @return true when the cursor is before the first row and there are rows */
	override fun isBeforeFirst(): Boolean {
		checkOpen()
		return rowIndex < 0 && rows.isNotEmpty()
	}

	/** @return true when the cursor is after the last row and there are rows */
	override fun isAfterLast(): Boolean {
		checkOpen()
		return rowIndex >= rows.size && rows.isNotEmpty()
	}

	/** @return true when the cursor is on the first row */
	override fun isFirst(): Boolean {
		checkOpen()
		return rowIndex == 0 && rows.isNotEmpty()
	}

	/** @return true when the cursor is on the last row */
	override fun isLast(): Boolean {
		checkOpen()
		return rowIndex == rows.size - 1 && rows.isNotEmpty()
	}

	/** @return the current row number starting at 1, or 0 when there is no current row */
	override fun getRow(): Int {
		checkOpen()
		return if (rowIndex in rows.indices) rowIndex + 1 else 0
	}

	/** Not supported, as results are forward-only. */
	override fun beforeFirst() = throw notSupported("Scrolling result sets")

	/** Not supported, as results are forward-only. */
	override fun afterLast() = throw notSupported("Scrolling result sets")

	/** Not supported, as results are forward-only. */
	override fun first(): Boolean = throw notSupported("Scrolling result sets")

	/** Not supported, as results are forward-only. */
	override fun last(): Boolean = throw notSupported("Scrolling result sets")

	/** Not supported, as results are forward-only. */
	override fun absolute(row: Int): Boolean = throw notSupported("Scrolling result sets")

	/** Not supported, as results are forward-only. */
	override fun relative(rows: Int): Boolean = throw notSupported("Scrolling result sets")

	/** Not supported, as results are forward-only. */
	override fun previous(): Boolean = throw notSupported("Scrolling result sets")

	/**
	 * Accepts only forward reading.
	 *
	 * @param direction fetch direction
	 * @throws SQLException for any other direction
	 */
	override fun setFetchDirection(direction: Int) {
		checkOpen()
		if (direction != ResultSet.FETCH_FORWARD) {
			throw SQLException("D1 results can only be read forwards.", "HY024")
		}
	}

	/** @return [ResultSet.FETCH_FORWARD] */
	override fun getFetchDirection(): Int {
		checkOpen()
		return ResultSet.FETCH_FORWARD
	}

	/**
	 * Ignored, as all rows are already loaded.
	 *
	 * @param rows fetch size hint
	 */
	override fun setFetchSize(rows: Int) {
		checkOpen()
	}

	/** @return 0, as all rows are already loaded */
	override fun getFetchSize(): Int {
		checkOpen()
		return 0
	}

	/** @return [ResultSet.TYPE_FORWARD_ONLY] */
	override fun getType(): Int = ResultSet.TYPE_FORWARD_ONLY

	/** @return [ResultSet.CONCUR_READ_ONLY] */
	override fun getConcurrency(): Int = ResultSet.CONCUR_READ_ONLY

	/** @return [ResultSet.HOLD_CURSORS_OVER_COMMIT] */
	override fun getHoldability(): Int = ResultSet.HOLD_CURSORS_OVER_COMMIT

	/** @return false, as results are read-only */
	override fun rowUpdated(): Boolean = false

	/** @return false, as results are read-only */
	override fun rowInserted(): Boolean = false

	/** @return false, as results are read-only */
	override fun rowDeleted(): Boolean = false

	/** @return the statement that made these rows, or null for metadata results */
	override fun getStatement(): Statement? {
		checkOpen()
		return statement
	}

	/** @return whether the result set is closed */
	override fun isClosed(): Boolean = closed

	/**
	 * Returns this result set as the given interface.
	 *
	 * @param iface interface to unwrap to
	 * @return this result set
	 * @throws SQLException when it doesn't implement the interface
	 */
	override fun <T : Any?> unwrap(iface: Class<T>): T {
		if (!iface.isInstance(this)) {
			throw SQLException("The result set is not a ${iface.name}.")
		}
		return iface.cast(this)
	}

	/** @return whether this result set implements the interface */
	override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

	/** Not supported, as D1 results are read-only. */
	override fun updateNull(p0: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBoolean(p0: Int, p1: Boolean) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateByte(p0: Int, p1: Byte) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateShort(p0: Int, p1: Short) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateInt(p0: Int, p1: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateLong(p0: Int, p1: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateFloat(p0: Int, p1: Float) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateDouble(p0: Int, p1: Double) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBigDecimal(p0: Int, p1: java.math.BigDecimal?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateString(p0: Int, p1: String?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBytes(p0: Int, p1: ByteArray?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateDate(p0: Int, p1: java.sql.Date?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateTime(p0: Int, p1: java.sql.Time?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateTimestamp(p0: Int, p1: java.sql.Timestamp?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateAsciiStream(p0: Int, p1: java.io.InputStream?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBinaryStream(p0: Int, p1: java.io.InputStream?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateCharacterStream(p0: Int, p1: java.io.Reader?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateObject(p0: Int, p1: Any?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateObject(p0: Int, p1: Any?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNull(p0: String?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBoolean(p0: String?, p1: Boolean) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateByte(p0: String?, p1: Byte) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateShort(p0: String?, p1: Short) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateInt(p0: String?, p1: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateLong(p0: String?, p1: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateFloat(p0: String?, p1: Float) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateDouble(p0: String?, p1: Double) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBigDecimal(p0: String?, p1: java.math.BigDecimal?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateString(p0: String?, p1: String?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBytes(p0: String?, p1: ByteArray?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateDate(p0: String?, p1: java.sql.Date?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateTime(p0: String?, p1: java.sql.Time?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateTimestamp(p0: String?, p1: java.sql.Timestamp?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateAsciiStream(p0: String?, p1: java.io.InputStream?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBinaryStream(p0: String?, p1: java.io.InputStream?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateCharacterStream(p0: String?, p1: java.io.Reader?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateObject(p0: String?, p1: Any?, p2: Int) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateObject(p0: String?, p1: Any?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun insertRow() = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateRow() = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun deleteRow() = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun refreshRow() = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun cancelRowUpdates() = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun moveToInsertRow() = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun moveToCurrentRow() = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateRef(p0: Int, p1: java.sql.Ref?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateRef(p0: String?, p1: java.sql.Ref?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBlob(p0: Int, p1: java.sql.Blob?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBlob(p0: String?, p1: java.sql.Blob?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateClob(p0: Int, p1: java.sql.Clob?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateClob(p0: String?, p1: java.sql.Clob?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateArray(p0: Int, p1: SqlArray?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateArray(p0: String?, p1: SqlArray?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateRowId(p0: Int, p1: java.sql.RowId?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateRowId(p0: String?, p1: java.sql.RowId?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNString(p0: Int, p1: String?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNString(p0: String?, p1: String?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNClob(p0: Int, p1: java.sql.NClob?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNClob(p0: String?, p1: java.sql.NClob?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateSQLXML(p0: Int, p1: java.sql.SQLXML?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateSQLXML(p0: String?, p1: java.sql.SQLXML?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNCharacterStream(p0: Int, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNCharacterStream(p0: String?, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateAsciiStream(p0: Int, p1: java.io.InputStream?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBinaryStream(p0: Int, p1: java.io.InputStream?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateCharacterStream(p0: Int, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateAsciiStream(p0: String?, p1: java.io.InputStream?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBinaryStream(p0: String?, p1: java.io.InputStream?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateCharacterStream(p0: String?, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBlob(p0: Int, p1: java.io.InputStream?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBlob(p0: String?, p1: java.io.InputStream?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateClob(p0: Int, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateClob(p0: String?, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNClob(p0: Int, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNClob(p0: String?, p1: java.io.Reader?, p2: Long) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNCharacterStream(p0: Int, p1: java.io.Reader?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNCharacterStream(p0: String?, p1: java.io.Reader?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateAsciiStream(p0: Int, p1: java.io.InputStream?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBinaryStream(p0: Int, p1: java.io.InputStream?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateCharacterStream(p0: Int, p1: java.io.Reader?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateAsciiStream(p0: String?, p1: java.io.InputStream?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBinaryStream(p0: String?, p1: java.io.InputStream?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateCharacterStream(p0: String?, p1: java.io.Reader?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBlob(p0: Int, p1: java.io.InputStream?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateBlob(p0: String?, p1: java.io.InputStream?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateClob(p0: Int, p1: java.io.Reader?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateClob(p0: String?, p1: java.io.Reader?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNClob(p0: Int, p1: java.io.Reader?) = throw notSupported("Updating result sets")

	/** Not supported, as D1 results are read-only. */
	override fun updateNClob(p0: String?, p1: java.io.Reader?) = throw notSupported("Updating result sets")

}
