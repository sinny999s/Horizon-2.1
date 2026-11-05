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
    // The Hypixel Mod API's classes, to compile against: at runtime they come from the Hypixel Mod API mod, if installed.
    maven("https://repo.hypixel.net/repository/Hypixel/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    compileOnly("net.hypixel:mod-api:1.0.2")
    // Kotlin's standard library and kotlinx.serialization come from Fabric Language Kotlin at runtime.
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")
    // SVG icons (Horizon rasterised its icons with JSVG 1.x), shipped inside the mod jar (MIT).
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
