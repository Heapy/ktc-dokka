#!/usr/bin/env kotlinr
// Run with Kotlin 2.4.21+ and JDK 25: kotlinr scripts/integration.main.kts

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

val repo = __FILE__.canonicalFile.parentFile.parentFile
val windows = System.getProperty("os.name").startsWith("Windows")

fun temporary(prefix: String, block: (File) -> Unit) {
    val directory = Files.createTempDirectory(prefix).toFile()
    block(directory)
    directory.deleteRecursively()
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


// Regression: local module types must resolve, and common/actual declarations retain their platforms.
temporary("dokka-kmp-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "plugins")) copy(repo.resolve(name), project.resolve(name))
    val native = when {
        windows -> "mingwX64"
        System.getProperty("os.name").startsWith("Mac") -> "macosArm64"
        else -> "linuxX64"
    }
    write(project, "project.yaml", """
        modules: [api, model, plugins/dokka]
        plugins: [//plugins/dokka]
    """.trimIndent() + "\n")
    for (name in listOf("api", "model")) {
        write(project, "$name/module.yaml", """
            product:
              type: kmp/lib
              platforms: [jvm, js, wasmJs, $native]
            settings:
              publishing:
                enabled: true
                group: test.docs
                version: 1.0
        """.trimIndent() + "\n")
    }
    project.resolve("api/module.yaml").appendText("""
        dependencies:
          - ../model: exported
        aliases:
          nonJvm: [js, wasmJs, $native]
        plugins:
          dokka: enabled
    """.trimIndent() + "\n")
    write(project, "model/src/Model.kt", """
        package fixture
        /** A model declared in another module. */
        class Node(val value: String)
    """.trimIndent() + "\n")
    write(project, "api/src/Api.kt", """
        package fixture
        /** Return the value of this node. */
        fun Node.render(): String = value
        /** Platform identity. */
        expect fun platformName(): String
    """.trimIndent() + "\n")
    write(project, "api/src@nonJvm/Shared.kt", """
        package fixture
        /** Shared web and native declaration. */
        fun Node.nonJvmValue(): String = value
    """.trimIndent() + "\n")
    for (platform in listOf("jvm", "js", "wasmJs", native)) {
        write(project, "api/src@$platform/Platform.kt", """
            package fixture
            /** Platform identity. */
            actual fun platformName(): String = "$platform"
        """.trimIndent() + "\n")
    }
    toolchain(project, "do", "dokkaHtml", "-m", "api")
    val output = project.resolve("build/tasks/_api_dokkaHtml@dokka")
    val pages = output.resolve("html").walkTopDown().filter { it.isFile && it.extension == "html" }.toList()
    val html = pages.joinToString("\n") { it.readText() }
    check("ERROR CLASS" !in html && "Symbol not found for" !in html)
    check("Node" in html && "Return the value of this node" in html && "nonJvmValue" in html)
    for (name in listOf("commonMain", "jvmMain", "jsMain", "wasmJsMain", "${native}Main", "nonJvmMain")) {
        check("api/$name" in html) { "Missing platform/source set $name" }
    }
    val config = output.resolve("dokka.json").readText()
    check("kotlin-stdlib-commonMain" in config && "_model_compileMetadataCommon" in config)
    check("CompiledJvmArtifact" in config && "modeljvm" in config)
    check("dependentSourceSets" in config && "src@nonJvm" in config)

    // The next run must observe dependency signature changes, even with the child compilation cache populated.
    project.resolve("model/src/Model.kt").appendText("\n/** New dependency type. */\nclass Added\n")
    project.resolve("api/src/Api.kt").appendText("\n/** New dependency signature. */\nfun Added.added(): Node = Node(\"new\")\n")
    toolchain(project, "do", "dokkaHtml", "-m", "api")
    val regenerated = output.resolve("html").walkTopDown().filter { it.isFile && it.extension == "html" }.joinToString("\n") { it.readText() }
    check("ERROR CLASS" !in regenerated && "Symbol not found for" !in regenerated && "Added" in regenerated)
}
println("Dokka integration passed: JVM/KMP, local dependency types, metadata classpaths, expect/actual, aliases and regeneration.")
