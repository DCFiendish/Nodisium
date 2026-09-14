apply(plugin = "org.jlleitschuh.gradle.ktlint")

dependencies {
    implementation("net.minestom:minestom:2026.07.12-26.2")
    compileOnly("net.aechronis:utils:86a747b")
    // Interface types only (HotSwappableModule/ModuleContext, see WatchdogLiveModule.kt).
    compileOnly(project(":server"))

    // testing
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("net.aechronis:utils:86a747b")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
