package nz.co.simontong.d1jdbc.ide

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.IconPathPatcher

/** Path of the driver icon, as `d1jdbc/drivers.xml` names it. */
private const val DRIVER_ICON = "/d1jdbc/d1.svg"

/**
 * Points PhpStorm at this plugin for the driver icon, as it looks in its own database plugin once a
 * user has changed the driver's settings.
 */
class D1IconPatcher : IconPathPatcher() {
	/**
	 * Keeps the driver icon's path as it is.
	 *
	 * @param path icon path asked for
	 * @param classLoader class loader it was asked from
	 * @return the same path for the driver icon, or null for any other
	 */
	override fun patchPath(path: String, classLoader: ClassLoader?): String? {
		return path.takeIf { isDriverIcon(it) }
	}

	/**
	 * Loads the driver icon from this plugin.
	 *
	 * @param path icon path asked for
	 * @param originalClassLoader class loader it was asked from
	 * @return this plugin's class loader for the driver icon, or null for any other
	 */
	override fun getContextClassLoader(path: String, originalClassLoader: ClassLoader?): ClassLoader? {
		return if (isDriverIcon(path)) D1IconPatcher::class.java.classLoader else null
	}

	/**
	 * Says whether a path is the driver icon, with or without its leading slash.
	 *
	 * @param path icon path
	 * @return true for the driver icon
	 */
	private fun isDriverIcon(path: String): Boolean {
		return path.trimStart('/') == DRIVER_ICON.trimStart('/')
	}
}

/**
 * Installs [D1IconPatcher] when the IDE starts, before any project loads its data sources.
 */
class D1IconInstaller : AppLifecycleListener {
	/**
	 * Installs the icon patcher.
	 *
	 * @param commandLineArgs arguments the IDE started with
	 */
	override fun appFrameCreated(commandLineArgs: MutableList<String>) {
		IconLoader.installPathPatcher(D1IconPatcher())
	}
}
