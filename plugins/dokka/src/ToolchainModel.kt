package io.heapy.ktc.plugins.dokka

import kotlinx.serialization.json.Json
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

// Everything below depends on Kotlin Toolchain 0.13.0 internals and must be revisited on upgrade.

// KTC 0.13 places ${taskOutputDir} at <build>/tasks/<task>. Share dependency compilation
// across modules in <build>/dokka-model-build, separately from the outer build lock.
internal fun modelBuildDirectory(taskOutput: Path): Path {
    val tasks = taskOutput.toAbsolutePath().normalize().parent
    check(tasks != null && tasks.name == "tasks" && tasks.parent != null) {
        "Unsupported Kotlin Toolchain task layout, expected <build>/tasks/<task>: $taskOutput"
    }
    return tasks.parent.resolve("dokka-model-build")
}

internal data class CompilationInputs(val roots: List<Path>, val classpath: List<Path>)

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
internal fun compilationInputs(build: Path, module: String, task: String): CompilationInputs {
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

internal fun hasSources(path: Path): Boolean = containsFile(path) { it.extension in setOf("kt", "java") }
private fun hasClassFiles(path: Path): Boolean = containsFile(path) { it.extension == "class" }
private fun containsFile(path: Path, predicate: (Path) -> Boolean): Boolean {
    if (path.isRegularFile()) return predicate(path)
    if (!path.isDirectory()) return false
    return Files.walk(path).use { files -> files.anyMatch { it.isRegularFile() && predicate(it) } }
}

internal fun runToolchain(project: Path, build: Path, args: List<String>, log: Path, timeout: Int) {
    val command = if (isWindows()) listOf("cmd.exe", "/c", project.resolve("kotlin.bat").toString())
        else listOf("sh", project.resolve("kotlin").toString())
    val exitCode = executeProcess(
        command + args + listOf("--project-dir", project.toString(), "--build-dir", build.toString()),
        project, timeout, log,
    )
    check(exitCode == 0) { "KTC failed for ${args.joinToString(" ")}:\n${log.readText().takeLast(12000)}" }
}
