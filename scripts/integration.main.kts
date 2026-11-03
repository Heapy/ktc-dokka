#!/usr/bin/env kotlinr
// Run with Kotlin 2.4.21+ and JDK 25: kotlinr scripts/integration.main.kts

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

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

fun verifyArchive(project: File, module: String) {
    val html = project.resolve("build/tasks/_${module}_dokkaHtml@dokka/html")
    val archive = project.resolve("build/tasks/_${module}_dokkaJar@dokka/$module-javadoc.jar")
    val files = html.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(html).invariantSeparatorsPath }
    check("index.html" in files && files.size > 1) { "Expected a complete Dokka site" }
    JarFile(archive).use { jar ->
        val entries = jar.entries().asSequence().toList()
        check(entries.map { it.name } == files.keys.sorted()) { "Archive does not contain exactly the complete HTML site" }
        for (entry in entries) {
            check(jar.getInputStream(entry).use { it.readBytes() }.contentEquals(files.getValue(entry.name).readBytes())) {
                "Archive entry differs from generated documentation: ${entry.name}"
            }
        }
    }
    println("PASS: $module Javadoc archive contains all ${files.size} generated files")
}

// Regression: metadata compiler roots include generated sources outside src/src@... .
temporary("dokka-generated-sources-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "plugins/dokka")) copy(repo.resolve(name), project.resolve(name))
    write(project, "project.yaml", "modules: [api, plugins/dokka, plugins/generator]\nplugins: [//plugins/dokka, //plugins/generator]\n")
    write(project, "api/module.yaml", """
        product:
          type: kmp/lib
          platforms: [jvm, js, wasmJs]
        settings:
          publishing:
            enabled: true
            group: test.docs
            version: 1.0
        plugins:
          dokka: enabled
          generator: enabled
    """.trimIndent() + "\n")
    write(project, "plugins/generator/module.yaml", "product: jvm/amper-plugin\n")
    write(project, "plugins/generator/plugin.yaml", """
        tasks:
          generate:
            action: !fixture.generate
              commonDirectory: ${'$'}{taskOutputDir}/common
              webDirectory: ${'$'}{taskOutputDir}/web
        generated:
          sources:
            - directory: ${'$'}{tasks.generate.action.commonDirectory}
            - directory: ${'$'}{tasks.generate.action.webDirectory}
              fragment:
                modifier: "@web"
    """.trimIndent() + "\n")
    write(project, "plugins/generator/src/Generate.kt", """
        package fixture
        import org.jetbrains.amper.plugins.Output
        import org.jetbrains.amper.plugins.TaskAction
        import java.nio.file.Path
        import kotlin.io.path.createDirectories
        import kotlin.io.path.writeText

        @TaskAction
        fun generate(@Output commonDirectory: Path, @Output webDirectory: Path) {
            commonDirectory.createDirectories().resolve("Common.kt").writeText(
                "package fixture\n/** Generated common API. */\nclass GeneratedCommon\n"
            )
            webDirectory.createDirectories().resolve("Web.kt").writeText(
                "package fixture\n/** Generated shared API. */\nclass GeneratedWeb(val common: GeneratedCommon)\n"
            )
        }
    """.trimIndent() + "\n")
    fun verifyGeneratedSources() {
        toolchain(project, "do", "dokkaJar", "-m", "api")
        verifyArchive(project, "api")
        val output = project.resolve("build/tasks/_api_dokkaHtml@dokka")
        val sourceSets = Json.parseToJsonElement(output.resolve("dokka.json").readText())
            .jsonObject.getValue("sourceSets").jsonArray.map { it.jsonObject }
        for ((directory, owner) in listOf("common" to "commonMain", "web" to "webMain")) {
            val root = project.resolve("build/dokka-model-build/tasks/_api_generate@generator/$directory").canonicalPath
            val owners = sourceSets.filter { set ->
                set.getValue("sourceRoots").jsonArray.any { it.jsonPrimitive.content == root }
            }.map { it.getValue("sourceSetID").jsonObject.getValue("sourceSetName").jsonPrimitive.content }
            check(owners == listOf(owner)) { "Generated $directory roots must belong only to $owner, got $owners" }
        }
        val pages = output.resolve("html").walkTopDown().filter { it.isFile && it.extension == "html" }.toList()
        for ((directory, name) in listOf("-generated-common" to "GeneratedCommon", "-generated-web" to "GeneratedWeb")) {
            check(pages.any { it.name == "index.html" && it.parentFile.name == directory && name in it.readText() }) {
                "No documentation page for $name"
            }
        }
    }
    // Only generated source files and no leaf compilations: generated API must still appear.
    verifyGeneratedSources()
    // Compiling a leaf must not move or duplicate common generated sources into its source set.
    write(project, "api/src@jvm/JvmApi.kt", "package fixture\n/** JVM API. */\nfun generated(): GeneratedCommon = GeneratedCommon()\n")
    verifyGeneratedSources()
}

