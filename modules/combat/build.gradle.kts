apply(plugin = "org.jlleitschuh.gradle.ktlint")

dependencies {
    implementation("net.minestom:minestom:2026.07.12-26.2")
    // Interface types only (HotSwappableModule/ModuleContext, see CombatLiveModule.kt).
    compileOnly(project(":server"))

    // testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18") // logging (only used while testing at the moment)
}
