// ---------------------------------------------------------------------------
// Jar-in-Jar (JiJ) bundling notes:
//
// Fabric Loom's `include(...)` embeds the listed artifact (plus transitive deps
// via its generated fabric.mod.json nested-jar manifest) into META-INF/jars/
// of the final mod JAR. The Fabric loader then exposes those nested jars on
// the mod classloader at runtime.
//
// For every non-Fabric-ecosystem library that we need at runtime inside the
// Minecraft JVM (Kotlin scripting, Jackson, MCP SDK, ...), we declare BOTH:
//   - implementation(coords)   -- so compile + test see it
//   - include(coords)          -- so the runtime mod JAR bundles it
//
// Do NOT include() the Fabric deps (minecraft, yarn, fabric-loader, fabric-api,
// fabric-language-kotlin); those are provided by Fabric at runtime.
//
// The kotlin-scripting-jvm-host transitively pulls kotlin-compiler-embeddable
// (~50 MB); the mod JAR will be ~55 MB as a result. That's the cost of in-JVM
// Kotlin eval.
// ---------------------------------------------------------------------------

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

    // Java MCP SDK 1.0 -- bundle into mod JAR for runtime.
    implementation("io.modelcontextprotocol.sdk:mcp:${property("mcp_sdk_version")}")
    include("io.modelcontextprotocol.sdk:mcp:${property("mcp_sdk_version")}")

    // Kotlin scripting host -- MUST be bundled via JiJ so the script compiler
    // classes are on the mod classloader when eval runs.
    //
    // Loom's include(...) does NOT resolve transitives, so we must list every
    // artifact the runtime needs. In particular, kotlin-compiler-embeddable is
    // a transitive of kotlin-scripting-jvm-host and is the big (~50MB) one.
    // kotlin-stdlib / kotlin-reflect are excluded on purpose -- fabric-language-kotlin
    // provides those at runtime.
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${property("kotlin_version")}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-scripting-jvm:${property("kotlin_version")}")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-scripting-common:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-compiler-embeddable:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable:${property("kotlin_version")}")
    include("org.jetbrains.kotlin:kotlin-script-runtime:${property("kotlin_version")}")

    // Jackson for JSON (MCP SDK also pulls this; pin explicitly).
    // include() is non-transitive so we bundle the runtime-required artifacts explicitly.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    include("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    include("com.fasterxml.jackson.core:jackson-core:2.17.2")
    include("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    include("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:${property("kotlin_version")}")
}

kotlin { jvmToolchain(21) }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.test { useJUnitPlatform() }
