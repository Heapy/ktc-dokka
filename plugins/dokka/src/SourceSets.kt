package io.heapy.ktc.plugins.dokka

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
    val leafInputs = mutableMapOf<String, CompilationInputs>()
    fun compile(target: String): CompilationInputs = leafInputs.getOrPut(target) {
        val task = compilationTask(target)
        runToolchain(project, build, listOf("task", ":$module:$task"), output.resolve("$target.log"), timeout)
        compilationInputs(build, module, task)
    }
    if (targets.size == 1) {
        val target = targets.single()
        val inputs = compile(target)
        return listOf(DocumentationSourceSet("${target}Main", analysisPlatform(listOf(target)), inputs.roots, inputs.classpath, emptyList()))
    }

    // The descriptor retains variant ancestry even for shared JVM/Android fragments, which
    // KTC deliberately does not compile to KLib metadata. It does not publish anything.
    runToolchain(project, build, listOf("task", ":$module:assembleMetadata"), output.resolve("metadata.log"), timeout)
    val structureFile = build.resolve("tasks/_${module}_assembleMetadata/kotlin-project-structure-metadata.json")
    check(structureFile.isRegularFile()) { "KMP documentation requires KTC publication metadata; configure publishing group and version" }
    val structure = Json.parseToJsonElement(structureFile.readText()).jsonObject.getValue("projectStructure").jsonObject
    val hierarchy = sourceSetHierarchy(structure, targets)
    val publishedSets = structure.getValue("sourceSets").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet()
    val nodes = hierarchy.map { node ->
        val roots = sourceRoots(moduleDir, node.name).filter(::hasSources)
        val compiledMetadata = node.name in publishedSets && metadataCompilationRequired(node.targets)
        val inputs = when {
            compiledMetadata ->
                compilationInputs(build, module, "compileMetadata" + cap(node.name.removeSuffix("Main")))
            roots.isNotEmpty() -> compile(node.targets.firstOrNull { it == "jvm" } ?: node.targets.first())
            else -> CompilationInputs(emptyList(), emptyList())
        }
        // Metadata compiles only this fragment's sources, including generated roots. Ancestors
        // are KLib inputs. Leaf compilations combine fragments and still need root filtering.
        val ownedRoots = if (compiledMetadata) inputs.roots else inputs.roots.filter { it in roots }
        DocumentationSourceSet(node.name, analysisPlatform(node.targets), ownedRoots, inputs.classpath, node.dependsOn)
    }.toMutableList()
    val ownedRoots = nodes.flatMap { it.roots }.toSet()
    for (target in targets) {
        val parents = hierarchy.filter { target in it.targets }.map { it.name }
        check(parents.isNotEmpty()) { "No shared source sets for $target" }
        val platformRoots = (parents + "${target}Main").flatMap { sourceRoots(moduleDir, it) }
        val inputs = if (target in leafInputs || platformRoots.any { it !in ownedRoots && hasSources(it) }) {
            compile(target)
        } else {
            CompilationInputs(emptyList(), emptyList())
        }
        nodes += DocumentationSourceSet("${target}Main", analysisPlatform(listOf(target)), inputs.roots.filterNot { it in ownedRoots }, inputs.classpath, parents)
    }
    // Inherit classpaths explicitly: Dokka's per-source-set analysis needs the common KLibs
    // as well as each leaf's JVM/Android classes (including android.jar).
    fun inheritedClasspath(name: String, visiting: Set<String> = emptySet()): List<Path> {
        check(name !in visiting) { "Cyclic documentation source-set hierarchy at $name" }
        val node = nodes.single { it.name == name }
        return (node.classpath + node.dependsOn.flatMap { inheritedClasspath(it, visiting + name) }).distinct()
    }
    return nodes.map {
        // A completed JVM/Android compilation already has its full platform classpath.
        // Inheriting a shared node's JVM dependency outputs into Android would mix variants.
        it.copy(classpath = if (it.platform == "jvm" && it.classpath.isNotEmpty()) it.classpath else inheritedClasspath(it.name))
    }
}

internal data class SourceSetHierarchy(val name: String, val targets: List<String>, val dependsOn: List<String>)

internal fun sourceSetHierarchy(structure: JsonObject, targets: List<String>): List<SourceSetHierarchy> {
    check(structure.getValue("formatVersion").jsonPrimitive.content == "0.3.3") {
        "Dokka requires Kotlin Toolchain 0.13 project structure format 0.3.3"
    }
    val variants = structure.getValue("variants").jsonArray.map { it.jsonObject }
    val ancestry = targets.associateWith { target ->
        variants.single { it.getValue("name").jsonPrimitive.content == "${target}ApiElements" }.strings("sourceSet")
    }
    val declared = structure.getValue("sourceSets").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
    return (declared.keys + ancestry.values.flatten()).distinct().map { name ->
        val supporting = targets.filter { name in ancestry.getValue(it) }
        check(supporting.isNotEmpty()) { "No publication variant for source set $name" }
        // KTC emits a refinement traversal, with each ancestor after its descendants.
        // A missing fragment's common suffix across all supporting variants contains its ancestors.
        val parents = declared[name]?.strings("dependsOn") ?: supporting
            .map { target -> ancestry.getValue(target).dropWhile { it != name }.drop(1).toSet() }
            .reduce { left, right -> left intersect right }.toList()
        SourceSetHierarchy(name, supporting, parents)
    }
}

private fun sourceRoots(module: Path, name: String): List<Path> =
    (if (name == "commonMain") listOf(module.resolve("src")) else emptyList()) +
        listOf(module.resolve("src@${name.removeSuffix("Main")}"), module.resolve("src@$name"))

internal fun metadataCompilationRequired(targets: List<String>): Boolean =
    targets.size > 1 && targets.toSet() !in setOf(setOf("jvm", "android"), setOf("wasmJs", "wasmWasi"))

private fun compilationTask(target: String): String = when (target) {
    "jvm", "js", "wasmJs", "wasmWasi" -> "compile" + cap(target)
    "android" -> "compileAndroidRelease"
    else -> "compile" + cap(target) + "Debug"
}

internal fun analysisPlatform(targets: List<String>): String = when {
    targets.all { it in setOf("jvm", "android") } -> "jvm"
    targets == listOf("js") -> "js"
    targets.all { it.startsWith("wasm") } -> "wasm"
    targets.all { it !in setOf("jvm", "android", "js", "wasmJs", "wasmWasi") } -> "native"
    else -> "common"
}

private data class CompilationInputs(val roots: List<Path>, val classpath: List<Path>)

// KTC 0.13.0's IncrementalCache.stateFileFor, state format 6 (Apache-2.0).
// Copyright 2000-2026 JetBrains s.r.o. and contributors.
// The hash uses the full task key; the readable prefix alone is neither complete nor unique.
internal fun compilationStatePath(build: Path, module: String, task: String): Path {
    val key = ":$module:$task"
    val prefix = key.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_").take(50)
    val hash = MessageDigest.getInstance("MD5")
        .digest("$key\nstate format version: 6".toByteArray(Charsets.UTF_8)).toHexString().take(10)
    return build.resolve("incremental.state/$prefix-$hash")
}

// KTC 0.13 does not expose KMP sources/classpaths through its public plugin API.
// Read the versioned inputs of completed compiler tasks, not a guessed Maven classpath.
private fun compilationInputs(build: Path, module: String, task: String): CompilationInputs {
    val state = compilationStatePath(build, module, task)
    check(state.isRegularFile()) { "No KTC compilation state for $module:$task at $state" }
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
