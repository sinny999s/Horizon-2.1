import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom") version "1.19.1"
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val fabricKotlinVersion = property("fabric_kotlin_version") as String

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = "horizon-26.3"
}

repositories {
    mavenCentral()
    // Hypixel's Mod API protocol library (bundled: compat/HypixelApi carries its packets).
    maven("https://repo.hypixel.net/repository/Hypixel/")
    // Lunar Client's public repository (apollo-protos, for Lunar Spoof).
    maven("https://repo.lunarclient.dev")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    // Inside Horizon's jar. Not transitive: its build lists an old Netty, and the game has its own.
    implementation("net.hypixel:mod-api:1.0.2") { isTransitive = false }
    include("net.hypixel:mod-api:1.0.2") { isTransitive = false }
    // Lunar Client's Apollo messages (module/misc/LunarSpoof), from Lunar's public repository (repo.lunarclient.dev), and
    // the protobuf runtime they're generated for (3.25.x). Neither the game nor the owner's other mods ship protobuf.
    implementation("com.lunarclient:apollo-protos:0.2.7") { isTransitive = false }
    include("com.lunarclient:apollo-protos:0.2.7") { isTransitive = false }
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    include("com.google.protobuf:protobuf-java:3.25.5")
    // WebP pictures (Lunar's cosmetic textures, lunar/WebP.kt): TwelveMonkeys' pure-Java reader and what it needs (BSD).
    for (lib in listOf("com.twelvemonkeys.imageio:imageio-webp", "com.twelvemonkeys.imageio:imageio-core",
        "com.twelvemonkeys.imageio:imageio-metadata", "com.twelvemonkeys.common:common-lang",
        "com.twelvemonkeys.common:common-io", "com.twelvemonkeys.common:common-image")) {
        implementation("$lib:3.12.0") { isTransitive = false }
        include("$lib:3.12.0") { isTransitive = false }
    }
    // Kotlin's standard library and kotlinx.serialization come from Fabric Language Kotlin at runtime.
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")
    // SVG icons (Horizon rasterised its icons with JSVG 1.x), shipped inside the mod jar (MIT).
    // Not in the original: MP3 decoding for Lunar emote music (jams).
    implementation("javazoom:jlayer:1.0.1") { isTransitive = false }
    include("javazoom:jlayer:1.0.1") { isTransitive = false }
    implementation("com.github.weisj:jsvg:1.4.0")
    include("com.github.weisj:jsvg:1.4.0")
    // SOCKS proxies for the proxy window (Horizon shaded the same netty modules; the game doesn't ship them).
    implementation("io.netty:netty-handler-proxy:4.2.16.Final")
    include("io.netty:netty-handler-proxy:4.2.16.Final")
    include("io.netty:netty-codec-socks:4.2.16.Final")
    // Tests of game-free logic (./gradlew test), e.g. Elytra Autopilot's trail steering.
    testImplementation(kotlin("test"))
}

loom {
    runs {
        named("client") {
            runDir = "run"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
