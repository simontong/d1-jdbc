package nz.co.simontong.d1jdbc

import java.sql.Types
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The type of a result column, worked out from its values, as D1 doesn't send declared types.
 *
 * @property sqlType [Types] constant
 * @property typeName SQLite type name
 * @property className Java class that getObject returns
 */
internal enum class ColumnKind(
	val sqlType: Int,
	val typeName: String,
	val className: String,
) {
	INTEGER(Types.BIGINT, "INTEGER", "java.lang.Long"),
	REAL(Types.DOUBLE, "REAL", "java.lang.Double"),
	TEXT(Types.VARCHAR, "TEXT", "java.lang.String"),

	// D1 never sends JSON booleans, so only the driver's own metadata results use this.
	BOOLEAN(Types.BOOLEAN, "BOOLEAN", "java.lang.Boolean"),

	BLOB(Types.BLOB, "BLOB", "[B"),
	ANY(Types.OTHER, "ANY", "java.lang.Object"),
	NULL(Types.NULL, "NULL", "java.lang.Object"),
}

/**
 * Works out a column's kind from every value in it.
 *
 * @param values the column's values, one per row
 * @return the kind that fits every non-null value
 */
internal fun columnKindOf(values: List<JsonElement>): ColumnKind {
	val kinds = values.filter { it !is JsonNull }.map { valueKindOf(it) }.toSet()
	return when {
		kinds.isEmpty() -> ColumnKind.NULL
		kinds == setOf(ColumnKind.INTEGER, ColumnKind.REAL) -> ColumnKind.REAL
		kinds.size == 1 -> kinds.single()
		else -> ColumnKind.ANY
	}
}

/**
 * Works out the kind of one value.
 *
 * @param value a non-null value
 * @return its kind
 */
internal fun valueKindOf(value: JsonElement): ColumnKind {
	return when {
		value is JsonArray -> ColumnKind.BLOB
		value !is JsonPrimitive -> ColumnKind.TEXT
		value.isString -> ColumnKind.TEXT
		value.booleanOrNull != null -> ColumnKind.BOOLEAN
		isWholeNumber(value.content) -> ColumnKind.INTEGER
		else -> ColumnKind.REAL
	}
}

/**
 * Says whether a JSON number is a whole number that fits in a Long.
 *
 * @param content the number as written in the JSON
 * @return true for a whole number
 */
private fun isWholeNumber(content: String): Boolean {
	return content.all { it.isDigit() || it == '-' } && content.toLongOrNull() != null
}
