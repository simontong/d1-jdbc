package nz.co.simontong.d1jdbc

import java.sql.ParameterMetaData
import java.sql.SQLException
import java.sql.Types

/**
 * Parameter details for a prepared statement. D1 doesn't describe parameters, so only the count is known.
 *
 * @param parameterCount number of parameters the SQL needs
 */
class D1ParameterMetaData(
	private val parameterCount: Int,
) : ParameterMetaData {
	/**
	 * Throws when the parameter number is out of range.
	 *
	 * @param param parameter number, starting at 1
	 * @throws SQLException when out of range
	 */
	private fun checkParameter(param: Int) {
		if (param < 1 || param > parameterCount) {
			throw SQLException("There is no parameter $param. The SQL has $parameterCount.", "07009")
		}
	}

	/** @return number of parameters the SQL needs */
	override fun getParameterCount(): Int = parameterCount

	/** @return [ParameterMetaData.parameterNullableUnknown], as D1 doesn't describe parameters */
	override fun isNullable(param: Int): Int {
		checkParameter(param)
		return ParameterMetaData.parameterNullableUnknown
	}

	/** @return true, as a parameter may hold a signed number */
	override fun isSigned(param: Int): Boolean {
		checkParameter(param)
		return true
	}

	/** @return 0, as D1 doesn't describe parameters */
	override fun getPrecision(param: Int): Int {
		checkParameter(param)
		return 0
	}

	/** @return 0, as D1 doesn't describe parameters */
	override fun getScale(param: Int): Int {
		checkParameter(param)
		return 0
	}

	/** @return [Types.OTHER], as a parameter may hold any type */
	override fun getParameterType(param: Int): Int {
		checkParameter(param)
		return Types.OTHER
	}

	/** @return an empty name, as a parameter may hold any type */
	override fun getParameterTypeName(param: Int): String {
		checkParameter(param)
		return ""
	}

	/** @return the class name for any value */
	override fun getParameterClassName(param: Int): String {
		checkParameter(param)
		return "java.lang.Object"
	}

	/** @return [ParameterMetaData.parameterModeIn], as D1 has no output parameters */
	override fun getParameterMode(param: Int): Int {
		checkParameter(param)
		return ParameterMetaData.parameterModeIn
	}

	/**
	 * Returns this object as the given interface.
	 *
	 * @param iface interface to unwrap to
	 * @return this object
	 * @throws SQLException when it doesn't implement the interface
	 */
	override fun <T : Any?> unwrap(iface: Class<T>): T {
		if (!iface.isInstance(this)) {
			throw SQLException("The parameter metadata is not a ${iface.name}.")
		}
		return iface.cast(this)
	}

	/** @return whether this object implements the interface */
	override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
