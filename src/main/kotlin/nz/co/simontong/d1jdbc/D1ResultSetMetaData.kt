package nz.co.simontong.d1jdbc

import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Column details for rows returned by D1.
 *
 * @param columns column names in order
 * @param rows row values, used to guess each column's type
 */
class D1ResultSetMetaData(
	private val columns: List<String>,
	private val rows: List<List<JsonElement>>,
) : ResultSetMetaData {
	/**
	 * Throws when the column number is out of range.
	 *
	 * @param column column number, starting at 1
	 * @throws SQLException when out of range
	 */
	private fun checkColumn(column: Int) {
		if (column < 1 || column > columns.size) {
			throw SQLException("Column $column doesn't exist. There are ${columns.size} columns.", "42S22")
		}
	}

	/**
	 * Guesses a column's SQL type from its first non-null value.
	 *
	 * @param column column number
	 * @return a [Types] constant
	 */
	private fun guessedType(column: Int): Int {
		checkColumn(column)
		val sample = rows.asSequence().map { it[column - 1] }.firstOrNull { it !is JsonNull }
		return when {
			sample is JsonArray -> Types.BLOB
			sample is JsonPrimitive && sample.isString -> Types.VARCHAR
			sample is JsonPrimitive && sample.content.toLongOrNull() != null -> Types.BIGINT
			sample is JsonPrimitive -> Types.DOUBLE
			else -> Types.VARCHAR
		}
	}

	/** @return number of columns */
	override fun getColumnCount(): Int = columns.size

	/** @return false, as this isn't known from the reply */
	override fun isAutoIncrement(column: Int): Boolean {
		checkColumn(column)
		return false
	}

	/** @return true for text columns */
	override fun isCaseSensitive(column: Int): Boolean = guessedType(column) == Types.VARCHAR

	/** @return true, as any column can be used in a WHERE clause */
	override fun isSearchable(column: Int): Boolean {
		checkColumn(column)
		return true
	}

	/** @return false, as D1 has no currency type */
	override fun isCurrency(column: Int): Boolean {
		checkColumn(column)
		return false
	}

	/** @return [ResultSetMetaData.columnNullableUnknown], as this isn't known from the reply */
	override fun isNullable(column: Int): Int {
		checkColumn(column)
		return ResultSetMetaData.columnNullableUnknown
	}

	/** @return true for number columns */
	override fun isSigned(column: Int): Boolean = guessedType(column) in setOf(Types.BIGINT, Types.DOUBLE)

	/** @return a display width wide enough for most values */
	override fun getColumnDisplaySize(column: Int): Int {
		checkColumn(column)
		return 64
	}

	/** @return the column name as returned by D1 */
	override fun getColumnLabel(column: Int): String {
		checkColumn(column)
		return columns[column - 1]
	}

	/** @return the column name as returned by D1 */
	override fun getColumnName(column: Int): String = getColumnLabel(column)

	/** @return an empty string, as the source table isn't known from the reply */
	override fun getSchemaName(column: Int): String {
		checkColumn(column)
		return ""
	}

	/** @return 0, as precision isn't known from the reply */
	override fun getPrecision(column: Int): Int {
		checkColumn(column)
		return 0
	}

	/** @return 0, as scale isn't known from the reply */
	override fun getScale(column: Int): Int {
		checkColumn(column)
		return 0
	}

	/** @return an empty string, as the source table isn't known from the reply */
	override fun getTableName(column: Int): String {
		checkColumn(column)
		return ""
	}

	/** @return an empty string, as D1 has no catalogs */
	override fun getCatalogName(column: Int): String {
		checkColumn(column)
		return ""
	}

	/** @return the guessed [Types] constant */
	override fun getColumnType(column: Int): Int = guessedType(column)

	/** @return the SQLite type name matching the guessed type */
	override fun getColumnTypeName(column: Int): String {
		return when (guessedType(column)) {
			Types.BLOB -> "BLOB"
			Types.BIGINT -> "INTEGER"
			Types.DOUBLE -> "REAL"
			else -> "TEXT"
		}
	}

	/** @return true, as these result sets can't be changed */
	override fun isReadOnly(column: Int): Boolean {
		checkColumn(column)
		return true
	}

	/** @return false, as these result sets can't be changed */
	override fun isWritable(column: Int): Boolean {
		checkColumn(column)
		return false
	}

	/** @return false, as these result sets can't be changed */
	override fun isDefinitelyWritable(column: Int): Boolean {
		checkColumn(column)
		return false
	}

	/** @return the Java class [D1ResultSet.getObject] returns for the guessed type */
	override fun getColumnClassName(column: Int): String {
		return when (guessedType(column)) {
			Types.BLOB -> "[B"
			Types.BIGINT -> "java.lang.Long"
			Types.DOUBLE -> "java.lang.Double"
			else -> "java.lang.String"
		}
	}

	/**
	 * Returns this object as the given interface.
	 *
	 * @param iface interface to unwrap to
	 * @return this object
	 * @throws SQLException when it doesn't implement the interface
	 */
	override fun <T : Any?> unwrap(iface: Class<T>): T {
		if (!iface.isInstance(this)) {
			throw SQLException("The result set metadata is not a ${iface.name}.")
		}
		return iface.cast(this)
	}

	/** @return whether this object implements the interface */
	override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
