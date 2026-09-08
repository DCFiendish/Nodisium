plugins {
    application
    id("com.gradleup.shadow") version "8.3.6"
}

configurations.all {
    resolutionStrategy {
        // Historically nodes/combat/vanilla pinned different net.aechronis:utils versions; both
        // modules/nodes and modules/vanilla now declare 86a747b directly, so this is redundant but
        // harmless — keeping it as a guard against a future module reintroducing a stale pin.
        force("net.aechronis:utils:86a747b")
    }
}

dependencies {
    implementation("net.minestom:minestom:2026.07.12-26.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("net.aechronis:utils:86a747b")
    // modules/vanilla only declares these compileOnly (matching its net.aechronis:utils pattern),
    // so the real runtime jars have to be pulled in here or Signs.kt's DroppedItemFactory use
    // throws NoClassDefFoundError the first time a sign is broken.
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-common:1.10.2-SNAPSHOT")
    // nodes/vanilla/combat/worldedit are no longer project dependencies -- they're loaded at
    // runtime by ModuleManager from their own jars (nodisium-data/modules/), each through its own
    // ModuleClassLoader, so a reload can pick up newly-built code without a full server restart.
    // The lines below replace what those four project dependencies used to pull in transitively:
    // each module's own non-project dependencies, which its ModuleClassLoader resolves from this
    // parent classpath (see ModuleClassLoader's kdoc for why they can't be bundled in the module's
    // own plain jar instead).
    // -- from modules/vanilla:
    implementation("com.cronutils:cron-utils:9.2.1") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.modernmt.text:profanity-filter:1.0.1")
    // -- from modules/worldedit:
    implementation("com.sk89q.worldedit:worldedit-core:7.4.4") {
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "com.google.guava", module = "guava")
        exclude(group = "it.unimi.dsi", module = "fastutil")
    }
    implementation("com.google.guava:guava:33.6.0-jre")
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.slf4j:slf4j-simple:2.0.18")
    // Perf profiler -- github.com/LooFifteen/spark's Minestom port of lucko/spark, see Main.kt's
    // SparkMinestom.builder() call. Only version currently published to repo.hypera.dev.
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")
    // Real permission gating — ConceptMC's Minestom port of LuckPerms, same lib+wiring as
    // Aechronis/aechronis's own Server.kt. See net.nodisium.server.Permissions. H2 is its default
    // storage backend (file-based, nodisium-data/luckperms/) — no external DB needed for this.
    implementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    implementation("com.h2database:h2:2.4.240")
    implementation("com.zaxxer:HikariCP:7.1.0")

    // testing -- exercises ModuleManager's real jar-loading/reload path against the actual jars
    // built by syncModuleJars, same net.aechronis.utils.createTestServer() harness modules/nodes
    // and modules/vanilla already use.
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}

application {
    mainClass.set("net.nodisium.server.MainKt")
}

// ModuleManager loads vanilla/combat/worldedit/nodes at runtime from their own jars under
// nodisium-data/modules/ (see server/src/main/kotlin/net/nodisium/server/modules/ModuleManager.kt)
// instead of them being compiled into this project. This task keeps that directory in sync with
// each module's own :jar output as part of the normal build, so a plain build/run still boots
// correctly without a manual copy step -- the same directory a live deploy overwrites one jar at a
// time to hot-swap a single module (see .claude/skills/nodisium-ops).
val moduleIds = listOf("vanilla", "combat", "worldedit", "nodes")

val syncModuleJars by tasks.registering(Copy::class) {
    moduleIds.forEach { id ->
        dependsOn(":modules:$id:jar")
        from(project(":modules:$id").tasks.named("jar")) {
            rename { "$id.jar" }
        }
    }
    into("nodisium-data/modules")
}

tasks.named("run") { dependsOn(syncModuleJars) }
tasks.test { dependsOn(syncModuleJars) }
tasks.shadowJar {
    dependsOn(syncModuleJars)
    archiveBaseName.set("nodisium-server")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
