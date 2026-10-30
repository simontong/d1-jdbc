package nz.co.simontong.d1jdbc.ide

import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseAuthProvider.ApplicabilityLevel
import com.intellij.database.dataSource.DatabaseAuthProviderParam
import com.intellij.database.dataSource.DatabaseConnectionConfig
import com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.DatabaseCredentialsAuthProvider

/** ID of the D1 driver this plugin adds, from `d1jdbc/drivers.xml`. */
private const val D1_DRIVER_ID = "nz.co.simontong.d1jdbc"

/**
 * Says how well a login option suits a data source: only D1 data sources can use it.
 *
 * @param point the data source
 * @param level how strong a match the IDE asks about
 * @param best the strongest match this option offers for D1
 * @return the match
 */
private fun d1Applicability(point: DatabaseConnectionPoint, level: ApplicabilityLevel, best: ApplicabilityLevel.Result): ApplicabilityLevel.Result {
	if (point.databaseDriver?.id != D1_DRIVER_ID) {
		return ApplicabilityLevel.Result.NOT_APPLICABLE
	}
	return best.clamp(level)
}

/**
 * Login option that signs in with Cloudflare in the browser, so the user needs no API token.
 */
class CloudflareSignInAuthProvider : DatabaseAuthProvider {
	/** @return the option's ID */
	override fun getId(): String = "d1.cloudflare-sign-in"

	/** @return the option's name in the Authentication list */
	override fun getDisplayName(): String = "Cloudflare sign-in"

	/**
	 * Offers this option to D1 data sources, and picks it for new ones.
	 *
	 * @param point the data source
	 * @param level how strong a match the IDE asks about
	 * @return the match
	 */
	override fun getApplicability(point: DatabaseConnectionPoint, level: ApplicabilityLevel): ApplicabilityLevel.Result {
		return d1Applicability(point, level, ApplicabilityLevel.Result.PREFERRED)
	}

	/**
	 * Lists the option's fields, which is none, as the browser does the sign-in.
	 *
	 * @param config the data source settings
	 * @return no fields
	 */
	override fun getAuthParams(config: DatabaseConnectionConfig): List<DatabaseAuthProviderParam<*>> = emptyList()

	/**
	 * Tells the driver to sign in with Cloudflare, but only to open the browser when the user is watching.
	 *
	 * @param proto the connection being opened
	 * @param silent whether the IDE is connecting in the background
	 * @return true, as this option handled the login
	 */
	override suspend fun interceptConnection(proto: ProtoConnection, silent: Boolean): Boolean {
		proto.connectionProperties["auth"] = "browser"
		proto.connectionProperties["openBrowser"] = (!silent).toString()
		proto.connectionProperties.remove("password")
		return true
	}
}

/**
 * Login option that sends an API token made in the Cloudflare dashboard.
 */
class ApiTokenAuthProvider : DatabaseAuthProvider {
	/** @return the option's ID */
	override fun getId(): String = "d1.api-token"

	/** @return the option's name in the Authentication list */
	override fun getDisplayName(): String = "API token"

	/**
	 * Offers this option to D1 data sources.
	 *
	 * @param point the data source
	 * @param level how strong a match the IDE asks about
	 * @return the match
	 */
	override fun getApplicability(point: DatabaseConnectionPoint, level: ApplicabilityLevel): ApplicabilityLevel.Result {
		return d1Applicability(point, level, ApplicabilityLevel.Result.APPLICABLE)
	}

	/**
	 * Lists the option's one field: the token, kept in the IDE's password store.
	 *
	 * @param config the data source settings
	 * @return the fields
	 */
	override fun getAuthParams(config: DatabaseConnectionConfig): List<DatabaseAuthProviderParam<*>> {
		return DatabaseAuthProviderParam.buildParams {
			passwordParam(name = "API token:")
		}
	}

	/**
	 * Passes the saved token to the driver, asking for it when it's missing and the user is watching.
	 *
	 * @param proto the connection being opened
	 * @param silent whether the IDE is connecting in the background
	 * @return true, as this option handled the login
	 */
	override suspend fun interceptConnection(proto: ProtoConnection, silent: Boolean): Boolean {
		DatabaseCredentialsAuthProvider.applyInitialCredentials(proto, silent)
		proto.connectionProperties["auth"] = "token"
		return true
	}
}
