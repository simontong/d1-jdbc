package nz.co.simontong.d1jdbc

import java.sql.Array as SqlArray
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Clob
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.NClob
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLWarning
import java.sql.SQLXML
import java.sql.Savepoint
import java.sql.Statement
import java.sql.Struct
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * A JDBC connection to the D1 databases of one or more accounts, where statements run on the chosen one.
 *
 * @param clients one client per account, each sending SQL to that account's databases
 * @param settings settings the connection was opened with
 */
class D1Connection(
	private val clients: List<D1Client>,
	private val settings: D1Settings,
) : Connection {
	/**
	 * Opens a connection to one account.
	 *
	 * @param client client for the account
	 * @param settings settings the connection was opened with
	 */
	constructor(client: D1Client, settings: D1Settings) : this(listOf(client), settings)

	@Volatile
	private var closed = false
	private var warnings: SQLWarning? = null
	private var readOnly = settings.readOnly

	/** Whether a statement that changes data has run since the last commit or rollback. */
	@Volatile
	internal var changedSinceCommit = false

	/** ID of the database statements run on, or null until one is chosen. */
	@Volatile
	private var databaseId: String? = null

	/** Each database's name as Cloudflare lists it, by ID, before any account name is added. */
	@Volatile
	private var listedNames: Map<String, String> = emptyMap()

	/** Every account's databases sorted by name, loaded the first time they're needed. */
	@Volatile
	private var databaseList: List<D1DatabaseInfo>? = null

	/** When this connection last got a good reply from Cloudflare, from [System.nanoTime]. */
	@Volatile
	private var lastGoodReply = 0L

	/**
	 * Lists every account's databases, loading the list the first time or when asked to.
	 *
	 * @param reload whether to ask Cloudflare again
	 * @param timeout how long to wait, or null for the client's default
	 * @return the databases sorted by name
	 * @throws SQLException when Cloudflare can't be reached or rejects the token
	 */
	internal fun databases(reload: Boolean = false, timeout: Duration? = null): List<D1DatabaseInfo> {
		val known = databaseList
		if (known != null && !reload) {
			return known
		}
		val fromCloudflare = clients.flatMap { client ->
			if (timeout != null) client.listDatabases(timeout) else client.listDatabases()
		}
		listedNames = fromCloudflare.associate { it.id to it.name }
		val listed = clients.flatMap { client ->
			val databases = fromCloudflare.filter { it.accountId == client.accountId }
			// With several accounts, each name starts with its account's name.
			if (clients.size > 1) databases.map { it.copy(name = "${client.accountName} / ${it.name}") } else databases
		}
		// Cloudflare doesn't promise names are unique, so a shared name gets its ID added.
		val shared = listed.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
		val loaded = listed
			.map { if (it.name in shared) it.copy(name = "${it.name} (${it.id})") else it }
			.sortedBy { it.name.lowercase() }
		databaseList = loaded
		return loaded
	}

	/**
	 * Finds the database with an ID, or makes a stand-in named by its ID when the list lacks it.
	 *
	 * @param id database ID
	 * @return the database
	 * @throws SQLException when the list can't be loaded
	 */
	private fun databaseWithId(id: String): D1DatabaseInfo {
		return databases().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: D1DatabaseInfo(id, id, clients.first().accountId)
	}

	/**
	 * Returns the client for the account a database belongs to.
	 *
	 * @param id database ID
	 * @return the client
	 * @throws SQLException when the list can't be loaded
	 */
	private fun clientFor(id: String): D1Client {
		val accountId = databaseWithId(id).accountId
		return clients.firstOrNull { it.accountId == accountId } ?: clients.first()
	}

	/**
	 * Works out the database statements run on, choosing the first by name when none is chosen yet.
	 *
	 * @return the database ID
	 * @throws SQLException when the account has no databases
	 */
	private fun currentDatabaseId(): String {
		chosenDatabaseId()?.let { return it }
		// IDEs need a current database, so the first by name stands in until the user picks one.
		val first = databases().firstOrNull() ?: throw SQLException(noDatabasesMessage(), "3D000")
		databaseId = first.id
		return first.id
	}

	/**
	 * Returns the chosen database, working out the one the URL names the first time.
	 *
	 * @return the database ID, or null when none is chosen
	 * @throws SQLException when the URL names a database this connection can't reach
	 */
	private fun chosenDatabaseId(): String? {
		databaseId?.let { return it }
		val start = settings.databaseId ?: return null
		val found = findDatabase(start)
		databaseId = found
		return found
	}

	/**
	 * Finds a database by its ID or by its name as Cloudflare lists it.
	 *
	 * @param idOrName database ID or name
	 * @return the database ID
	 * @throws SQLException when no database or more than one matches
	 */
	private fun findDatabase(idOrName: String): String {
		val all = databases()
		all.firstOrNull { it.id.equals(idOrName, ignoreCase = true) }?.let { return it.id }
		val named = listedNames.filterValues { it == idOrName }.keys
		if (named.size > 1) {
			throw SQLException("More than one D1 database is called \"$idOrName\". Put its ID in the URL instead.", "3D000")
		}
		named.singleOrNull()?.let { return it }
		// A database can be missing from the list for a short time after it's made, so trust an ID.
		if (UUID_PATTERN.matches(idOrName)) {
			return idOrName
		}
		throw SQLException("There's no D1 database called \"$idOrName\" that this connection can reach.", "3D000")
	}

	/**
	 * Builds the message for a connection that reaches no databases.
	 *
	 * @return the message
	 */
	private fun noDatabasesMessage(): String {
		return if (clients.size > 1) "None of these Cloudflare accounts has a D1 database." else "This Cloudflare account has no D1 databases."
	}

	/**
	 * Runs statements on the chosen database.
	 *
	 * @param statements statements to run in order
	 * @param timeout how long to wait, or null for the client's default
	 * @return one result per statement
	 * @throws SQLException when no database is chosen, or D1 reports an error
	 */
	internal fun execute(statements: List<D1Query>, timeout: Duration? = null): List<D1Result> {
		val id = currentDatabaseId()
		val reads = statements.all { isReadOnlySql(it.sql) }
		val client = clientFor(id)
		val results = if (timeout != null) {
			client.execute(id, statements, timeout, retry = reads)
		} else {
			client.execute(id, statements, retry = reads)
		}
		lastGoodReply = System.nanoTime()
		return results
	}

	/**
	 * Returns a database's schema cache, making it the first time.
	 *
	 * @param id database ID
	 * @return the cache
	 */
	private fun schemaCacheFor(id: String): SchemaCache {
		val client = clientFor(id)
		// Connections of one person share schemas, so a table one connection makes shows in the others.
		val key = "${client.cacheKey}|$id|${settings.schemaCacheSeconds}"
		// Drop schemas nobody has read for a while, so a long session doesn't hold every one ever seen.
		sharedSchemaCaches.values.forEach { it.dropIfStale() }
		return sharedSchemaCaches.computeIfAbsent(key) {
			SchemaCache(client, id, Duration.ofSeconds(settings.schemaCacheSeconds))
		}
	}

	/** The chosen database's schema cache. */
	internal val schemaCache: SchemaCache
		get() = schemaCacheFor(currentDatabaseId())

	/**
	 * Loads the schemas a metadata call asks about, each with its database name.
	 *
	 * @param catalog database name, null for every database, or empty for none, as JDBC defines them
	 * @return database names and schemas, sorted by name
	 * @throws SQLException when D1 reports an error
	 */
	internal fun schemasFor(catalog: String?): List<Pair<String, D1Schema>> {
		val chosen = if (catalog == null) databases() else databases().filter { it.name == catalog }
		return chosen.map { it.name to schemaCacheFor(it.id).get() }
	}

	/**
	 * Throws when the connection is closed.
	 *
	 * @throws SQLNonTransientConnectionException when closed
	 */
	internal fun checkOpen() {
		if (closed) {
			throw SQLNonTransientConnectionException("The connection is closed.", "08003")
		}
	}

	/**
	 * Creates a statement.
	 *
	 * @return a new statement
	 * @throws SQLException when the connection is closed
	 */
	override fun createStatement(): Statement {
		checkOpen()
		return D1Statement(this)
	}

	/**
	 * Creates a statement, allowing only forward-only, read-only results.
	 *
	 * @param resultSetType result set type
	 * @param resultSetConcurrency result set concurrency
	 * @return a new statement
	 * @throws SQLException for other result set types
	 */
	override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement {
		checkResultSetOptions(resultSetType, resultSetConcurrency)
		return createStatement()
	}

	/**
	 * Creates a statement, allowing only forward-only, read-only results.
	 *
	 * @param resultSetType result set type
	 * @param resultSetConcurrency result set concurrency
	 * @param resultSetHoldability result set holdability, ignored
	 * @return a new statement
	 * @throws SQLException for other result set types
	 */
	override fun createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): Statement {
		return createStatement(resultSetType, resultSetConcurrency)
	}

	/**
	 * Throws unless the options ask for forward-only, read-only results.
	 *
	 * @param resultSetType result set type
	 * @param resultSetConcurrency result set concurrency
	 * @throws SQLFeatureNotSupportedException for other options
	 */
	private fun checkResultSetOptions(resultSetType: Int, resultSetConcurrency: Int) {
		if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
			throw SQLFeatureNotSupportedException("D1 only supports forward-only, read-only result sets.")
		}
	}

	/**
	 * Creates a prepared statement.
	 *
	 * @param sql SQL text with `?` or `?NNN` placeholders
	 * @return a new prepared statement
	 * @throws SQLException when the connection is closed
	 */
	override fun prepareStatement(sql: String): PreparedStatement {
		checkOpen()
		return D1PreparedStatement(this, sql)
	}

	/**
	 * Creates a prepared statement, allowing only forward-only, read-only results.
	 *
	 * @param sql SQL text
	 * @param resultSetType result set type
	 * @param resultSetConcurrency result set concurrency
	 * @return a new prepared statement
	 * @throws SQLException for other result set types
	 */
	override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement {
		checkResultSetOptions(resultSetType, resultSetConcurrency)
		return prepareStatement(sql)
	}

	/**
	 * Creates a prepared statement, allowing only forward-only, read-only results.
	 *
	 * @param sql SQL text
	 * @param resultSetType result set type
	 * @param resultSetConcurrency result set concurrency
	 * @param resultSetHoldability result set holdability, ignored
	 * @return a new prepared statement
	 * @throws SQLException for other result set types
	 */
	override fun prepareStatement(
		sql: String,
		resultSetType: Int,
		resultSetConcurrency: Int,
		resultSetHoldability: Int,
	): PreparedStatement {
		return prepareStatement(sql, resultSetType, resultSetConcurrency)
	}

	/**
	 * Creates a prepared statement that can keep the new row's ID after an INSERT.
	 *
	 * @param sql SQL text
	 * @param autoGeneratedKeys [Statement.RETURN_GENERATED_KEYS] or [Statement.NO_GENERATED_KEYS]
	 * @return a new prepared statement
	 * @throws SQLException for any other flag
	 */
	override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement {
		checkOpen()
		if (autoGeneratedKeys != Statement.RETURN_GENERATED_KEYS && autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
			throw SQLException("autoGeneratedKeys must be RETURN_GENERATED_KEYS or NO_GENERATED_KEYS.", "HY024")
		}
		return D1PreparedStatement(this, sql, autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS)
	}

	/** Not supported, as D1 only reports the new row's ID. Use RETURN_GENERATED_KEYS. */
	override fun prepareStatement(sql: String?, columnIndexes: IntArray?): PreparedStatement {
		throw notSupported("Generated keys by column")
	}

	/** Not supported, as D1 only reports the new row's ID. Use RETURN_GENERATED_KEYS. */
	override fun prepareStatement(sql: String?, columnNames: Array<out String>?): PreparedStatement {
		throw notSupported("Generated keys by column")
	}

	/** Not supported, as D1 has no stored procedures. */
	override fun prepareCall(sql: String?): CallableStatement = throw notSupported("Stored procedures")

	/** Not supported, as D1 has no stored procedures. */
	override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int): CallableStatement {
		throw notSupported("Stored procedures")
	}

	/** Not supported, as D1 has no stored procedures. */
	override fun prepareCall(
		sql: String?,
		resultSetType: Int,
		resultSetConcurrency: Int,
		resultSetHoldability: Int,
	): CallableStatement {
		throw notSupported("Stored procedures")
	}

	/**
	 * Returns the SQL unchanged, as the driver doesn't rewrite escape syntax.
	 *
	 * @param sql SQL text
	 * @return the same SQL
	 */
	override fun nativeSQL(sql: String?): String? {
		checkOpen()
		return sql
	}

	/**
	 * Stays in auto-commit, adding a warning when asked to turn it off.
	 *
	 * @param autoCommit requested setting
	 * @throws SQLException when the connection is closed
	 */
	override fun setAutoCommit(autoCommit: Boolean) {
		checkOpen()

		// Throwing here breaks IDE schema sync, see DBE-3115.
		if (!autoCommit) {
			addWarning("D1 has no transactions, so every statement is saved as soon as it runs.")
		}
	}

	/**
	 * Adds a warning to the end of the warning chain.
	 *
	 * @param message warning text
	 */
	internal fun addWarning(message: String) {
		val warning = SQLWarning(message, "01000")
		val first = warnings
		if (first == null) {
			warnings = warning
		} else {
			first.setNextWarning(warning)
		}
	}

	/**
	 * Says whether each statement is saved straight away, which is always true on D1.
	 *
	 * @return true
	 * @throws SQLException when the connection is closed
	 */
	override fun getAutoCommit(): Boolean {
		checkOpen()
		return true
	}

	/**
	 * Does nothing, as every statement is already saved.
	 *
	 * @throws SQLException when the connection is closed
	 */
	override fun commit() {
		checkOpen()
		changedSinceCommit = false
	}

	/**
	 * Does nothing when no change was saved since the last commit, and fails otherwise.
	 *
	 * @throws SQLException when a change was saved, as D1 can't undo it
	 */
	override fun rollback() {
		checkOpen()
		if (!changedSinceCommit) {
			return
		}
		changedSinceCommit = false
		throw SQLException(
			"D1 has no transactions, so changes were saved as each statement ran and can't be rolled back.",
			"25000",
		)
	}

	/** Closes the connection. Closing it again does nothing. */
	override fun close() {
		closed = true
	}

	/** @return whether the connection is closed */
	override fun isClosed(): Boolean = closed

	/**
	 * Describes D1 and its schema.
	 *
	 * @return the database metadata
	 * @throws SQLException when the connection is closed
	 */
	override fun getMetaData(): DatabaseMetaData {
		checkOpen()
		return D1DatabaseMetaData(this)
	}

	/** The JDBC URL for this database. */
	internal val url: String
		get() = "jdbc:d1://${settings.accountId.orEmpty()}" + (settings.databaseId?.let { "/$it" } ?: "")

	/**
	 * Turns read-only mode on or off, unless the connection settings force it on.
	 *
	 * @param readOnly requested setting
	 * @throws SQLException when the connection is closed
	 */
	override fun setReadOnly(readOnly: Boolean) {
		checkOpen()
		if (!readOnly && settings.readOnly) {
			addWarning("The connection settings make this connection read-only, so it stays read-only.")
			return
		}
		this.readOnly = readOnly
	}

	/**
	 * Says whether the connection blocks statements that change data.
	 *
	 * @return true when read-only
	 * @throws SQLException when the connection is closed
	 */
	override fun isReadOnly(): Boolean {
		checkOpen()
		return readOnly
	}

	/**
	 * Chooses the database statements run on, by its name.
	 *
	 * @param catalog database name, where null or empty change nothing
	 * @throws SQLException when the account has no database with that name
	 */
	override fun setCatalog(catalog: String?) {
		checkOpen()
		if (catalog.isNullOrEmpty()) {
			return
		}
		val found = databases().firstOrNull { it.name == catalog } ?: databases(reload = true).firstOrNull { it.name == catalog }
		databaseId = found?.id ?: throw SQLException("There's no D1 database called \"$catalog\" that this connection can reach.", "3D000")
	}

	/**
	 * Names the database statements run on.
	 *
	 * @return the database name, or null when the account has no databases
	 * @throws SQLException when the database list can't be loaded
	 */
	override fun getCatalog(): String? {
		checkOpen()
		val id = chosenDatabaseId() ?: databases().firstOrNull()?.id ?: return null
		return databaseWithId(id).name
	}

	/**
	 * Accepts an isolation level with a warning, as IDEs set one and D1 has no transactions.
	 *
	 * @param level a [Connection] TRANSACTION constant
	 * @throws SQLException when the level isn't a JDBC isolation level, or the connection is closed
	 */
	override fun setTransactionIsolation(level: Int) {
		checkOpen()
		if (level !in ISOLATION_LEVELS) {
			throw SQLException("$level isn't a JDBC transaction isolation level.", "HY024")
		}
		if (level != Connection.TRANSACTION_NONE) {
			addWarning("D1 has no transactions, so the isolation level has no effect.")
		}
	}

	/** @return [Connection.TRANSACTION_NONE], as D1 has no transactions */
	override fun getTransactionIsolation(): Int {
		checkOpen()
		return Connection.TRANSACTION_NONE
	}

	/** @return warnings raised on this connection, oldest first */
	override fun getWarnings(): SQLWarning? {
		checkOpen()
		return warnings
	}

	/** Clears warnings raised on this connection. */
	override fun clearWarnings() {
		checkOpen()
		warnings = null
	}

	/** Not supported, as D1 has no user-defined types. */
	override fun getTypeMap(): MutableMap<String, Class<*>> = throw notSupported("Type maps")

	/** Not supported, as D1 has no user-defined types. */
	override fun setTypeMap(map: MutableMap<String, Class<*>>?) = throw notSupported("Type maps")

	/**
	 * Accepts the setting, as results never depend on a transaction.
	 *
	 * @param holdability holdability setting
	 */
	override fun setHoldability(holdability: Int) {
		checkOpen()
	}

	/** @return [ResultSet.HOLD_CURSORS_OVER_COMMIT], as results are read in full */
	override fun getHoldability(): Int {
		checkOpen()
		return ResultSet.HOLD_CURSORS_OVER_COMMIT
	}

	/** Not supported, as D1 has no transactions. */
	override fun setSavepoint(): Savepoint = throw notSupported("Savepoints")

	/** Not supported, as D1 has no transactions. */
	override fun setSavepoint(name: String?): Savepoint = throw notSupported("Savepoints")

	/** Not supported, as D1 has no transactions. */
	override fun rollback(savepoint: Savepoint?) = throw notSupported("Savepoints")

	/** Not supported, as D1 has no transactions. */
	override fun releaseSavepoint(savepoint: Savepoint?) = throw notSupported("Savepoints")

	/** Not supported. */
	override fun createClob(): Clob = throw notSupported("Clob values")

	/** Not supported. */
	override fun createBlob(): Blob = throw notSupported("Blob values")

	/** Not supported. */
	override fun createNClob(): NClob = throw notSupported("NClob values")

	/** Not supported. */
	override fun createSQLXML(): SQLXML = throw notSupported("SQLXML values")

	/**
	 * Checks the connection works by running `select 1` on D1.
	 *
	 * @param timeout time limit in seconds, where 0 means the default
	 * @return true when D1 answered
	 * @throws SQLException when the time limit is negative
	 */
	override fun isValid(timeout: Int): Boolean {
		if (timeout < 0) {
			throw SQLException("The time limit can't be negative.", "HY024")
		}
		if (closed) {
			return false
		}

		// IDEs check before every query, so a good reply moments ago is enough and saves an API call.
		if (System.nanoTime() - lastGoodReply < RECENT_REPLY.toNanos()) {
			return true
		}

		// With no database chosen, listing the databases checks the token instead.
		val id = try {
			chosenDatabaseId()
		} catch (error: SQLException) {
			return false
		}
		val query = listOf(D1Query("select 1"))
		try {
			when {
				id == null -> databases(reload = true, Duration.ofSeconds(timeout.toLong()).takeIf { timeout > 0 })
				timeout > 0 -> clientFor(id).execute(id, query, Duration.ofSeconds(timeout.toLong()))
				else -> clientFor(id).execute(id, query)
			}
		} catch (error: SQLException) {
			return false
		}
		lastGoodReply = System.nanoTime()
		return true
	}

	/** Forgets every cached schema this connection can reach, so the next read asks D1 again. */
	internal fun forgetSchemas() {
		val keys = clients.map { it.cacheKey }.toSet()
		sharedSchemaCaches.keys.filter { it.substringBefore("|") in keys }.forEach { sharedSchemaCaches[it]?.clear() }
	}

	/**
	 * Ignored, as D1 has no client info settings.
	 *
	 * @param name setting name
	 * @param value setting value
	 */
	override fun setClientInfo(name: String?, value: String?) {
		if (closed) {
			throw SQLClientInfoException("The connection is closed.", emptyMap())
		}
	}

	/**
	 * Ignored, as D1 has no client info settings.
	 *
	 * @param properties settings
	 */
	override fun setClientInfo(properties: Properties?) {
		if (closed) {
			throw SQLClientInfoException("The connection is closed.", emptyMap())
		}
	}

	/** @return null, as D1 has no client info settings */
	override fun getClientInfo(name: String?): String? {
		checkOpen()
		return null
	}

	/** @return empty properties, as D1 has no client info settings */
	override fun getClientInfo(): Properties {
		checkOpen()
		return Properties()
	}

	/** Not supported, as D1 has no array type. */
	override fun createArrayOf(typeName: String?, elements: Array<out Any>?): SqlArray = throw notSupported("Arrays")

	/** Not supported, as D1 has no struct type. */
	override fun createStruct(typeName: String?, attributes: Array<out Any>?): Struct = throw notSupported("Structs")

	/**
	 * Ignored, as D1 has only the `main` schema.
	 *
	 * @param schema schema name
	 */
	override fun setSchema(schema: String?) {
		checkOpen()
	}

	/** @return `main`, the only schema in D1 */
	override fun getSchema(): String {
		checkOpen()
		return "main"
	}

	/**
	 * Closes the connection at once.
	 *
	 * @param executor not used
	 */
	override fun abort(executor: Executor?) {
		close()
	}

	/** Not supported. */
	override fun setNetworkTimeout(executor: Executor?, milliseconds: Int) = throw notSupported("Network timeouts")

	/** Not supported. */
	override fun getNetworkTimeout(): Int = throw notSupported("Network timeouts")

	/**
	 * Returns this connection as the given interface.
	 *
	 * @param iface interface to unwrap to
	 * @return this connection
	 * @throws SQLException when it doesn't implement the interface
	 */
	override fun <T : Any?> unwrap(iface: Class<T>): T {
		return unwrapAs(this, iface, "The connection")
	}

	/** @return whether this connection implements the interface */
	override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}

