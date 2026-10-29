package nz.co.simontong.d1jdbc

/**
 * A foreign key that CREATE TABLE gives a name.
 *
 * @property name the key's name
 * @property columns child columns in order
 * @property parentTable table the key refers to
 */
internal data class NamedForeignKey(
	val name: String,
	val columns: List<String>,
	val parentTable: String,
)

/**
 * The key names written in a CREATE TABLE statement, as SQLite's PRAGMAs don't report them.
 *
 * @property primaryKey primary key name, or null
 * @property foreignKeys foreign keys that have names
 */
internal data class ConstraintNames(
	val primaryKey: String?,
	val foreignKeys: List<NamedForeignKey>,
)

/** Words that start an unnamed table constraint. */
private val TABLE_CONSTRAINT_WORDS = setOf("primary", "unique", "check", "foreign")

/**
 * Says whether a token is a symbol with the given text.
 *
 * @param symbol symbol to compare with
 * @return true when it matches
 */
private fun Token.isSymbol(symbol: String): Boolean = kind == TokenKind.SYMBOL && text == symbol

/**
 * Says whether a token can be a name.
 *
 * @return true for a quoted name or a word
 */
private fun Token.canBeName(): Boolean = kind == TokenKind.QUOTED_NAME || kind == TokenKind.WORD

/**
 * Reads the key names from a CREATE TABLE statement.
 *
 * @param createSql the table's SQL from sqlite_schema, or null
 * @return the names found, empty when the SQL isn't a plain column list
 */
internal fun constraintNames(createSql: String?): ConstraintNames {
	val none = ConstraintNames(null, emptyList())
	val all = tokens(createSql.orEmpty())
	val open = all.indexOfFirst { it.isSymbol("(") }
	if (open < 0 || all.subList(0, open).any { it.isWord("as") || it.isWord("using") }) {
		return none
	}
	val depth = depths(all)
	val close = (open + 1 until all.size).firstOrNull { all[it].isSymbol(")") && depth[it] == 1 } ?: all.size

	var primaryKey: String? = null
	val foreignKeys = mutableListOf<NamedForeignKey>()
	for (definition in splitOnCommas(all.subList(open + 1, close))) {
		val first = definition.firstOrNull() ?: continue
		val isTableConstraint = first.isWord("constraint") || TABLE_CONSTRAINT_WORDS.any { first.isWord(it) }
		for (index in definition.indices) {
			if (!definition[index].isWord("constraint")) {
				continue
			}
			val name = definition.getOrNull(index + 1)?.takeIf { it.canBeName() }?.text ?: continue
			val next = definition.getOrNull(index + 2) ?: continue
			when {
				next.isWord("primary") -> primaryKey = name

				next.isWord("references") && !isTableConstraint -> {
					val parent = definition.getOrNull(index + 3)?.takeIf { it.canBeName() } ?: continue
					foreignKeys += NamedForeignKey(name, listOf(first.text), parent.text)
				}

				next.isWord("foreign") -> foreignKeys += namedTableForeignKey(name, definition.drop(index + 3)) ?: continue
			}
		}
	}
	return ConstraintNames(primaryKey, foreignKeys)
}

/**
 * Reads the columns and parent of a FOREIGN KEY table constraint.
 *
 * @param name the key's name
 * @param tokens tokens after FOREIGN, starting with KEY
 * @return the key, or null when it can't be read
 */
private fun namedTableForeignKey(name: String, tokens: List<Token>): NamedForeignKey? {
	if (tokens.getOrNull(0)?.isWord("key") != true || tokens.getOrNull(1)?.isSymbol("(") != true) {
		return null
	}
	val close = tokens.indexOfFirst { it.isSymbol(")") }.takeIf { it > 1 } ?: return null
	val columns = tokens.subList(2, close).filterNot { it.isSymbol(",") }
	if (columns.any { !it.canBeName() } || tokens.getOrNull(close + 1)?.isWord("references") != true) {
		return null
	}
	val parent = tokens.getOrNull(close + 2)?.takeIf { it.canBeName() } ?: return null
	return NamedForeignKey(name, columns.map { it.text }, parent.text)
}
