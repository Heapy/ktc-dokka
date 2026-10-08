#!/usr/bin/env kotlinr
// Run with Kotlin 2.4.21+ and JDK 25: kotlinr scripts/integration.main.kts

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

val repo = __FILE__.canonicalFile.parentFile.parentFile
val windows = System.getProperty("os.name").startsWith("Windows")

fun temporary(prefix: String, block: (File) -> Unit) {
    val directory = Files.createTempDirectory(prefix).toFile()
    try { block(directory) } finally { directory.deleteRecursively() }
}

fun write(root: File, path: String, text: String) {
    root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
}

fun copy(source: File, destination: File) {
    check(source.copyRecursively(destination, overwrite = true)) { "Could not copy $source to $destination" }
    if (!windows) {
        source.walkTopDown().filter { it.isFile && it.canExecute() }.forEach { file ->
            val target = if (source.isDirectory) destination.resolve(file.relativeTo(source)) else destination
            check(target.setExecutable(true, false)) { "Could not preserve executable permission: $target" }
        }
    }
}

data class CommandResult(val exitCode: Int, val output: String)
fun command(directory: File, arguments: List<String>, environment: Map<String, String?> = emptyMap(), timeout: Long = 600): CommandResult {
    val log = Files.createTempFile("ktc-command-", ".log").toFile()
    try {
        val process = ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).redirectOutput(log).apply {
            environment.forEach { (key, value) -> if (value == null) environment().remove(key) else environment()[key] = value }
        }.start()
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) { "Timed out: $arguments\n${log.readText()}" }
            return CommandResult(process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
            }
        }
    } finally { log.delete() }
}

fun toolchain(project: File, vararg arguments: String, succeeds: Boolean = true, diagnostic: String? = null): String {
    val wrapper = project.resolve(if (windows) "kotlin.bat" else "kotlin").absolutePath
    val invocation = if (windows) listOf("cmd.exe", "/c", wrapper) else listOf("sh", wrapper)
    val result = command(project, invocation + arguments)
    check((result.exitCode == 0) == succeeds) { "Unexpected exit ${result.exitCode}: ${arguments.toList()}\n${result.output}" }
    check(diagnostic == null || diagnostic in result.output) { "Missing diagnostic $diagnostic:\n${result.output}" }
    println("PASS: ${arguments.joinToString(" ")} (${if (succeeds) "success" else "expected failure"})")
    return result.output
}

temporary("dokka-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "project.yaml", "plugins", "templates", "example/src", "example/module.yaml")) {
        copy(repo.resolve(name), project.resolve(name))
    }
    toolchain(project, "do", "dokkaHtml", "-m", "example")
    val indexes = project.resolve("build").walkTopDown().filter { it.name == "index.html" && it.parentFile.name == "html" }.toList()
    check(indexes.size == 1) { "Expected one documentation site, got $indexes" }
    val html = indexes.single().parentFile.walkTopDown().filter { it.isFile && it.extension == "html" }.joinToString("\n") { it.readText() }
    check("Greeter" in html && "Returns a friendly greeting" in html)
    check("implementationDetail" !in html) { "Default public-only visibility was ignored" }
    project.resolve("example/module.yaml").appendText("\nplugins:\n  dokka:\n    reportUndocumented: true\n    failOnWarning: true\n")
    project.resolve("example/src/Greeter.kt").appendText("\npublic fun undocumentedFunction(): String = \"missing KDoc\"\n")
    toolchain(project, "do", "dokkaHtml", "-m", "example", succeeds = false, diagnostic = "Dokka failed with exit code")
}
println("Dokka integration passed: HTML/KDoc rendering, public-only visibility, documentation-warning failure.")
