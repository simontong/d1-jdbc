plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.intellij.platform)
	alias(libs.plugins.ktlint)
}

// Checks Kotlin style. The baseline lists the code written before ktlint was added.
ktlint {
	version = libs.versions.ktlint.cli
	baseline = file("config/ktlint/baseline.xml")
}

version = rootProject.version

repositories {
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}

dependencies {
	intellijPlatform {
		// -PlocalIde=/path/to/phpstorm builds against an installed IDE, which skips a large download.
		val localIde = providers.gradleProperty("localIde").orNull
		if (localIde != null) {
			local(localIde)
		} else {
			phpstorm("2026.2.3")
		}
		bundledPlugin("com.intellij.database")
	}
}

kotlin {
	// PhpStorm 2026.2 runs on Java 25 and ships Kotlin 2.4.
	jvmToolchain(21)
}

intellijPlatform {
	buildSearchableOptions = false
}

tasks.processResources {
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

	inputs.property("pluginVersion", pluginVersion)
	inputs.property("driverUrl", driverUrl)
	inputs.property("licenseUrl", licenseUrl)
	filesMatching("d1jdbc/*.xml") {
		expand("version" to pluginVersion, "driverUrl" to driverUrl, "licenseUrl" to licenseUrl)
	}
}

tasks.buildPlugin {
	archiveBaseName = "d1-jdbc-ide-plugin"
}

tasks.assemble {
	dependsOn(tasks.buildPlugin)
}
