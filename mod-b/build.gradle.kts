plugins {
    id("fabric-loom") version "1.11.8"
    kotlin("jvm") version "2.3.20"
}

base { archivesName = property("archives_base_name") as String }
version = property("mod_version") as String
group = property("maven_group") as String

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // Baritone API - vendored JAR, compile-only (user installs standalone at runtime)
    modCompileOnly(files("libs/baritone-api-fabric-1.15.0.jar"))

    // Java MCP SDK 1.0
    implementation("io.modelcontextprotocol.sdk:mcp:${property("mcp_sdk_version")}")

    // Kotlin scripting host
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${property("kotlin_version")}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:${property("kotlin_version")}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:${property("kotlin_version")}")

    // Jackson for JSON (MCP SDK also pulls this; pin explicitly)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:${property("kotlin_version")}")
}

kotlin { jvmToolchain(21) }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.test { useJUnitPlatform() }
