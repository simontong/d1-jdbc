package nz.co.simontong.d1jdbc

/**
 * Where a result column's values come from.
 *
 * @property table table or view the column belongs to
 * @property column the table column, or null for the row ID
 */
internal data class ColumnSource(
	val table: SchemaTable,
	val column: SchemaColumn?,
)

/**
 * One piece of SQL text.
 *
 * @property text the token, with quotes removed from quoted names
 * @property kind what sort of token it is
 */
internal data class Token(
	val text: String,
	val kind: TokenKind,
)

/** Sorts of SQL token the reader tells apart. */
internal enum class TokenKind {
	WORD,
	QUOTED_NAME,
	SYMBOL,
	OTHER,
}

/** Words that end a table reference or show the rows aren't plain table rows. */
private val KEYWORDS = setOf(
	"where", "order", "limit", "group", "having", "window", "join", "inner", "left", "right", "full", "outer",
	"cross", "natural", "union", "intersect", "except", "on", "using", "as", "from", "select", "values", "offset",
	"indexed", "not",
)

/** Words that mean the result rows don't each match one table row. */
private val NOT_ONE_TABLE = setOf("join", "union", "intersect", "except", "group", "having", "window")

/** Names SQLite accepts for the row ID. */
private val ROW_ID_NAMES = setOf("rowid", "oid", "_rowid_")

/**
 * Splits SQL into tokens, dropping comments and whitespace.
 *
 * @param sql SQL text
 * @return the tokens in order
 */
internal fun tokens(sql: String): List<Token> {
	val found = mutableListOf<Token>()
	var index = 0
	while (index < sql.length) {
		val char = sql[index]
		val rest = sql.substring(index)
		when {
			char.isWhitespace() -> index++
			rest.startsWith("--") -> index = sql.indexOf('\n', index).let { if (it < 0) sql.length else it }
			rest.startsWith("/*") -> index = sql.indexOf("*/", index + 2).let { if (it < 0) sql.length else it + 2 }
			char == '"' || char == '`' || char == '[' -> {
				val close = if (char == '[') ']' else char
				val end = quoteEnd(sql, index, close)
				val inner = sql.substring(index + 1, end).replace("$close$close", "$close")
				found += Token(inner, TokenKind.QUOTED_NAME)
				index = end + 1
			}
			char == '\'' -> {
				val end = quoteEnd(sql, index, '\'')
				found += Token(sql.substring(index, end + 1), TokenKind.OTHER)
				index = end + 1
			}
			char.isLetter() || char == '_' -> {
				var end = index
				while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_' || sql[end] == '$')) {
					end++
				}
				found += Token(sql.substring(index, end), TokenKind.WORD)
				index = end
			}
			char in ",.*();" -> {
				found += Token(char.toString(), TokenKind.SYMBOL)
				index++
			}
			else -> {
				found += Token(char.toString(), TokenKind.OTHER)
				index++
			}
		}
	}
	return found
}

/**
 * Finds the closing quote, where a doubled quote stands for one quote.
 *
 * @param sql SQL text
 * @param start position of the opening quote
 * @param close closing quote character
 * @return position of the closing quote, or the last position when there's none
 */
private fun quoteEnd(sql: String, start: Int, close: Char): Int {
	var index = start + 1
	while (index < sql.length) {
		if (sql[index] == close) {
			if (close != ']' && index + 1 < sql.length && sql[index + 1] == close) {
				index += 2
				continue
			}
			return index
		}
		index++
	}
	return sql.length - 1
}

/**
 * Says whether a token is a word with the given text, ignoring case.
 *
 * @param word word to compare with
 * @return true when it matches
 */
internal fun Token.isWord(word: String): Boolean = kind == TokenKind.WORD && text.equals(word, ignoreCase = true)

/**
 * Says whether a token can be a table, column or alias name.
 *
 * @return true for a quoted name or a word that isn't a keyword
 */
private fun Token.isName(): Boolean {
	return kind == TokenKind.QUOTED_NAME || (kind == TokenKind.WORD && text.lowercase() !in KEYWORDS)
}

/**
 * Splits tokens on commas that aren't inside brackets.
 *
 * @param tokens tokens to split
 * @return the pieces between commas
 */
internal fun splitOnCommas(tokens: List<Token>): List<List<Token>> {
	val pieces = mutableListOf(mutableListOf<Token>())
	var depth = 0
	for (token in tokens) {
		when {
			token.text == "(" && token.kind == TokenKind.SYMBOL -> depth++
			token.text == ")" && token.kind == TokenKind.SYMBOL -> depth--
		}
		if (depth == 0 && token.text == "," && token.kind == TokenKind.SYMBOL) {
			pieces += mutableListOf<Token>()
		} else {
			pieces.last() += token
		}
	}
	return pieces
}

/**
 * Works out how deep inside brackets each token is.
 *
 * @param tokens tokens to measure
 * @return bracket depth per token, where brackets themselves count as inside
 */
