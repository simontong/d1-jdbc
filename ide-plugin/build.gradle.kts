plugins {
	base
}

version = rootProject.version

// The plugin holds only XML, so it's a plain jar with the version and driver address filled in.
val pluginJar = tasks.register<Jar>("pluginJar") {
	val pluginVersion = project.version.toString()
	val releaseUrl = "https://github.com/simontong/d1-jdbc/releases/download/v$pluginVersion"
	val tagUrl = "https://raw.githubusercontent.com/simontong/d1-jdbc/v$pluginVersion"

	// A build without -PreleaseVersion is for local testing, so it points at the jar this build makes.
	// -PdriverJarUrl=file:///path/to/d1-jdbc-all.jar points at any other jar.
	val isRelease = providers.gradleProperty("releaseVersion").isPresent
	val builtDriver = "file://" + rootProject.layout.buildDirectory.file("libs/d1-jdbc-$pluginVersion-all.jar").get().asFile.absolutePath
	val localDriver = providers.gradleProperty("driverJarUrl").orNull ?: builtDriver.takeUnless { isRelease }
	val driverUrl = localDriver ?: "$releaseUrl/d1-jdbc-$pluginVersion-all.jar"
	val licenseUrl = if (localDriver != null) "file://" + rootProject.file("LICENSE").absolutePath else "$tagUrl/LICENSE"

	if (localDriver == builtDriver) {
		dependsOn(":shadowJar")
	}

	archiveBaseName = "d1-jdbc-ide-plugin"
	destinationDirectory = layout.buildDirectory.dir("libs")
	inputs.property("pluginVersion", pluginVersion)
	inputs.property("driverUrl", driverUrl)
	inputs.property("licenseUrl", licenseUrl)
	from("src/main/resources") {
		filesMatching("**/*.xml") {
			expand("version" to pluginVersion, "driverUrl" to driverUrl, "licenseUrl" to licenseUrl)
		}
	}
}

tasks.assemble {
	dependsOn(pluginJar)
}
