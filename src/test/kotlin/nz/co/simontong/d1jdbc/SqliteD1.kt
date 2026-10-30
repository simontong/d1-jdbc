package nz.co.simontong.d1jdbc

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An in-memory SQLite database that answers the stub server's requests the way D1's `/raw` does.
 *
 * @param server stub server to answer for
 */
class SqliteD1(server: StubD1Server) : AutoCloseable {
	/** Direct connection to the database, for setting up test data. */
	val sqlite: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

	init {
		server.respondWith { body -> reply(body) }
	}

	/**
	 * Runs SQL directly on the database.
	 *
	 * @param statements SQL statements to run in order
	 */
	fun setUp(vararg statements: String) {
		sqlite.createStatement().use { statement ->
			statements.forEach { statement.executeUpdate(it) }
		}
	}

	/**
	 * Runs the statements in a request body in one transaction and builds D1's reply.
	 *
	 * @param body request body
	 * @return HTTP status and reply body
	 */
	private fun reply(body: String): Pair<Int, String> {
		val request = Json.parseToJsonElement(body).jsonObject
		val statements = request["batch"]?.jsonArray?.map { it.jsonObject } ?: listOf(request)
		sqlite.autoCommit = false
		val results = try {
			statements.map { run(it) }.also { sqlite.commit() }
		} catch (error: SQLException) {
			sqlite.rollback()
			val message = JsonPrimitive("${error.message}: SQLITE_ERROR")
			return 400 to """{"result":[],"success":false,"errors":[{"code":7500,"message":$message}],"messages":[]}"""
		} finally {
			sqlite.autoCommit = true
		}
		return 200 to """{"result":${JsonArray(results)},"success":true,"errors":[],"messages":[]}"""
	}

	/**
	 * Runs one statement, binding numbers as REAL the way D1 does.
	 *
	 * @param statement one statement from the request
	 * @return its entry in the reply's `result` array
	 */
	private fun run(statement: JsonObject): JsonElement {
		val sql = statement.getValue("sql").jsonPrimitive.content
		val params = statement["params"]?.jsonArray.orEmpty()
		sqlite.prepareStatement(sql).use { prepared ->
			params.forEachIndexed { index, param ->
				when {
					param is JsonNull -> prepared.setNull(index + 1, java.sql.Types.NULL)
					param is JsonArray -> prepared.setBytes(index + 1, ByteArray(param.size) { param[it].jsonPrimitive.int.toByte() })
					param.jsonPrimitive.isString -> prepared.setString(index + 1, param.jsonPrimitive.content)
					else -> prepared.setDouble(index + 1, param.jsonPrimitive.double)
				}
			}
			val hasRows = prepared.execute()
			val columns = mutableListOf<JsonElement>()
			val rows = mutableListOf<JsonElement>()
			if (hasRows) {
				val resultSet = prepared.resultSet
				val count = resultSet.metaData.columnCount
				(1..count).forEach { columns.add(JsonPrimitive(resultSet.metaData.getColumnLabel(it))) }
				while (resultSet.next()) {
					rows.add(JsonArray((1..count).map { jsonValue(resultSet.getObject(it)) }))
				}
			}
			val changes = if (hasRows) 0 else prepared.updateCount
			val lastRowId = sqlite.createStatement().use { it.executeQuery("select last_insert_rowid()").getLong(1) }
			return buildJsonObject {
				put("results", buildJsonObject {
					put("columns", JsonArray(columns))
					put("rows", JsonArray(rows))
				})
				put("success", true)
				put("meta", buildJsonObject {
					put("changes", changes)
					put("last_row_id", lastRowId)
				})
			}
		}
	}

	/**
	 * Turns a SQLite value into JSON the way D1 does.
	 *
	 * @param value value read from SQLite
	 * @return the JSON value
	 */
	private fun jsonValue(value: Any?): JsonElement {
		return when (value) {
			null -> JsonNull
			// D1 sends a whole REAL like 1.0 as 1, as found in live tests.
			is Double -> if (value % 1.0 == 0.0 && value.isFinite()) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
			is Number -> JsonPrimitive(value)
			is ByteArray -> JsonArray(value.map { JsonPrimitive(it.toInt() and 0xFF) })
			else -> JsonPrimitive(value.toString())
		}
	}

	/** Closes the database. */
	override fun close() {
		sqlite.close()
	}
}
