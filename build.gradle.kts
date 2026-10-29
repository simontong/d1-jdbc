plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.shadow)
}

group = "nz.co.simontong"
version = "0.0.0"

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

tasks.test {
	useJUnitPlatform()
}

tasks.shadowJar {
	// Shadow skips duplicate service files unless told to merge them.
	duplicatesStrategy = DuplicatesStrategy.INCLUDE
	mergeServiceFiles()
}
