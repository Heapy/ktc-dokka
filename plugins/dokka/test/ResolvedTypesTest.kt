package io.heapy.ktc.plugins.dokka

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ResolvedTypesTest {
    @Test
    fun acceptsPackageDirectoriesEndingInHtml() = temporary { site ->
        site.resolve("index.html").writeText("API")
        site.resolve("fixture.html").createDirectories().resolve("index.html").writeText("Package API")
        verifyResolvedTypes(site)
    }

    @Test
    fun stillRejectsBrokenTypesInsideThosePackages() = temporary { site ->
        site.resolve("fixture.html").createDirectories().resolve("index.html").writeText("ERROR CLASS")
        val error = assertFailsWith<IllegalStateException> { verifyResolvedTypes(site) }
        assertContains(error.message.orEmpty(), "fixture.html")
    }

    @OptIn(ExperimentalPathApi::class)
    private fun temporary(block: (Path) -> Unit) {
        val site = Files.createTempDirectory("dokka-types-test-")
        try {
            block(site)
        } finally {
            site.deleteRecursively()
        }
    }
}
