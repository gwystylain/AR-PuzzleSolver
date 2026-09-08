plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a pure-JVM module: no Android dependencies anywhere in :core.
// That is what makes the geometry, grid detection and solvers unit-testable on
// the desktop in milliseconds instead of via a device install.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    // Forwarded so `TerminalTemplateBuilder` can be pointed at a directory of frames
    // from the command line. Gradle does not pass -D through to the test JVM on its own,
    // and the alternative -- hardcoding a path in a test -- is worse.
    for (key in listOf("terminal.frames", "terminal.out")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
