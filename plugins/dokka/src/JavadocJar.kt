package io.heapy.ktc.plugins.dokka

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Packages the complete Dokka HTML site; does not attach it to a Maven publication. */
@TaskAction
public fun generateJavadocJar(
    @Input htmlDirectory: Path,
    moduleName: String,
    @Output outputDirectory: Path,
) {
    require(moduleName.isNotBlank() && moduleName.none { it == '/' || it == '\\' }) {
        "Module name must be a nonempty file name"
    }
    val archive = outputDirectory.resolve("$moduleName-javadoc.jar")
    writeJavadocJar(htmlDirectory, archive)
    println("Dokka Javadoc JAR: $archive")
}

internal fun writeJavadocJar(htmlDirectory: Path, archive: Path) {
    val source = htmlDirectory.toAbsolutePath().normalize()
    val target = archive.toAbsolutePath().normalize()
    require(!target.startsWith(source)) { "Javadoc archive must be outside the HTML directory" }
    require(!Files.isSymbolicLink(source) && Files.isDirectory(source, NOFOLLOW_LINKS)) {
        "Dokka HTML directory does not exist: $source"
    }
    require(Files.isRegularFile(source.resolve("index.html"), NOFOLLOW_LINKS)) {
        "Dokka HTML index is missing: ${source.resolve("index.html")}"
    }
    val files = Files.walk(source).use { paths ->
        paths.filter { path ->
            require(!Files.isSymbolicLink(path)) { "Dokka HTML must not contain symbolic links: $path" }
            Files.isRegularFile(path, NOFOLLOW_LINKS)
        }.toList().sortedBy { source.relativize(it).joinToString("/") }
    }
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".dokka-", ".jar")
    try {
        JarOutputStream(Files.newOutputStream(temporary).buffered()).use { jar ->
            for (file in files) {
                val entry = JarEntry(source.relativize(file).joinToString("/"))
                // Stay above ZIP's minimum-date sentinel to avoid extended, timezone-dependent timestamps.
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0))
                jar.putNextEntry(entry)
                Files.newInputStream(file).use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
