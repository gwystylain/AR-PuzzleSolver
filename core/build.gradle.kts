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
    for (key in listOf("terminal.frames", "terminal.out", "terminal.rows")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// The team splits the Strategy and Gridlock guides show are worked out here, not on the
// phone: every stage, every team size, every plan split in full. After changing a stage
// file or the planner, `generateStrategySplits` rewrites them next to the stage files;
// `checkStrategySplits` only says whether that is needed. They take minutes, so neither
// is part of the build -- but a stage changed without regenerating fails the tests.
val strategyResources = layout.projectDirectory.dir("src/main/resources/strategy").asFile.path
for ((name, mode) in listOf("generateStrategySplits" to "write", "checkStrategySplits" to "check")) {
    tasks.register<JavaExec>(name) {
        group = "strategy"
        description = if (mode == "write") {
            "Works out every Strategy and Gridlock team split and writes them into the bundled resources."
        } else {
            "Fails if the bundled team splits are not what the current stages and planner give."
        }
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("com.puzzlesolver.core.StrategySplitsGenerator")
        args(mode, strategyResources)
    }
}
