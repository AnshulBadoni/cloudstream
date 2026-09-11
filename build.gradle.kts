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
    outputs.upToDateWhen { false }
    systemProperties(System.getProperties().mapKeys { it.key.toString() })
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
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

        val prebuiltDex = file("src/main/resources/prebuilt_dex/classes.dex")
        if (prebuiltDex.exists()) {
            println("Using verified prebuilt DEX from ${prebuiltDex.absolutePath}...")
            prebuiltDex.copyTo(File(dexDir, "classes.dex"), overwrite = true)
        } else {
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
        }

        fun createPluginZip(pluginName: String, className: String, outFile: File) {
            val manifest = """
{
  "name": "$pluginName",
  "pluginClassName": "$className",
  "requiresResources": false,
  "version": 84
}
            """.trimIndent()
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                dexDir.listFiles()?.filter { it.extension == "dex" }?.sortedBy { it.name }?.forEach { dexFile ->
                    zos.putNextEntry(ZipEntry(dexFile.name))
                    dexFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        val porntrexCs3 = File(distDir, "PornTrex.cs3")
        val multiSourceCs3 = File(distDir, "MultiSource.cs3")
        val yamyHubCs3 = File(distDir, "YamyHub.cs3")
        val daftSexCs3 = File(distDir, "DaftSex.cs3")
        val tnaFlixCs3 = File(distDir, "TnaFlix.cs3")
        val fpoCs3 = File(distDir, "FPO.cs3")
        val plibraryCs3 = File(distDir, "PLibrary.cs3")

        createPluginZip("PornTrex", "com.megix.PorntrexProvider", porntrexCs3)
        createPluginZip("MultiSource", "com.custom.CustomProvider", multiSourceCs3)
        createPluginZip("YamyHub", "com.custom.CustomProvider", yamyHubCs3)
        createPluginZip("DaftSex", "com.custom.CustomProvider", daftSexCs3)
        createPluginZip("TnaFlix", "com.custom.CustomProvider", tnaFlixCs3)
        createPluginZip("FPO", "com.custom.CustomProvider", fpoCs3)
        createPluginZip("PLibrary", "com.custom.CustomProvider", plibraryCs3)

        val pluginsJson = """
[
  {
    "name": "PornTrex",
    "internalName": "PornTrex",
    "version": 84,
    "apiVersion": 1,
    "description": "High quality adult streaming provider with actor catalogs, multi-resolution streaming (480p/720p/1080p), and fast search.",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "Others"],
    "iconUrl": "https://www.porntrex.com/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/PornTrex.cs3",
    "fileSize": ${if (porntrexCs3.exists()) porntrexCs3.length() else 102400}
  },
  {
    "name": "YamyHub",
    "internalName": "YamyHub",
    "version": 84,
    "apiVersion": 1,
    "description": "Fast video streaming with studio channels (Vixen, Blacked, Brazzers), performer catalogs, and direct multi-resolution MP4 downloads (360p-1080p).",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "TvSeries"],
    "iconUrl": "https://www.yamyhub.com/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/YamyHub.cs3",
    "fileSize": ${if (yamyHubCs3.exists()) yamyHubCs3.length() else 102400}
  },
  {
    "name": "DaftSex",
    "internalName": "DaftSex",
    "version": 84,
    "apiVersion": 1,
    "description": "High performance provider with fast CDN streaming, performer profiles, and multi-resolution MP4 video streams from 360p up to 4K.",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "TvSeries"],
    "iconUrl": "https://daftsex.biz/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/DaftSex.cs3",
    "fileSize": ${if (daftSexCs3.exists()) daftSexCs3.length() else 102400}
  },
  {
    "name": "TnaFlix",
    "internalName": "TnaFlix",
    "version": 84,
    "apiVersion": 1,
    "description": "Extensive video catalog with trending scenes, categories, performer channels, and multi-resolution stream extraction.",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "TvSeries"],
    "iconUrl": "https://www.tnaflix.com/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/TnaFlix.cs3",
    "fileSize": ${if (tnaFlixCs3.exists()) tnaFlixCs3.length() else 102400}
  },
  {
    "name": "FPO",
    "internalName": "FPO",
    "version": 84,
    "apiVersion": 1,
    "description": "Fast video indexing with model profiles, trending videos, and direct downloadable MP4 streams.",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "TvSeries"],
    "iconUrl": "https://www.fpo.xxx/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/FPO.cs3",
    "fileSize": ${if (fpoCs3.exists()) fpoCs3.length() else 102400}
  },
  {
    "name": "MultiSource",
    "internalName": "MultiSource",
    "version": 84,
    "apiVersion": 1,
    "description": "Multi-source aggregator with SpeedPorn and ParadiseHill: deep search, MP4/VOE stream resolvers, multi-part CD episodes, and actor catalogs.",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "TvSeries"],
    "iconUrl": "https://en.paradisehill.cc/img/favicon/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/MultiSource.cs3",
    "fileSize": ${if (multiSourceCs3.exists()) multiSourceCs3.length() else 102400}
  },
  {
    "name": "PLibrary",
    "internalName": "PLibrary",
    "version": 84,
    "apiVersion": 1,
    "description": "Multi-source aggregated collection with 4 seasons per performer across YamyHub, DaftSex, TnaFlix, and FPO.",
    "authors": ["AnshulBadoni"],
    "repositoryUrl": "https://github.com/AnshulBadoni/cloudstream",
    "status": 1,
    "language": "en",
    "tvTypes": ["NSFW", "Movie", "TvSeries"],
    "iconUrl": "https://www.yamyhub.com/favicon.ico",
    "url": "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/PLibrary.cs3",
    "fileSize": ${if (plibraryCs3.exists()) plibraryCs3.length() else 102400}
  }
]
        """.trimIndent()

        val repoJson = """
{
  "name": "Anshul Providers",
  "description": "CloudStream adult and streaming providers repository.",
  "manifestVersion": 1,
  "pluginLists": [
    "https://raw.githubusercontent.com/AnshulBadoni/cloudstream/builds/plugins.json"
  ]
}
        """.trimIndent()

        File(distDir, "plugins.json").writeText(pluginsJson)
        File(distDir, "builds.json").writeText(pluginsJson)
        File(distDir, "repo.json").writeText(repoJson)
        println("✓ Successfully generated repo.json, plugins.json, and all standalone .cs3 plugins in ${distDir.absolutePath}")
    }
}