temporary("dokka-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "project.yaml", "plugins", "templates", "example/src", "example/module.yaml")) {
        copy(repo.resolve(name), project.resolve(name))
    }
    // A fresh archive command must infer and run the HTML task without a separate invocation.
    toolchain(project, "do", "dokkaJar", "-m", "example")
    verifyArchive(project, "example")
    // Custom outer build roots (including spaces) must own separate nested caches.
    val customBuild = project.resolve("custom build")
    toolchain(project, "do", "dokkaHtml", "-m", "example", "--build-dir", customBuild.absolutePath)
    val customConfig = customBuild.resolve("tasks/_example_dokkaHtml@dokka/dokka.json").readText()
    check(customBuild.resolve("dokka-model-build/incremental.state").listFiles()!!.any { it.name.startsWith("_example_compileJvm-") })
    check(customBuild.resolve("tasks/_example_dokkaHtml@dokka/html/index.html").isFile)
    check(project.resolve("build/dokka-model-build").canonicalPath !in customConfig)
    val indexes = project.resolve("build").walkTopDown().filter { it.name == "index.html" && it.parentFile.name == "html" }.toList()
    check(indexes.size == 1) { "Expected one documentation site, got $indexes" }
    val html = indexes.single().parentFile.walkTopDown().filter { it.isFile && it.extension == "html" }.joinToString("\n") { it.readText() }
    check("Greeter" in html && "Returns a friendly greeting" in html)
    check("implementationDetail" !in html) { "Default public-only visibility was ignored" }
    project.resolve("example/module.yaml").appendText("\nplugins:\n  dokka:\n    reportUndocumented: true\n    failOnWarning: true\n")
    project.resolve("example/src/Greeter.kt").appendText("\npublic fun undocumentedFunction(): String = \"missing KDoc\"\n")
    toolchain(project, "do", "dokkaJar", "-m", "example", succeeds = false, diagnostic = "Dokka failed with exit code")
}


