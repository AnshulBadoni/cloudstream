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

tasks.register("makePlugin") {
    dependsOn("jar")
    doLast {
        val distDir = file("build/dist")
        distDir.mkdirs()

        val libsDir = file("build/libs")
        val jarFile = libsDir.listFiles()?.firstOrNull { it.extension == "jar" && !it.name.contains("plain") }
            ?: file("build/libs/cloudstrem_Scrapper-1.0.0.jar")

        val cs3File = File(distDir, "PornTrex.cs3")
        if (jarFile.exists()) {
            jarFile.copyTo(cs3File, overwrite = true)
        }

        val pluginsJson = """
[
  {
    "name": "PornTrex",
    "pluginClassName": "com.cloudstream.scraper.cloudstream.PornTrexPlugin",
    "version": 1,
    "description": "High quality adult streaming provider with actor catalogs, multi-resolution streaming (480p/720p/1080p), and fast search.",
    "authors": ["AnshulBadoni"],
    "status": 1,
    "types": ["NSFW"],
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
        println("✓ Successfully generated repo.json, plugins.json, and PornTrex.cs3 in ${distDir.absolutePath}")
    }
}
