plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.shadow)
}

group = "nz.co.simontong"
// Release builds pass -PreleaseVersion=1.2.3, set from the git tag.
version = providers.gradleProperty("releaseVersion").getOrElse("0.0.0")

kotlin {
	jvmToolchain(17)
}

dependencies {
	implementation(libs.kotlinx.serialization.json)

	testImplementation(kotlin("test"))
	testImplementation(platform(libs.junit.bom))
	testImplementation(libs.junit.jupiter)
	testImplementation(libs.sqlite.jdbc)
	testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
	val driverVersion = project.version.toString()
	inputs.property("driverVersion", driverVersion)
	filesMatching("**/version.properties") {
		expand("version" to driverVersion)
	}
}

tasks.test {
	useJUnitPlatform()
	// Lets tests check the driver reports the version it was built as.
	systemProperty("d1jdbc.buildVersion", project.version.toString())
}

tasks.shadowJar {
	// Shadow skips duplicate service files unless told to merge them.
	duplicatesStrategy = DuplicatesStrategy.INCLUDE
	mergeServiceFiles()
}
