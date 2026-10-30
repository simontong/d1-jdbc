package nz.co.simontong.d1jdbc

import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.Statement
import java.time.Duration
import kotlinx.serialization.json.JsonPrimitive

/**
 * A JDBC statement that sends SQL to D1.
 *
 * @param connection connection the statement belongs to
 */
open class D1Statement(
	private val connection: D1Connection,
) : Statement {
	private var closed = false
	private var results: List<D1Result> = emptyList()
	private var resultQueries: List<D1Query> = emptyList()
	private var resultIndex = 0
	private var currentResultSet: D1ResultSet? = null
	private var maxRows = 0
	private var queryTimeoutSeconds = 0
	private var closeOnCompletion = false
	private val batch = mutableListOf<D1Query>()
	private var keysForNextRun = false
	private var generatedKeys: List<Long> = emptyList()

	/** Whether every run should keep the new row's ID, as a prepared statement can ask for. */
	protected open val alwaysReturnKeys = false

	/**
	 * Throws when the statement or its connection is closed.
	 *
	 * @throws SQLException when closed
	 */
	protected fun checkOpen() {
		if (closed) {
			throw SQLException("The statement is closed.", "HY010")
		}
		connection.checkOpen()
	}

	/**
	 * Sends queries to D1 and makes the first result current.
	 *
	 * @param queries queries to send
	 * @return true when the first result has rows to read
	 * @throws SQLException when D1 reports an error
	 */
	protected fun run(queries: List<D1Query>): Boolean {
		closeCurrentResultSet()
		val wantKeys = keysForNextRun || alwaysReturnKeys
		keysForNextRun = false
		generatedKeys = emptyList()
		results = send(queries)
		val inserted = results.singleOrNull()?.takeIf { queries.size == 1 && isInsert(queries[0].sql) && it.changes > 0 }
		if (wantKeys && inserted != null) {
			generatedKeys = listOf(inserted.lastRowId)
		}
		// Several statements in one SQL string give results that can't be matched to their SQL.
		resultQueries = if (results.size == queries.size) queries else emptyList()
		resultIndex = 0
		return openCurrentResult()
	}

	/**
	 * Sends queries to D1, applying read-only mode and the query time limit.
	 *
	 * @param queries queries to send
	 * @return one result per query
	 * @throws SQLException when a write is blocked or D1 reports an error
	 */
	private fun send(queries: List<D1Query>): List<D1Result> {
		checkOpen()
		val writes = queries.any { !isReadOnlySql(it.sql) }
		if (connection.isReadOnly && writes) {
			throw SQLException("This connection is read-only, so statements that change data are blocked.", "25006")
		}
		if (writes) {
			// The statement may change the schema, so load it again next time.
			connection.schemaCache.clear()
		}
		val timeout = if (queryTimeoutSeconds > 0) Duration.ofSeconds(queryTimeoutSeconds.toLong()) else null
		val sent = try {
			connection.execute(queries, timeout)
		} catch (error: SQLException) {
			// SQLite names a missing table or column, which means the saved schema is out of date.
			if (STALE_SCHEMA_ERRORS.any { error.message.orEmpty().contains(it) }) {
				connection.schemaCache.clear()
			}
			throw error
		}
		if (writes) {
			connection.changedSinceCommit = true
		}
		return sent
	}

	/**
	 * Opens a result set for the current result when it has columns.
	 *
	 * @return true when the current result is a result set
	 */
	private fun openCurrentResult(): Boolean {
		val result = results.getOrNull(resultIndex) ?: return false
		if (result.columns.isEmpty()) {
			return false
		}
		val rows = if (maxRows > 0) result.rows.take(maxRows) else result.rows
		val sql = resultQueries.getOrNull(resultIndex)?.sql
		// Keep the database the query ran on, in case another one is chosen before the IDE asks.
		val cache = connection.schemaCache
		val findSources = sql?.let { { findColumnSources(it, result.columns, cache) } }
		currentResultSet = D1ResultSet(this, result.columns, rows, findSources)
		return true
	}

	/**
	 * Works out each result column's table column, loading the schema again once if it's out of date.
	 *
	 * @param sql SQL that made the result
	 * @param columns the result's column names
	 * @param cache schema cache of the database the query ran on
	 * @return one source per column, or null when the result isn't one table's rows
	 * @throws SQLException when the schema can't be loaded
	 */
	private fun findColumnSources(sql: String, columns: List<String>, cache: SchemaCache): List<ColumnSource?>? {
		val sources = columnSources(sql, cache.get()) ?: return null
		if (sourcesFit(sources, columns)) {
			return sources
		}
		// Another tool may have changed the table since the schema was loaded.
		cache.clear()
		return columnSources(sql, cache.get())?.takeIf { sourcesFit(it, columns) }
	}

	/** Closes the open result set, if any. */
	private fun closeCurrentResultSet() {
		currentResultSet?.close()
		currentResultSet = null
	}

	/**
	 * Runs SQL that returns rows.
	 *
	 * @param sql SQL text
	 * @return the rows
	 * @throws SQLException when the SQL doesn't return rows
	 */
	override fun executeQuery(sql: String): ResultSet = runQuery(D1Query(sql))

	/**
	 * Runs a query that returns rows.
	 *
	 * @param query query to run
	 * @return the rows
	 * @throws SQLException when the query doesn't return rows
	 */
	protected fun runQuery(query: D1Query): ResultSet {
		if (!run(listOf(query))) {
			throw SQLException("The SQL did not return rows. Use executeUpdate or execute instead.", "07000")
		}
		return currentResultSet!!
	}

	/**
	 * Runs SQL that changes data.
	 *
	 * @param sql SQL text
	 * @return number of rows changed
	 * @throws SQLException when the SQL returns rows
	 */
	override fun executeUpdate(sql: String): Int = runUpdate(D1Query(sql))

	/**
	 * Runs a query that changes data.
	 *
	 * @param query query to run
	 * @return number of rows changed
	 * @throws SQLException when the query returns rows
	 */
	protected fun runUpdate(query: D1Query): Int {
		if (run(listOf(query))) {
			closeCurrentResultSet()
			throw SQLException("The SQL returned rows. Use executeQuery or execute instead.", "07000")
		}
		return results.first().changes.toInt()
	}

	/**
	 * Runs any SQL.
	 *
	 * @param sql SQL text
	 * @return true when the first result has rows to read
	 * @throws SQLException when D1 reports an error
	 */
	override fun execute(sql: String): Boolean {
		return run(listOf(D1Query(sql)))
	}

	/** @return the current result set, or null when the current result is an update count */
	override fun getResultSet(): ResultSet? {
		checkOpen()
		return currentResultSet
	}

	/** @return the current update count, or -1 for rows or when there are no more results */
	override fun getUpdateCount(): Int {
		checkOpen()
		val result = results.getOrNull(resultIndex)
		if (result == null || result.columns.isNotEmpty()) {
			return -1
		}
		return result.changes.toInt()
	}

	/**
	 * Moves to the next result and closes the current result set.
	 *
	 * @return true when the next result is a result set
	 */
	override fun getMoreResults(): Boolean {
		checkOpen()
		closeCurrentResultSet()
		if (resultIndex < results.size) {
			resultIndex++
		}
		return openCurrentResult()
	}

	/**
	 * Moves to the next result, closing the current result set whatever the setting.
	 *
	 * @param current what to do with the current result set, ignored
	 * @return true when the next result is a result set
	 */
	override fun getMoreResults(current: Int): Boolean = getMoreResults()

	/**
	 * Throws unless the flag is one of the two JDBC allows.
	 *
	 * @param autoGeneratedKeys flag to check
	 * @return true when the new row's ID should be kept
	 * @throws SQLException for any other value
	 */
	private fun wantsKeys(autoGeneratedKeys: Int): Boolean {
		if (autoGeneratedKeys != Statement.RETURN_GENERATED_KEYS && autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
			throw SQLException("autoGeneratedKeys must be RETURN_GENERATED_KEYS or NO_GENERATED_KEYS.", "HY024")
		}
		return autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS
	}

	/**
	 * Runs SQL that changes data, keeping the new row's ID when asked.
	 *
	 * @param sql SQL text
	 * @param autoGeneratedKeys [Statement.RETURN_GENERATED_KEYS] or [Statement.NO_GENERATED_KEYS]
	 * @return number of rows changed
	 * @throws SQLException when the SQL returns rows or D1 reports an error
	 */
	override fun executeUpdate(sql: String, autoGeneratedKeys: Int): Int {
		keysForNextRun = wantsKeys(autoGeneratedKeys)
		return executeUpdate(sql)
	}

	/** Not supported, as D1 only reports the new row's ID. Use RETURN_GENERATED_KEYS. */
	override fun executeUpdate(sql: String?, columnIndexes: IntArray?): Int = throw notSupported("Generated keys by column")

	/** Not supported, as D1 only reports the new row's ID. Use RETURN_GENERATED_KEYS. */
	override fun executeUpdate(sql: String?, columnNames: Array<out String>?): Int = throw notSupported("Generated keys by column")

	/**
	 * Runs any SQL, keeping the new row's ID when asked.
	 *
	 * @param sql SQL text
	 * @param autoGeneratedKeys [Statement.RETURN_GENERATED_KEYS] or [Statement.NO_GENERATED_KEYS]
	 * @return true when the first result has rows to read
	 * @throws SQLException when D1 reports an error
	 */
	override fun execute(sql: String, autoGeneratedKeys: Int): Boolean {
		keysForNextRun = wantsKeys(autoGeneratedKeys)
		return execute(sql)
	}

	/** Not supported, as D1 only reports the new row's ID. Use RETURN_GENERATED_KEYS. */
	override fun execute(sql: String?, columnIndexes: IntArray?): Boolean = throw notSupported("Generated keys by column")

	/** Not supported, as D1 only reports the new row's ID. Use RETURN_GENERATED_KEYS. */
	override fun execute(sql: String?, columnNames: Array<out String>?): Boolean = throw notSupported("Generated keys by column")

	/**
	 * Returns the row ID of the row the last INSERT made, when keys were asked for.
	 *
	 * @return one row with the ID, or no rows
	 * @throws SQLException when the statement is closed
	 */
	override fun getGeneratedKeys(): ResultSet {
		checkOpen()
		return D1ResultSet(null, listOf("last_insert_rowid()"), generatedKeys.map { listOf(JsonPrimitive(it)) })
	}

	/**
	 * Adds SQL to the batch.
	 *
	 * @param sql SQL text
	 * @throws SQLException when the statement is closed
	 */
	override fun addBatch(sql: String) {
		checkOpen()
		addToBatch(D1Query(sql))
	}

	/**
	 * Adds a query to the batch.
	 *
	 * @param query query to add
	 */
	protected fun addToBatch(query: D1Query) {
		batch += query
	}

	/**
	 * Empties the batch.
	 *
	 * @throws SQLException when the statement is closed
	 */
	override fun clearBatch() {
		checkOpen()
		batch.clear()
	}

	/**
	 * Runs the batch in one request, which D1 saves in full or not at all.
	 *
	 * @return changed-row count for each statement
	 * @throws BatchUpdateException when the batch fails
	 */
	override fun executeBatch(): IntArray {
		checkOpen()
		val queries = batch.toList()
		batch.clear()
		generatedKeys = emptyList()
		if (queries.isEmpty()) {
			return IntArray(0)
		}

		val batchResults = try {
			send(queries)
		} catch (error: SQLException) {
			throw BatchUpdateException(
				"The batch failed and D1 saved none of it. ${error.message}",
				error.sqlState,
				error.errorCode,
				IntArray(0),
				error,
			)
		}
		return batchResults.map { it.changes.toInt() }.toIntArray()
	}

	/** Closes the statement and its result set. */
	override fun close() {
		closeCurrentResultSet()
		closed = true
	}

	/** @return whether the statement is closed */
	override fun isClosed(): Boolean = closed

	/** @return 0, meaning no limit on column size */
	override fun getMaxFieldSize(): Int {
		checkOpen()
		return 0
	}

	/**
	 * Ignored, as D1 always returns whole values.
	 *
	 * @param max size limit
	 */
	override fun setMaxFieldSize(max: Int) {
		checkOpen()
	}

	/** @return the row limit, where 0 means no limit */
	override fun getMaxRows(): Int {
		checkOpen()
		return maxRows
	}

	/**
	 * Sets the most rows a result set will hold.
	 *
	 * @param max row limit, where 0 means no limit
	 * @throws SQLException when the limit is negative
	 */
	override fun setMaxRows(max: Int) {
		checkOpen()
		if (max < 0) {
			throw SQLException("The row limit can't be negative.", "HY024")
		}
		maxRows = max
	}

	/**
	 * Ignored, as the driver passes SQL through unchanged.
	 *
	 * @param enable whether to process escapes
	 */
	override fun setEscapeProcessing(enable: Boolean) {
		checkOpen()
	}

	/** @return the query time limit in seconds, where 0 means no limit */
	override fun getQueryTimeout(): Int {
		checkOpen()
		return queryTimeoutSeconds
	}

	/**
	 * Sets the query time limit.
	 *
	 * @param seconds time limit in seconds, where 0 means no limit
	 * @throws SQLException when the limit is negative
	 */
	override fun setQueryTimeout(seconds: Int) {
		checkOpen()
		if (seconds < 0) {
			throw SQLException("The query time limit can't be negative.", "HY024")
		}
		queryTimeoutSeconds = seconds
	}

	/** Not supported, as a request sent to D1 can't be stopped. */
	override fun cancel() = throw notSupported("Cancelling statements")

	/** @return null, as statements raise no warnings */
	override fun getWarnings(): SQLWarning? {
		checkOpen()
		return null
	}

	/** Does nothing, as statements raise no warnings. */
	override fun clearWarnings() {
		checkOpen()
	}

	/** Not supported. */
	override fun setCursorName(name: String?) = throw notSupported("Named cursors")

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
	 * Ignored, as D1 returns every row in one reply.
	 *
	 * @param rows fetch size hint
	 */
	override fun setFetchSize(rows: Int) {
		checkOpen()
	}

	/** @return 0, as D1 returns every row in one reply */
	override fun getFetchSize(): Int {
		checkOpen()
		return 0
	}

	/** @return [ResultSet.CONCUR_READ_ONLY] */
	override fun getResultSetConcurrency(): Int = ResultSet.CONCUR_READ_ONLY

	/** @return [ResultSet.TYPE_FORWARD_ONLY] */
	override fun getResultSetType(): Int = ResultSet.TYPE_FORWARD_ONLY

	/** @return [ResultSet.HOLD_CURSORS_OVER_COMMIT] */
	override fun getResultSetHoldability(): Int = ResultSet.HOLD_CURSORS_OVER_COMMIT

	/** @return the connection that made this statement */
	override fun getConnection(): Connection {
		checkOpen()
		return connection
	}

	/**
	 * Ignored, as the driver has no statement pool.
	 *
	 * @param poolable pooling hint
	 */
	override fun setPoolable(poolable: Boolean) {
		checkOpen()
	}

	/** @return false, as the driver has no statement pool */
	override fun isPoolable(): Boolean {
		checkOpen()
		return false
	}

	/** Closes the statement once its result sets are closed. */
	override fun closeOnCompletion() {
		checkOpen()
		closeOnCompletion = true
	}

	/** @return whether the statement closes once its result sets are closed */
	override fun isCloseOnCompletion(): Boolean {
		checkOpen()
		return closeOnCompletion
	}

	/** Closes the statement when [closeOnCompletion] is set. */
	internal fun resultSetClosed() {
		if (closeOnCompletion) {
			closed = true
		}
	}

	/**
	 * Returns this statement as the given interface.
	 *
	 * @param iface interface to unwrap to
	 * @return this statement
	 * @throws SQLException when it doesn't implement the interface
	 */
	override fun <T : Any?> unwrap(iface: Class<T>): T {
		if (!iface.isInstance(this)) {
			throw SQLException("The statement is not a ${iface.name}.")
		}
		return iface.cast(this)
	}

	/** @return whether this statement implements the interface */
	override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}


/** SQLite error text that means a table or column the schema lists no longer exists. */
private val STALE_SCHEMA_ERRORS = listOf("no such table", "no such column")

/**
 * Says whether SQL is an INSERT or REPLACE, which D1 reports a new row ID for.
 *
 * @param sql SQL text
 * @return true when the first word is INSERT or REPLACE
 */
private fun isInsert(sql: String): Boolean {
	val firstWord = QUOTED_OR_COMMENT.replace(sql, " ").trim().substringBefore(" ").lowercase()
	return firstWord == "insert" || firstWord == "replace"
}
