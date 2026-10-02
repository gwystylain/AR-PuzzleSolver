plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The phone's side of Spellinator: one WebSocket to the lobby server, kept alive and put
// back when it drops. Pure JVM rather than Android, so it is tested against the real server
// over real sockets -- dropped, stalled and restarted -- by :spell-server's tests.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":spell"))
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