/** Schemas shared by every connection in this program, keyed by person, database and cache time. */
private val sharedSchemaCaches = ConcurrentHashMap<String, SchemaCache>()

/** How recent a good reply must be for [D1Connection.isValid] to skip its own check. */
private val RECENT_REPLY = Duration.ofSeconds(5)

/** The form of a D1 database ID. */
private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** The isolation levels JDBC defines. */
private val ISOLATION_LEVELS = setOf(
	Connection.TRANSACTION_NONE,
	Connection.TRANSACTION_READ_UNCOMMITTED,
	Connection.TRANSACTION_READ_COMMITTED,
	Connection.TRANSACTION_REPEATABLE_READ,
	Connection.TRANSACTION_SERIALIZABLE,
)

/**
 * Builds the error for a feature the driver doesn't support.
 *
 * @param feature name of the feature
 * @return the exception to throw
 */
internal fun notSupported(feature: String): SQLFeatureNotSupportedException {
	return SQLFeatureNotSupportedException("Not supported by the D1 driver: $feature.", "0A000")
}

/**
 * Returns a JDBC object as the interface a caller asks for.
 *
 * @param wrapper the driver's object
 * @param iface interface to unwrap to
 * @param name name of the object for the error, such as "The statement"
 * @return the object as the interface
 * @throws SQLException when it doesn't implement the interface
 */
internal fun <T> unwrapAs(wrapper: Any, iface: Class<T>, name: String): T {
	if (!iface.isInstance(wrapper)) {
		throw SQLException("$name is not a ${iface.name}.")
	}
	return iface.cast(wrapper)
}
