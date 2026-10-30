package nz.co.simontong.d1jdbc

import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types

/**
 * Column details for rows returned by D1.
 *
 * @param columns column names in order
 * @param kinds each column's type, worked out from its values
 * @param sources each column's source table, null where unknown
 */
class D1ResultSetMetaData internal constructor(
	private val columns: List<String>,
	private val kinds: List<ColumnKind>,
	private val sources: List<ColumnSource?> = columns.map { null },
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
	 * Returns a column's type worked out from its values.
	 *
	 * @param column column number
	 * @return the column's kind
	 * @throws SQLException when the column doesn't exist
	 */
	private fun kind(column: Int): ColumnKind {
		checkColumn(column)
		return kinds[column - 1]
	}

	/**
	 * Returns where a column's values come from.
	 *
	 * @param column column number
	 * @return the source, or null when unknown
	 * @throws SQLException when the column doesn't exist
	 */
	private fun source(column: Int): ColumnSource? {
		checkColumn(column)
		return sources[column - 1]
	}

	/**
	 * Returns a column's declared type, or the row ID's type.
	 *
	 * @param column column number
	 * @return the declared type, or null when the source is unknown or has no declared type
	 * @throws SQLException when the column doesn't exist
	 */
	private fun declared(column: Int): DeclaredType? {
		val source = source(column) ?: return null
		val declaredText = source.column?.declaredType ?: "INTEGER"
		return declaredText.takeIf { it.isNotBlank() }?.let { declaredType(it) }
	}

	/**
	 * Says whether an IDE can change a column's values with UPDATE.
	 *
	 * @param column column number
	 * @return true for a table column that isn't generated
	 * @throws SQLException when the column doesn't exist
	 */
	private fun canWrite(column: Int): Boolean {
		val source = source(column) ?: return false
		return source.table.type == "TABLE" && source.column != null && !source.column.generated
	}

	/** @return number of columns */
	override fun getColumnCount(): Int = columns.size

	/** @return true for the row ID and for a column that is an alias for it */
	override fun isAutoIncrement(column: Int): Boolean {
		val source = source(column) ?: return false
		return source.column == null || source.column == source.table.rowIdAlias
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

	/** @return whether the column allows NULL, or unknown when its table isn't known */
	override fun isNullable(column: Int): Int {
		val source = source(column) ?: return ResultSetMetaData.columnNullableUnknown
		val sourceColumn = source.column ?: return ResultSetMetaData.columnNoNulls
		val notNull = sourceColumn.notNull || sourceColumn == source.table.rowIdAlias
		return if (notNull) ResultSetMetaData.columnNoNulls else ResultSetMetaData.columnNullable
	}

	/** @return true for number columns */
	override fun isSigned(column: Int): Boolean {
		val type = declared(column)?.sqlType ?: kind(column).sqlType
		return type in setOf(Types.BIGINT, Types.DOUBLE, Types.NUMERIC)
	}

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

	/** @return the table column's name, or the label when the source is unknown */
	override fun getColumnName(column: Int): String {
		val source = source(column) ?: return getColumnLabel(column)
		return source.column?.name ?: "rowid"
	}

	/** @return `main` when the source table is known, otherwise an empty string */
	override fun getSchemaName(column: Int): String = if (source(column) != null) "main" else ""

	/** @return the size in the declared type, or 0 */
	override fun getPrecision(column: Int): Int = declared(column)?.size ?: 0

	/** @return the digits after the point in the declared type, or 0 */
	override fun getScale(column: Int): Int = declared(column)?.decimalDigits ?: 0

	/** @return the source table's name, or an empty string when unknown */
	override fun getTableName(column: Int): String = source(column)?.table?.name ?: ""

	/** @return an empty string, as D1 has no catalogs */
	override fun getCatalogName(column: Int): String {
		checkColumn(column)
		return ""
	}

	/** @return the [Types] constant for the declared type, or for the column's values */
	override fun getColumnType(column: Int): Int = declared(column)?.sqlType ?: kind(column).sqlType

	/** @return the declared type's name, or the SQLite type name for the column's values */
	override fun getColumnTypeName(column: Int): String = declared(column)?.name ?: kind(column).typeName

	/** @return false for a table column that isn't generated, true otherwise */
	override fun isReadOnly(column: Int): Boolean = !canWrite(column)

	/** @return true for a table column that isn't generated */
	override fun isWritable(column: Int): Boolean = canWrite(column)

	/** @return false, as a write can still fail on a constraint */
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
		return unwrapAs(this, iface, "The result set metadata")
	}

	/** @return whether this object implements the interface */
	override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
