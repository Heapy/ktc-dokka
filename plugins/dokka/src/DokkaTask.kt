package io.heapy.ktc.plugins.dokka

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/** Runs Dokka in its own JVM so its compiler dependencies do not affect plugin execution. */
@OptIn(ExperimentalPathApi::class)
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
public fun generateHtml(
    @Input settings: DokkaSettings,
    moduleName: String,
    @Input(inferTaskDependency = false) projectDir: Path,
    moduleDir: String,
    @Input dokkaClasspath: Classpath,
    @Input htmlClasspath: Classpath,
    @Output outputDirectory: Path,
) {
    require(settings.jdkVersion >= 8) { "dokka.jdkVersion must be at least 8" }
    require(settings.documentedVisibilities.isNotEmpty()) { "Choose at least one documented visibility" }
    settings.includes.forEach { require(it.isRegularFile()) { "Dokka include file does not exist: $it" } }
    val cliJar = dokkaClasspath.resolvedFiles.singleOrNull()
        ?: error("Expected the standalone Dokka CLI fat JAR, got ${dokkaClasspath.resolvedFiles}")
    outputDirectory.createDirectories()
    val site = outputDirectory.resolve("html")
    site.deleteRecursively()
    val sourceSets = prepareSourceSets(projectDir, Path.of(moduleDir), moduleName, outputDirectory, settings.timeoutSeconds)
    val configuration = buildJsonObject {
        put("moduleName", moduleName)
        put("outputDir", site.toString())
        put("offlineMode", settings.offlineMode)
        put("failOnWarning", settings.failOnWarning)
        put("suppressInheritedMembers", settings.suppressInheritedMembers)
        put("pluginsClasspath", strings(htmlClasspath.resolvedFiles.map(Path::toString)))
        put("sourceSets", buildJsonArray {
            for (sourceSet in sourceSets) add(buildJsonObject {
                put("sourceSetID", sourceSetId(moduleName, sourceSet.name))
                put("displayName", sourceSet.name.removeSuffix("Main"))
                put("analysisPlatform", sourceSet.platform)
                put("dependentSourceSets", buildJsonArray {
                    sourceSet.dependsOn.forEach { add(sourceSetId(moduleName, it)) }
                })
                put("sourceRoots", strings(sourceSet.roots.map(Path::toString)))
                put("classpath", strings(sourceSet.classpath.map(Path::toString)))
                put("jdkVersion", settings.jdkVersion)
                put("documentedVisibilities", strings(settings.documentedVisibilities.map(Visibility::name)))
                put("reportUndocumented", settings.reportUndocumented)
                put("skipDeprecated", settings.skipDeprecated)
                put("includes", strings(settings.includes.map(Path::toString)))
            })
        })
    }
    val configFile = outputDirectory.resolve("dokka.json")
    configFile.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), configuration))
    val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
    val process = ProcessBuilder(java.toString(), "-jar", cliJar.toString(), configFile.toString())
        .redirectErrorStream(true).start()
    val reader = thread(name = "dokka-output", isDaemon = true) {
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::println) }
    }
    try {
        val exit = process.waitFor()
        reader.join()
        check(exit == 0) { "Dokka failed with exit code $exit. See its diagnostics above." }
        check(site.resolve("index.html").isRegularFile()) { "Dokka did not generate $site/index.html" }
        verifyResolvedTypes(site)
        println("Dokka HTML: ${site.resolve("index.html")}")
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun sourceSetId(module: String, name: String) = buildJsonObject {
    put("scopeId", module)
    put("sourceSetName", name)
}

internal fun verifyResolvedTypes(site: Path) {
    java.nio.file.Files.walk(site).use { files ->
        val broken = files.filter { it.toString().endsWith(".html") }.filter {
            val html = java.nio.file.Files.readString(it)
            "ERROR CLASS" in html || "Symbol not found for" in html
        }.toList()
        check(broken.isEmpty()) {
            "Dokka generated unresolved types in: " + broken.joinToString { site.relativize(it).toString() }
        }
    }
}
