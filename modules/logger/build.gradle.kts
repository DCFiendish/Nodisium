apply(plugin = "org.jlleitschuh.gradle.ktlint")

dependencies {
    implementation("net.minestom:minestom:2026.07.12-26.2")
    compileOnly("net.aechronis:utils:86a747b")
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))
    // Interface types only (HotSwappableModule/ModuleContext, see LoggerLiveModule.kt).
    compileOnly(project(":server"))

    compileOnly("com.h2database:h2:2.4.240")
    compileOnly("com.zaxxer:HikariCP:7.1.0")

    // testing
    testImplementation("net.aechronis:utils:86a747b")
    testImplementation(project(":modules:vanilla"))
    testImplementation(project(":modules:worldedit"))
    testImplementation("com.h2database:h2:2.4.240")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
