import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

val prometeus_version: String by project
val kodein_version: String by project
val h2_version: String by project
val flyway_version: String by project
val exposed_version: String by project
val postgres_version: String by project
val hikari_version: String by project
val testcontainers_version: String by project
plugins {
    kotlin("jvm") version "2.4.0"
    id("io.ktor.plugin") version "3.5.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
}

group = "io.vladar107"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-swagger-jvm")
    implementation("io.ktor:ktor-server-metrics-micrometer-jvm")
    implementation("io.micrometer:micrometer-registry-prometheus:$prometeus_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-resources")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("org.kodein.di:kodein-di:$kodein_version")
    implementation("org.kodein.di:kodein-di-framework-ktor-server-jvm:$kodein_version")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-server-status-pages")
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("com.h2database:h2:$h2_version")
    implementation("org.flywaydb:flyway-core:$flyway_version")
    implementation("org.flywaydb:flyway-database-postgresql:$flyway_version")
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    testImplementation("org.testcontainers:testcontainers:$testcontainers_version")
    testImplementation("org.testcontainers:postgresql:$testcontainers_version")
}

kotlin { jvmToolchain(25) }

tasks.test { maxParallelForks = 1 }

// Patch the Flyway services file after shadowJar: shadow 9.x does not properly merge
// META-INF/services when the same file exists in flyway-core AND flyway-database-postgresql.
// The postgresql module's file overwrites flyway-core's, losing H2/SQLite support.
// Fix: use 'zip -u' to update the fat JAR with a correctly merged services file.
tasks.register("patchFlywayServices") {
    dependsOn("shadowJar")
    val shadowTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    inputs.files(shadowTask.map { it.archiveFile })
    inputs.files(configurations.runtimeClasspath)
    outputs.upToDateWhen { false }

    doLast("patch flyway services") {
        val serviceFile = "META-INF/services/org.flywaydb.core.extensibility.Plugin"
        val fatJar = shadowTask.get().archiveFile.get().asFile

        val flywayCore = configurations["runtimeClasspath"].resolvedConfiguration
            .resolvedArtifacts
            .first { it.moduleVersion.id.module.group == "org.flywaydb" && it.moduleVersion.id.module.name == "flyway-core" }
            .file

        // Read the full list of service entries from flyway-core and the fat JAR
        val allEntries = mutableListOf<String>()
        ZipFile(flywayCore).use { zip ->
            val e = zip.getEntry(serviceFile)
            if (e != null) allEntries.addAll(zip.getInputStream(e).bufferedReader().readLines())
        }
        ZipFile(fatJar).use { zip ->
            val e = zip.getEntry(serviceFile)
            if (e != null) allEntries.addAll(zip.getInputStream(e).bufferedReader().readLines())
        }
        val merged = allEntries.filter { it.isNotBlank() }.distinct()
        logger.lifecycle("Merged Flyway service entries: $merged")

        // Write merged file to temp dir and zip-update the fat JAR
        val mergedContent = (merged.joinToString("\n") + "\n").toByteArray()
        logger.lifecycle("Patching fat JAR in-place with ${merged.size} merged service entries")

        // Rewrite the JAR, replacing only the services file entry
        val tmpJar = File(fatJar.parentFile, "${fatJar.name}.tmp")
        ZipFile(fatJar).use { inZip ->
            ZipOutputStream(tmpJar.outputStream().buffered()).use { outZip ->
                inZip.entries().asSequence().forEach { entry ->
                    val newEntry = ZipEntry(entry.name)
                    outZip.putNextEntry(newEntry)
                    if (!entry.isDirectory) {
                        if (entry.name == serviceFile) {
                            outZip.write(mergedContent)
                        } else {
                            inZip.getInputStream(entry).copyTo(outZip)
                        }
                    }
                    outZip.closeEntry()
                }
            }
        }
        tmpJar.renameTo(fatJar)
        logger.lifecycle("Fat JAR services patched successfully.")
    }
}

tasks.named("buildFatJar") { dependsOn("patchFlywayServices") }
