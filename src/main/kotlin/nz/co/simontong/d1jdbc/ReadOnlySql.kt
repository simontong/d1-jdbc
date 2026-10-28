package nz.co.simontong.d1jdbc

/** Words that start a statement which only reads. */
private val READ_STARTS = setOf("select", "with", "pragma", "explain", "values")

/** Words that change data or schema anywhere in a statement. */
private val WRITE_WORDS = Regex(
	"""\b(insert|update|delete|replace|create|drop|alter|attach|detach|vacuum|reindex|analyze|""" +
		"""begin|commit|rollback|savepoint|release|upsert)\b""",
	RegexOption.IGNORE_CASE,
)

/** PRAGMA commands that only read. */
private val READ_PRAGMAS = setOf(
	"table_list", "table_info", "table_xinfo", "index_list", "index_info", "index_xinfo",
	"foreign_key_list", "foreign_key_check", "quick_check", "integrity_check",
	"collation_list", "function_list", "compile_options",
)

/** Comments, string literals and quoted names, which can hold any word. */
internal val QUOTED_OR_COMMENT = Regex("""--[^\n]*|/\*[\s\S]*?(\*/|$)|'(?:[^']|'')*'|"(?:[^"]|"")*"|`[^`]*`|\[[^\]]*]""")

/**
 * Says whether SQL only reads data, as a safety net for read-only connections.
 *
 * @param sql SQL text, which may hold several statements
 * @return true when every statement only reads
 */
internal fun isReadOnlySql(sql: String): Boolean {
	val bare = QUOTED_OR_COMMENT.replace(sql, " ")
	if (WRITE_WORDS.containsMatchIn(bare)) {
		return false
	}
	return bare.split(";").map { it.trim() }.filter { it.isNotEmpty() }.all { isReadOnlyStatement(it) }
}

/**
 * Says whether one statement, with quotes and comments removed, only reads.
 *
 * @param statement one statement
 * @return true when it starts with a read word and any PRAGMA is a read-only one
 */
private fun isReadOnlyStatement(statement: String): Boolean {
	val words = statement.split(Regex("""[\s(]+""")).filter { it.isNotEmpty() }
	val first = words.first().lowercase()
	if (first !in READ_STARTS) {
		return false
	}
	if (first != "pragma") {
		return true
	}
	val pragmaName = words.getOrNull(1)?.substringAfterLast(".")?.lowercase()
	return !statement.contains("=") && pragmaName in READ_PRAGMAS
}
