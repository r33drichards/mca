// ---------------------------------------------------------------------------
// Pure-Java Fabric mod. No Kotlin, no Kotlin-scripting bundle.
//
// We bundle Jackson via JiJ (include) so handlers can use it at runtime; the
// Fabric ecosystem deps come from the Fabric loader / API at runtime.
// ---------------------------------------------------------------------------

plugins {
    id("fabric-loom") version "1.11.8"
    java
}

base { archivesName = property("archives_base_name") as String }
version = property("mod_version") as String
group = property("maven_group") as String

repositories {
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    // Baritone API - vendored JAR, compile-only (user installs at runtime).
    // Use the API variant (not standalone). The standalone JAR has its
    // baritone.api.* classes obfuscated, so third-party mods that link against
    // baritone.api.BaritoneAPI break at runtime.
    modCompileOnly(files("libs/baritone-api-fabric-1.15.0.jar"))

    // Jackson for JSON. include() is non-transitive so list each artifact.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    include("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    include("com.fasterxml.jackson.core:jackson-core:2.17.2")
    include("com.fasterxml.jackson.core:jackson-annotations:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.test { useJUnitPlatform() }
