plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The Spellinator lobby server. Runs in Docker on the NAS, not on a phone: phones on
// cellular sit behind carrier NAT and cannot be connected to, and a lobby that lives on
// one player's phone dies with that player's signal. See docs/SPELLINATOR.md.
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.puzzlesolver.spell.server.MainKt")
    applicationName = "spell-server"
    applicationDefaultJvmArgs = listOf(
        // Sized for a container: the whole word list is about 15 MB on the heap.
        "-XX:MaxRAMPercentage=75",
        "-XX:+UseSerialGC",
        // The container's root filesystem is read-only; nothing needs writing.
        "-XX:-UsePerfData",
        // Netty's native transport unpacks a .so into /tmp, which is noexec in the
        // container. NIO is as fast at this scale and does not try.
        "-Dio.netty.transport.noNative=true",
    )
}

dependencies {
    implementation(project(":spell"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(platform(libs.netty.bom))
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.slf4j.simple)

    // The end-to-end tests drive the server with the phone's own connection code.
    testImplementation(project(":spell-client"))
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    // LiveServerTest runs against a deployed server only when given one; Gradle does not
    // pass -D through to the test JVM on its own.
    System.getProperty("spell.live")?.let { systemProperty("spell.live", it) }
}
