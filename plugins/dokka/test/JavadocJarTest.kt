package io.heapy.ktc.plugins.dokka

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.TimeZone
import java.util.jar.JarFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JavadocJarTest {
    @Test
    fun containsTheEntireSiteWithRelativeSortedEntries() = temporary { root ->
        val html = root.resolve("html").createDirectories()
        html.resolve("index.html").writeText("<a href='api/type.html'>API</a>")
        html.resolve("api").createDirectories().resolve("type.html").writeText("Documented type")
        html.resolve("styles").createDirectories().resolve("main.css").writeText("body {}")
        val archive = root.resolve("docs.jar")
        writeJavadocJar(html, archive)
        JarFile(archive.toFile()).use { jar ->
            assertEquals(listOf("api/type.html", "index.html", "styles/main.css"), jar.entries().asSequence().map { it.name }.toList())
            for (entry in jar.entries()) {
                assertContentEquals(html.resolve(entry.name).readBytes(), jar.getInputStream(entry).use { it.readBytes() })
            }
        }
    }

    @Test
    fun archiveIsIndependentOfMtimesCreationOrderAndTimezone() = temporary { root ->
        val first = root.resolve("first").createDirectories()
        val second = root.resolve("second").createDirectories()
        for (name in listOf("index.html", "a.html")) first.resolve(name).writeText(name)
        for (name in listOf("a.html", "index.html")) {
            second.resolve(name).writeText(name)
            Files.setLastModifiedTime(second.resolve(name), FileTime.fromMillis(1_000))
        }
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            writeJavadocJar(first, root.resolve("first.jar"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            writeJavadocJar(second, root.resolve("second.jar"))
        } finally {
            TimeZone.setDefault(previous)
        }
        assertContentEquals(root.resolve("first.jar").readBytes(), root.resolve("second.jar").readBytes())
    }

    @Test
    fun rebuildingRemovesDeletedPages() = temporary { root ->
        val html = root.resolve("html").createDirectories()
        html.resolve("index.html").writeText("API")
        val stale = html.resolve("removed.html").also { it.writeText("Removed type") }
        val archive = root.resolve("docs.jar")
        writeJavadocJar(html, archive)
        Files.delete(stale)
        writeJavadocJar(html, archive)
        JarFile(archive.toFile()).use { jar ->
            assertEquals(listOf("index.html"), jar.entries().asSequence().map { it.name }.toList())
        }
    }

    @Test
    fun missingIndexDoesNotCreateAnArchive() = temporary { root ->
        val html = root.resolve("html").createDirectories()
        html.resolve("stray.html").writeText("Not a complete site")
        val archive = root.resolve("docs.jar")
        assertFailsWith<IllegalArgumentException> { writeJavadocJar(html, archive) }
        assertFalse(Files.exists(archive))
    }

    @Test
    fun rejectsAnArchiveInsideItsOwnInput() = temporary { root ->
        root.resolve("index.html").writeText("API")
        assertFailsWith<IllegalArgumentException> { writeJavadocJar(root, root.resolve("docs.jar")) }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("dokka-jar-test-")
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
