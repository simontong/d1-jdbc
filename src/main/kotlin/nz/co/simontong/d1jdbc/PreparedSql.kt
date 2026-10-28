package nz.co.simontong.d1jdbc

/** A `?` or `?NNN` placeholder, or text that can't hold one. */
private val PLACEHOLDER_OR_QUOTED = Regex("""${QUOTED_OR_COMMENT.pattern}|\?(\d*)""")

/**
 * One placeholder found in SQL.
 *
 * @property range where it sits in the SQL text
 * @property number parameter number SQLite gives it
 */
private data class Placeholder(
	val range: IntRange,
	val number: Int,
)

/**
 * SQL with `?` and `?NNN` placeholders, numbered the way SQLite numbers them.
 *
 * @param sql SQL text
 */
internal class PreparedSql(
	private val sql: String,
) {
	private val placeholders: List<Placeholder>

	/** The highest parameter number, which is how many values the SQL needs. */
	val parameterCount: Int

	init {
		// A bare ? takes one more than the highest number used before it.
		var highest = 0
		placeholders = PLACEHOLDER_OR_QUOTED.findAll(sql)
			.filter { it.value.startsWith("?") }
			.map { match ->
				val digits = match.value.drop(1)
				val number = if (digits.isEmpty()) highest + 1 else digits.toInt()
				highest = maxOf(highest, number)
				Placeholder(match.range, number)
			}
			.toList()
		parameterCount = highest
	}

	/**
	 * Builds the SQL to send, casting whole-number placeholders so D1 stores exact integers.
	 *
	 * @param wholeNumbers parameter numbers that hold whole numbers sent as text
	 * @return SQL text for D1
	 */
	fun render(wholeNumbers: Set<Int>): String {
		val builder = StringBuilder()
		var position = 0
		for (placeholder in placeholders) {
			if (placeholder.number !in wholeNumbers) {
				continue
			}
			builder.append(sql, position, placeholder.range.first)
			builder.append("cast(?${placeholder.number} as integer)")
			position = placeholder.range.last + 1
		}
		builder.append(sql, position, sql.length)
		return builder.toString()
	}
}