internal fun depths(tokens: List<Token>): List<Int> {
	var depth = 0
	return tokens.map { token ->
		if (token.kind == TokenKind.SYMBOL && token.text == "(") {
			depth++
			return@map depth
		}
		val current = if (token.kind == TokenKind.SYMBOL && token.text == ")") depth-- else depth
		current
	}
}

/**
 * Works out which table column each result column comes from, for a SELECT from one table.
 *
 * @param sql SQL that made the result
 * @param columnCount number of result columns
 * @param schema the database schema
 * @return one source per result column, null where unknown, or null if not a one-table SELECT
 */
internal fun columnSources(sql: String, columnCount: Int, schema: D1Schema): List<ColumnSource?>? {
	val all = tokens(sql).dropLastWhile { it.kind == TokenKind.SYMBOL && it.text == ";" }
	if (all.none { it.isWord("select") } || all.any { it.kind == TokenKind.SYMBOL && it.text == ";" }) {
		return null
	}
	if (!all.first().isWord("select") || all.getOrNull(1)?.isWord("distinct") == true) {
		return null
	}

	val depth = depths(all)
	val top = all.indices.filter { depth[it] == 0 }
	if (top.any { index -> NOT_ONE_TABLE.any { all[index].isWord(it) } }) {
		return null
	}
	val fromIndex = top.firstOrNull { all[it].isWord("from") } ?: return null
	val skip = if (all[1].isWord("all")) 2 else 1
	val items = splitOnCommas(all.subList(skip, fromIndex))
	val (table, alias) = tableReference(all.drop(fromIndex + 1), schema) ?: return null

	val sources = items.flatMap { item -> itemSources(item, table, alias) ?: return null }
	return sources.takeIf { it.size == columnCount }
}

/**
 * Reads the table after FROM and its alias, checking nothing but WHERE, ORDER BY or LIMIT follows.
 *
 * @param tokens tokens after FROM
 * @param schema the database schema
 * @return the table and its alias, or null when it isn't a single known table
 */
private fun tableReference(tokens: List<Token>, schema: D1Schema): Pair<SchemaTable, String?>? {
	var index = 0
	var name = tokens.getOrNull(index++)?.takeIf { it.isName() } ?: return null
	if (tokens.getOrNull(index)?.text == ".") {
		if (!name.text.equals("main", ignoreCase = true)) {
			return null
		}
		name = tokens.getOrNull(index + 1)?.takeIf { it.isName() } ?: return null
		index += 2
	}
	val table = schema.table(name.text) ?: return null

	var alias: String? = null
	if (tokens.getOrNull(index)?.isWord("as") == true) {
		alias = tokens.getOrNull(index + 1)?.takeIf { it.isName() }?.text ?: return null
		index += 2
	} else if (tokens.getOrNull(index)?.isName() == true) {
		alias = tokens[index].text
		index++
	}

	val next = tokens.getOrNull(index) ?: return table to alias
	val allowed = next.isWord("where") || next.isWord("order") || next.isWord("limit")
	return if (allowed) table to alias else null
}

/**
 * Works out the sources of one select list item.
 *
 * @param item the item's tokens
 * @param table table after FROM
 * @param alias the table's alias, or null
 * @return one source per column the item makes, null entries where unknown, or null when unreadable
 */
private fun itemSources(item: List<Token>, table: SchemaTable, alias: String?): List<ColumnSource?>? {
	val texts = item.map { it.text }

	/**
	 * Says whether a qualifier names the table.
	 *
	 * @param qualifier name before the dot
	 * @return true when it is the table or its alias
	 */
	fun isTable(qualifier: Token): Boolean {
		val name = alias ?: table.name
		return qualifier.isName() && qualifier.text.equals(name, ignoreCase = true)
	}

	// SQLite's * includes generated columns, so every column is listed.
	if (texts == listOf("*") || (texts.size == 3 && texts.drop(1) == listOf(".", "*") && isTable(item[0]))) {
		return table.columns.map { ColumnSource(table, it) }
	}

	var rest = item
	if (rest.size >= 3 && rest[1].text == "." && rest[1].kind == TokenKind.SYMBOL) {
		if (!isTable(rest[0])) {
			return listOf(null)
		}
		rest = rest.drop(2)
	}
	val labelled = when {
		rest.size == 1 -> true
		rest.size == 2 -> rest[1].isName()
		rest.size == 3 -> rest[1].isWord("as") && rest[2].isName()
		else -> false
	}
	if (!labelled || !rest[0].isName()) {
		return listOf(null)
	}
	val name = rest[0].text
	val column = table.columns.firstOrNull { it.name.equals(name, ignoreCase = true) }
	if (column != null) {
		return listOf(ColumnSource(table, column))
	}
	val isRowId = name.lowercase() in ROW_ID_NAMES && table.type == "TABLE" && !table.withoutRowId
	if (!isRowId) {
		return listOf(null)
	}
	// A table with an INTEGER PRIMARY KEY stores the row ID in that column.
	return listOf(ColumnSource(table, table.rowIdAlias))
}