// Regression: local module types must resolve, and common/actual declarations retain their platforms.
temporary("dokka-kmp-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "plugins")) copy(repo.resolve(name), project.resolve(name))
    val native = when {
        windows -> "mingwX64"
        System.getProperty("os.name").startsWith("Mac") -> "macosArm64"
        else -> "linuxX64"
    }
    val otherNative = if (System.getProperty("os.name").startsWith("Mac")) "iosArm64" else "linuxArm64"
    write(project, "project.yaml", """
        modules: [api, peer, model, plugins/dokka]
        plugins: [//plugins/dokka]
    """.trimIndent() + "\n")
    for (name in listOf("api", "model")) {
        write(project, "$name/module.yaml", """
            product:
              type: kmp/lib
              platforms: [jvm, js, wasmJs, $native, $otherNative]
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
          nonJvm: [js, wasmJs, $native, $otherNative]
        plugins:
          dokka: enabled
    """.trimIndent() + "\n")
    write(project, "model/src/Model.kt", """
        package fixture.html
        /** A model declared in another module. */
        class Node(val value: String)
    """.trimIndent() + "\n")
    write(project, "api/src/Api.kt", """
        package fixture.html
        /** Return the value of this node. */
        fun Node.render(): String = value
        /** Platform identity. */
        expect fun platformName(): String
    """.trimIndent() + "\n")
    write(project, "api/src@nonJvm/Shared.kt", """
        package fixture.html
        /** Shared web and native declaration. */
        fun Node.nonJvmValue(): String = value
    """.trimIndent() + "\n")
    for (platform in listOf("jvm", "js", "wasmJs", native)) {
        write(project, "api/src@$platform/Platform.kt", """
            package fixture.html
            /** Platform identity. */
            actual fun platformName(): String = "$platform"
        """.trimIndent() + "\n")
    }
    // Shared Native sources require commonized platform libraries, but the foreign
    // leaf has no own sources and therefore does not require a foreign SDK/compiler.
    write(project, "model/src@native/NativeModel.kt", """
        package fixture.html
        /** Whether a native environment variable is defined. */
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        fun nativeEnvironmentAvailable(): Boolean = platform.posix.getenv("PATH") != null
    """.trimIndent() + "\n")
    write(project, "api/src@native/NativeApi.kt", """
        package fixture.html
        /** Shared Native API backed by commonized platform libraries. */
        fun hasNativeEnvironment(): Boolean = nativeEnvironmentAvailable()
    """.trimIndent() + "\n")
    copy(project.resolve("api"), project.resolve("peer"))
    toolchain(project, "do", "dokkaHtml", "-m", "api")
    val modelBuild = project.resolve("build/dokka-model-build")
    // State files are rewritten only on cache misses in KTC 0.13.0. Compare both
    // contents and precise mtimes, so an unnecessary rebuild cannot pass unnoticed.
    fun dependencyState(): Map<String, Pair<String, java.nio.file.attribute.FileTime>> =
        modelBuild.resolve("incremental.state").listFiles()!!.filter {
            it.name.startsWith("_model_compile") || it.name.contains("commoniz", ignoreCase = true)
        }.associate { it.name to (it.readText() to Files.getLastModifiedTime(it.toPath())) }
    val initialState = dependencyState()
    check(initialState.keys.any { it.startsWith("_model_compileMetadataCommon-") })
    check(initialState.keys.any { it.startsWith("_model_compileJvm-") })
    check(initialState.keys.any { it.startsWith("_model_compileMetadataNative-") })
    check(initialState.keys.any { it.startsWith("_model_compile" + native.replaceFirstChar { it.uppercaseChar() }) })
    toolchain(project, "do", "dokkaHtml", "-m", "peer")
    check(dependencyState() == initialState) { "A second module recompiled unchanged shared dependencies" }
    check(!project.resolve("build/tasks/_peer_dokkaHtml@dokka/model-build").exists())
    // Start separate outer processes together; distinct Dokka tasks share nested locks.
    val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
    val start = java.util.concurrent.CountDownLatch(1)
    try {
        val results = listOf("api", "peer").map { name -> workers.submit {
            start.await()
            toolchain(project, "do", "dokkaHtml", "-m", name)
        } }
        start.countDown()
        results.forEach { it.get(600, TimeUnit.SECONDS) }
    } finally { workers.shutdownNow() }
    check(dependencyState() == initialState) { "Concurrent documentation recompiled unchanged dependencies" }
    for (name in listOf("api", "peer")) {
        val config = project.resolve("build/tasks/_${name}_dokkaHtml@dokka/dokka.json").readText()
        check(modelBuild.canonicalPath in config)
        check(project.resolve("$name/src").canonicalPath in config)
        val other = if (name == "api") "peer" else "api"
        check(project.resolve("$other/src").canonicalPath !in config) { "Source roots leaked between modules" }
    }
    println("PASS: shared KMP/Native metadata and JVM/Native dependency compilation reused sequentially and concurrently")
    val output = project.resolve("build/tasks/_api_dokkaHtml@dokka")
    val pages = output.resolve("html").walkTopDown().filter { it.isFile && it.extension == "html" }.toList()
    val html = pages.joinToString("\n") { it.readText() }
    check("ERROR CLASS" !in html && "Symbol not found for" !in html)
    check("Node" in html && "Return the value of this node" in html && "nonJvmValue" in html)
    for (name in listOf("commonMain", "jvmMain", "jsMain", "wasmJsMain", "${native}Main", "${otherNative}Main", "nativeMain", "nonJvmMain")) {
        check("api/$name" in html) { "Missing platform/source set $name" }
    }
    val config = output.resolve("dokka.json").readText()
    check("kotlin-stdlib-commonMain" in config && "_model_compileMetadataCommon" in config)
    check("CompiledJvmArtifact" in config && "modeljvm" in config)
    check("dependentSourceSets" in config && "src@nonJvm" in config)

    // The next run must observe dependency signature changes, even with the child compilation cache populated.
    project.resolve("model/src/Model.kt").appendText("\n/** New dependency type. */\nclass Added\n")
    project.resolve("api/src/Api.kt").appendText("\n/** New dependency signature. */\nfun Added.added(): Node = Node(\"new\")\n")
    toolchain(project, "do", "dokkaJar", "-m", "api")
    verifyArchive(project, "api")
    check(dependencyState() != initialState) { "Changed dependency did not invalidate the shared compilation cache" }
    val regenerated = output.resolve("html").walkTopDown().filter { it.isFile && it.extension == "html" }.joinToString("\n") { it.readText() }
    check("ERROR CLASS" !in regenerated && "Symbol not found for" !in regenerated && "Added" in regenerated)
    // Removing a declaration must remove its old page on the next run as well.
    val apiSource = project.resolve("api/src/Api.kt")
    apiSource.writeText(apiSource.readText().replace("fun Added.added(): Node = Node(\"new\")", "fun replacement(): String = \"fresh\""))
    toolchain(project, "do", "dokkaHtml", "-m", "api")
    check(!output.resolve("html").walkTopDown().any { it.name == "added.html" })
    check(output.resolve("html").walkTopDown().any { it.name == "replacement.html" })
}
// Android uses a JVM compilation with android.jar; JVM/Android shared fragments have no KLib task.
// The long module name also exercises KTC's truncated, hashed incremental-state filenames.
temporary("dokka-android-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "plugins")) copy(repo.resolve(name), project.resolve(name))
    val api = "documentation-android-library-with-a-long-name"
    write(project, "project.yaml", "modules: [$api, model, plugins/dokka]\nplugins: [//plugins/dokka]\n")
    for (name in listOf(api, "model")) {
        write(project, "$name/module.yaml", """
            product:
              type: kmp/lib
              platforms: [jvm, android, js]
            aliases:
              - jvmAndAndroid: [jvm, android]
            settings:
              android:
                namespace: fixture.docs
              publishing:
                enabled: true
                group: test.docs
                version: 1.0
        """.trimIndent() + "\n")
    }
    project.resolve("$api/module.yaml").appendText("dependencies:\n  - ../model: exported\nplugins:\n  dokka: enabled\n")
    write(project, "model/src/Model.kt", "package fixture.html\n/** Common model. */\nclass Node(val value: String)\n")
    write(project, "model/src@android/AndroidNode.kt", "package fixture.html\n/** Android dependency. */\nclass AndroidNode\n")
    write(project, "$api/src/Api.kt", """
        package fixture.html
        /** Common model API. */
        fun Node.render(): String = value
        /** Platform identity. */
        expect fun platformName(): String
    """.trimIndent() + "\n")
    write(project, "$api/src@jvmAndAndroid/Shared.kt", """
        package fixture.html
        /** JVM and Android time API. */
        fun Node.at(instant: java.time.Instant): String = value + instant
    """.trimIndent() + "\n")
    for (target in listOf("jvm", "android", "js")) {
        write(project, "$api/src@$target/Platform.kt", """
            package fixture.html
            /** Platform identity. */
            actual fun platformName(): String = "$target"
        """.trimIndent() + "\n")
    }
    write(project, "$api/src@android/AndroidApi.kt", """
        package fixture.html
        /** Android-only API using platform and local dependency types. */
        fun AndroidNode.contextName(context: android.content.Context): String = context.packageName
    """.trimIndent() + "\n")
    fun verifyAndroid() {
        toolchain(project, "do", "dokkaJar", "-m", api)
        verifyArchive(project, api)
        val output = project.resolve("build/tasks/_${api}_dokkaHtml@dokka")
        val html = output.resolve("html").walkTopDown().filter { it.isFile && it.extension == "html" }.joinToString("\n") { it.readText() }
        for (expected in listOf("contextName", "AndroidNode", "Context", "Instant", "jvmAndAndroidMain", "androidMain")) {
            check(expected in html) { "Missing Android/shared API: $expected" }
        }
        check("ERROR CLASS" !in html && "Symbol not found for" !in html)
        val config = output.resolve("dokka.json").readText()
        check("android.jar" in config && "modelandroidrelease" in config)
        check("src@jvmAndAndroid" in config && "src@android" in config)
    }
    verifyAndroid()
    // Reuse the same output directory after removing metadata compilation entirely.
    // Old metadata state must not masquerade as current JVM/Android compilation inputs.
    for (name in listOf(api, "model")) {
        val module = project.resolve("$name/module.yaml")
        module.writeText(module.readText().replace("[jvm, android, js]", "[jvm, android]"))
    }
    verifyAndroid()
}

temporary("dokka-android-only-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "plugins")) copy(repo.resolve(name), project.resolve(name))
    write(project, "project.yaml", "modules: [android-only, plugins/dokka]\nplugins: [//plugins/dokka]\n")
    write(project, "android-only/module.yaml", """
        product:
          type: kmp/lib
          platforms: [android]
        settings:
          android:
            namespace: fixture.androidonly
        plugins:
          dokka: enabled
    """.trimIndent() + "\n")
    write(project, "android-only/src/AndroidApi.kt", """
        package fixture.html
        /** Android-only library API. */
        fun packageName(context: android.content.Context): String = context.packageName
    """.trimIndent() + "\n")
    toolchain(project, "do", "dokkaJar", "-m", "android-only")
    verifyArchive(project, "android-only")
    val config = project.resolve("build/tasks/_android-only_dokkaHtml@dokka/dokka.json").readText()
    check("android.jar" in config && "androidMain" in config)
}
println("Dokka integration passed: shared compilation and Native metadata reuse, concurrent requests, freshness, isolated build paths, JVM/KMP/Android HTML and archives, generated metadata sources and ownership, long task names, shared JVM/Android sources, platform dependencies, task dependencies, failure propagation, aliases and regeneration.")
