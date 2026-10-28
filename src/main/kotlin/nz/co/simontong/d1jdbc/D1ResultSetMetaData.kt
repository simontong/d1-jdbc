package nz.co.simontong.d1jdbc

import java.sql.ResultSetMetaData
import java.sql.SQLException

/**
 * Column details for rows returned by D1.
 *
 * @param columns column names in order
 * @param kinds each column's type, worked out from its values
 */
class D1ResultSetMetaData internal constructor(
	private val columns: List<String>,
	private val kinds: List<ColumnKind>,
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
	 * Returns a column's type.
	 *
	 * @param column column number
	 * @return the column's kind
	 * @throws SQLException when the column doesn't exist
	 */
	private fun kind(column: Int): ColumnKind {
		checkColumn(column)
		return kinds[column - 1]
	}

	/** @return number of columns */
	override fun getColumnCount(): Int = columns.size

	/** @return false, as this isn't known from the reply */
	override fun isAutoIncrement(column: Int): Boolean {
		checkColumn(column)
		return false
	}

	/** @return true for text columns */
	override fun isCaseSensitive(column: Int): Boolean = kind(column) == ColumnKind.TEXT

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
	override fun isSigned(column: Int): Boolean = kind(column) in setOf(ColumnKind.INTEGER, ColumnKind.REAL)

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

	/** @return the [java.sql.Types] constant for the column's values */
	override fun getColumnType(column: Int): Int = kind(column).sqlType

	/** @return the SQLite type name for the column's values */
	override fun getColumnTypeName(column: Int): String = kind(column).typeName

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

	/** @return the Java class [D1ResultSet.getObject] returns for the column */
	override fun getColumnClassName(column: Int): String = kind(column).className

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
