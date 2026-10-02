plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// What the phone and the lobby server have to agree on -- the messages, the limits, and
// how words are found -- and nothing else. Pure JVM like :core, so both ends and their
// tests share one copy of it.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    // The real word list, when it is on this machine. It is not in the repo (it is
    // HarperCollins' copyright, see docs/SPELLINATOR.md), so the test that reads it skips
    // itself when the file is absent, as it is in CI.
    systemProperty("spell.dictionary", rootDir.resolve("spell-server/data/csw24.txt").path)
}
