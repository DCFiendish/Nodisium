apply(plugin = "org.jlleitschuh.gradle.ktlint")

dependencies {
    api("net.minestom:minestom:2026.07.12-26.2")
    api("com.google.code.gson:gson:2.14.0")
    compileOnly("net.aechronis:utils:86a747b")
    implementation("com.cronutils:cron-utils:9.2.1") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.modernmt.text:profanity-filter:1.0.1")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-common:1.10.2-SNAPSHOT")
    // Interface types only (HotSwappableModule/ModuleContext, see VanillaLiveModule.kt).
    compileOnly(project(":server"))

    // testing
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("net.aechronis:utils:86a747b")
    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18") // logging (only used while testing at the moment)
}

tasks.test {
    systemProperty("keepRunning", System.getProperty("keepRunning", "false"))
    // No LuckPerms provider is registered in this test process, so net.aechronis.utils.hasPermission
    // falls back to this env var (its only bypass in the pinned 86a747b build) instead of denying
    // every permission-gated check outright -- needed for VanishTest's level-based permission checks.
    environment("DEBUG", "true")
}
