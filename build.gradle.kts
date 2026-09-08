import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.io.FileOutputStream

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    application
}

group = "com.cloudstream.scraper"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin & Coroutines
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // HTML Parsing
    implementation("org.jsoup:jsoup:1.17.2")

    // HTTP Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // YAML & JSON Parsing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

application {
    mainClass.set("com.cloudstream.scraper.cli.ScraperDebugRunnerKt")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("android/**")
    exclude("com/lagradost/**")
    exclude("com/cloudstream/scraper/cli/**")
    exclude("com/cloudstream/scraper/config/ConfigLoader*")
}

tasks.register("makePlugin") {
    dependsOn("jar")
    doLast {
        val distDir = file("build/dist")
        distDir.mkdirs()

        val libsDir = file("build/libs")
        val jarFile = libsDir.listFiles()?.firstOrNull { it.extension == "jar" && !it.name.contains("plain") }
            ?: file("build/libs/cloudstream-scraper-1.0.0.jar")

        val dexDir = file("build/dist/dex")
        dexDir.mkdirs()

        // Locate d8 executable
        fun findD8(): String? {
            val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            if (androidHome != null) {
                val buildToolsDir = File(androidHome, "build-tools")
                if (buildToolsDir.exists()) {
                    val latestTools = buildToolsDir.listFiles()?.maxByOrNull { it.name }
                    if (latestTools != null) {
                        val d8Lin = File(latestTools, "d8")
                        val d8Bat = File(latestTools, "d8.bat")
                        if (d8Lin.exists()) return d8Lin.absolutePath
                        if (d8Bat.exists()) return d8Bat.absolutePath
                    }
                }
            }
            val localAppData = System.getenv("LOCALAPPDATA")
            if (localAppData != null) {
                val winBuildTools = File(localAppData, "Android/Sdk/build-tools")
                if (winBuildTools.exists()) {
                    val latestTools = winBuildTools.listFiles()?.maxByOrNull { it.name }
                    val d8Bat = File(latestTools, "d8.bat")
                    if (d8Bat.exists()) return d8Bat.absolutePath
                }
            }
            return null
        }

        fun findAndroidJar(): String? {
            val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
                ?: System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" }
            if (androidHome != null) {
                val platformsDir = File(androidHome, "platforms")
                if (platformsDir.exists()) {
                    val latestPlatform = platformsDir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
                    val jar = latestPlatform?.let { File(it, "android.jar") }
                    if (jar != null && jar.exists()) return jar.absolutePath
                }
            }
            return null
        }

        val d8Path = findD8()
        val androidJar = findAndroidJar()
        if (d8Path != null && File(d8Path).exists()) {
            println("Converting JAR to Dalvik DEX via d8 ($d8Path)...")
            val cmd = mutableListOf(
                d8Path,
                "--release",
                "--min-api", "21",
                "--output", dexDir.absolutePath
            )
            if (androidJar != null) {
                cmd.add("--lib")
                cmd.add(androidJar)
            }
            cmd.add(jarFile.absolutePath)

            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            println(output)
        } else {
            println("Warning: d8 not found, skipping DEX compilation.")
        }

        val cs3File = File(distDir, "PornTrex.cs3")
        val manifestFile = file("manifest.json")

        // Create .cs3 ZIP archive containing manifest.json first, then classes*.dex
        ZipOutputStream(FileOutputStream(cs3File)).use { zos ->
            if (manifestFile.exists()) {
                zos.putNextEntry(ZipEntry("manifest.json"))
                manifestFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            dexDir.listFiles()?.filter { it.extension == "dex" }?.sortedBy { it.name }?.forEach { dexFile ->
                zos.putNextEntry(ZipEntry(dexFile.name))
                dexFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        val pluginsJson = """
[
  {
    "name": "PornTrex",
    "internalName": "PornTrex",
    "version": 10,
    "apiVersion": 1,
    "description": "High quality adult streaming provider with actor catalogs, multi-resolution streaming (480p/720p/1080p), and fast search.",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "Others"],
    "iconUrl": "https://www.porntrex.com/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/PornTrex.cs3",
    "fileSize": ${if (cs3File.exists()) cs3File.length() else 102400}
  }
]
        """.trimIndent()

        val repoJson = """
{
  "name": "PornTrex",
  "description": "High quality adult streaming provider with actor catalogs and multi-resolution streaming.",
  "manifestVersion": 1,
  "pluginLists": [
    "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/plugins.json"
  ]
}
        """.trimIndent()

        File(distDir, "plugins.json").writeText(pluginsJson)
        File(distDir, "builds.json").writeText(pluginsJson)
        File(distDir, "repo.json").writeText(repoJson)
        println("✓ Successfully generated repo.json, plugins.json, and PornTrex.cs3 (size: ${cs3File.length()} bytes) in ${distDir.absolutePath}")
    }
}
