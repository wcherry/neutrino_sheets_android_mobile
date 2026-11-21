// Sheets' model and rules: the workbook as the editor holds it, every edit the grid offers (format,
// fill, merge, sort, filter, find, structure, tabs, clipboard), undo, the editor session with its
// guarded save, Drive browsing and the offline copies. Plain Kotlin/JVM, so all of it is tested on
// the JVM in seconds against the shared library's in-memory server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.neutrino.shared:neutrino-core")
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(testFixtures("com.neutrino.shared:neutrino-core"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("user.timezone", "Europe/Berlin")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
