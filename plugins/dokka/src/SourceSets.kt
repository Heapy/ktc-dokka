package io.heapy.ktc.plugins.dokka

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

internal data class DocumentationSourceSet(
    val name: String,
    val platform: String,
    val roots: List<Path>,
    val classpath: List<Path>,
    val dependsOn: List<String>,
)

private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
private fun cap(value: String) = value.replaceFirstChar { it.uppercaseChar() }

internal fun prepareSourceSets(project: Path, moduleDir: Path, module: String, output: Path, timeout: Int): List<DocumentationSourceSet> {
    require(timeout > 0) { "dokka.timeoutSeconds must be positive" }
    val build = output.resolve("model-build")
    runToolchain(project, build, listOf("show", "settings", "-m", module), output.resolve("settings.log"), timeout)
    val targets = Regex("^settings@([A-Za-z0-9+]+):\\s*$", RegexOption.MULTILINE)
        .findAll(output.resolve("settings.log").readText()).flatMap { it.groupValues[1].split('+') }.distinct().toList()
    check(targets.isNotEmpty()) { "Cannot discover platforms from Kotlin Toolchain 0.13 settings" }
    if (targets.size == 1) {
        val target = targets.single()
        val task = compilationTask(target)
        runToolchain(project, build, listOf("task", ":$module:$task"), output.resolve("compile.log"), timeout)
        val inputs = compilationInputs(build, module, task)
        return listOf(DocumentationSourceSet("${target}Main", analysisPlatform(listOf(target)), inputs.roots, inputs.classpath, emptyList()))
    }

    // This asks KTC to resolve and compile shared metadata, never to publish it. The descriptor
    // carries KTC's actual hierarchy, including aliases and dependency refinements.
    runToolchain(project, build, listOf("task", ":$module:assembleMetadata"), output.resolve("metadata.log"), timeout)
    val structureFile = build.resolve("tasks/_${module}_assembleMetadata/kotlin-project-structure-metadata.json")
    check(structureFile.isRegularFile()) { "KMP documentation requires KTC publication metadata; configure publishing group and version" }
    val structure = Json.parseToJsonElement(structureFile.readText()).jsonObject.getValue("projectStructure").jsonObject
    val variants = structure.getValue("variants").jsonArray.map { it.jsonObject }
    val sets = structure.getValue("sourceSets").jsonArray.map { it.jsonObject }
    val nodes = sets.map { node ->
        val name = node.getValue("name").jsonPrimitive.content
        val supportingTargets = targets.filter { target ->
            variants.any { it.getValue("name").jsonPrimitive.content == "${target}ApiElements" && name in it.strings("sourceSet") }
        }
        check(supportingTargets.isNotEmpty()) { "No publication variant for source set $name" }
        val task = "compileMetadata" + cap(name.removeSuffix("Main"))
        val inputs = compilationInputs(build, module, task)
        DocumentationSourceSet(name, analysisPlatform(supportingTargets), inputs.roots, inputs.classpath, node.strings("dependsOn"))
    }.toMutableList()
    val ownedRoots = nodes.flatMap { it.roots }.toSet()
    for (target in targets) {
        val variantParents = variants.firstOrNull { it.getValue("name").jsonPrimitive.content == "${target}ApiElements" }
            ?.strings("sourceSet").orEmpty()
        val parents = variantParents.filter { parent -> nodes.any { it.name == parent } }
        check(parents.isNotEmpty()) { "No shared source sets for $target" }
        val platformRoots = (variantParents.map { it.removeSuffix("Main") } + target).map { moduleDir.resolve("src@$it") }
        val inheritedClasspath = nodes.filter { it.name in parents }.flatMap { it.classpath }
        val inputs = if (platformRoots.any { it !in ownedRoots && hasSources(it) }) {
            val task = compilationTask(target)
            runToolchain(project, build, listOf("task", ":$module:$task"), output.resolve("$target.log"), timeout)
            compilationInputs(build, module, task)
        } else {
            CompilationInputs(emptyList(), emptyList())
        }
        nodes += DocumentationSourceSet("${target}Main", analysisPlatform(listOf(target)), inputs.roots.filterNot { it in ownedRoots }, (inputs.classpath + inheritedClasspath).distinct(), parents)
    }
    return nodes
}

private fun compilationTask(target: String): String = when (target) {
    "jvm", "js", "wasmJs", "wasmWasi" -> "compile" + cap(target)
    "android" -> error("Android documentation is not supported by this adapter yet")
    else -> "compile" + cap(target) + "Debug"
}

private fun analysisPlatform(targets: List<String>): String = when {
    targets == listOf("jvm") -> "jvm"
    targets == listOf("js") -> "js"
    targets.all { it.startsWith("wasm") } -> "wasm"
    targets.all { it !in setOf("jvm", "android", "js", "wasmJs", "wasmWasi") } -> "native"
    else -> "common"
}

private data class CompilationInputs(val roots: List<Path>, val classpath: List<Path>)

// KTC 0.13 does not expose KMP sources/classpaths through its public plugin API.
// Read the versioned inputs of completed compiler tasks, not a guessed Maven classpath.
private fun compilationInputs(build: Path, module: String, task: String): CompilationInputs {
    val prefix = "_${module}_$task-"
    val state = Files.list(build.resolve("incremental.state")).use { files ->
        files.filter { it.name.startsWith(prefix) }.toList().singleOrNull()
    } ?: error("No KTC compilation state for $module:$task")
    val json = Json.parseToJsonElement(state.readText()).jsonObject
    check(json.getValue("codeVersion").jsonPrimitive.content == "0.13.0") { "Dokka's compilation model reader requires Kotlin Toolchain 0.13.0" }
    val files = json.strings("inputFiles").map(Path::of).distinct()
    val roots = files.filter { it.name != "resources" && !it.name.startsWith("resources@") && hasSources(it) }
    val classpath = files.filter { path ->
        path !in roots && path.exists() &&
            (path.extension in setOf("jar", "klib") || path.resolve("default/manifest").isRegularFile() || hasClassFiles(path))
    }.filterNot { path -> path.parent == build.resolve("tasks") && path.name.startsWith("_${module}_compileMetadata") }
    return CompilationInputs(roots, classpath)
}

private fun hasSources(path: Path): Boolean = containsFile(path) { it.extension in setOf("kt", "java") }
private fun hasClassFiles(path: Path): Boolean = containsFile(path) { it.extension == "class" }
private fun containsFile(path: Path, predicate: (Path) -> Boolean): Boolean {
    if (path.isRegularFile()) return predicate(path)
    if (!path.isDirectory()) return false
    return Files.walk(path).use { files -> files.anyMatch { it.isRegularFile() && predicate(it) } }
}

private fun runToolchain(project: Path, build: Path, args: List<String>, log: Path, timeout: Int) {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val command = if (windows) listOf("cmd.exe", "/c", project.resolve("kotlin.bat").toString())
        else listOf("sh", project.resolve("kotlin").toString())
    val exitCode = executeProcess(
        command + args + listOf("--project-dir", project.toString(), "--build-dir", build.toString()),
        project, timeout, log,
    )
    check(exitCode == 0) { "KTC failed for ${args.joinToString(" ")}:\n${log.readText().takeLast(12000)}" }
}
