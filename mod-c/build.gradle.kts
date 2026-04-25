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

    // Meteor Client - vendored JAR, compile-only. We don't ship Meteor (user
    // installs it via setup-portablemc.sh), but we DO want to compile against
    // it so we can extend Module + register custom modules in Meteor's GUI.
    // Reflection-only access still works when Meteor is missing at runtime
    // (BtoneC catches NoClassDefFoundError around the registration site).
    modCompileOnly(files("libs/meteor-client-1.21.8.jar"))

    // Jackson for JSON. include() is non-transitive so list each artifact.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    include("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    include("com.fasterxml.jackson.core:jackson-core:2.17.2")
    include("com.fasterxml.jackson.core:jackson-annotations:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.test { useJUnitPlatform() }

// ---------------------------------------------------------------------------
// OpenRPC spec generation. Schema.java is the single source of truth for
// every JSON-RPC method the mod exposes. At build time we run its main(),
// write the spec to ../proto/btone-openrpc.json (for client codegen at the
// repo level), AND include it as a resource so rpc.discover can return it.
// ---------------------------------------------------------------------------
val generateOpenRpc by tasks.registering(JavaExec::class) {
    description = "Emit ../proto/btone-openrpc.json from Schema.java."
    group = "build"
    // compileClasspath (Jackson + everything compileJava needed) plus the
    // freshly compiled .class output. Using runtimeClasspath would pull in
    // processResources and create a cycle (classes → processResources →
    // generateOpenRpc → classes).
    classpath = sourceSets["main"].compileClasspath +
            files(tasks.named("compileJava").get().outputs.files)
    mainClass.set("com.btone.c.schema.Schema")
    val out = file("../proto/btone-openrpc.json")
    args(out.absolutePath)
    inputs.files(sourceSets["main"].java.srcDirs.flatMap {
        fileTree(it).matching { include("**/schema/**") }
    })
    outputs.file(out)
    dependsOn("compileJava")
}

// Bundle the spec into the mod jar so rpc.discover can return it at runtime.
sourceSets["main"].resources.srcDir(file("../proto"))
tasks.named("processResources") { dependsOn(generateOpenRpc) }
