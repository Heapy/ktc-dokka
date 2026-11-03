package io.heapy.ktc.plugins.dokka

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourceSetsTest {
    @Test
    fun matchesToolchainStateFilenamesIncludingTruncatedKeys() {
        val build = Path.of("build")
        assertEquals("_example_compileJvm-584209977b", compilationStatePath(build, "example", "compileJvm").fileName.toString())
        assertEquals(
            "_kotmark-ext-image-attributes_compileMetadataCommo-1ce16b2150",
            compilationStatePath(build, "kotmark-ext-image-attributes", "compileMetadataCommon").fileName.toString(),
        )
    }

    @Test
    fun sameTruncatedPrefixStillHasDistinctStatePaths() {
        val common = compilationStatePath(Path.of("build"), "kotmark-ext-image-attributes", "compileMetadataCommon")
        val longer = compilationStatePath(Path.of("build"), "kotmark-ext-image-attributes", "compileMetadataCommonOther")
        assertEquals(common.fileName.toString().substringBeforeLast('-'), longer.fileName.toString().substringBeforeLast('-'))
        assertNotEquals(common, longer)
    }

    @Test
    fun recoversJvmAndroidAliasAbsentFromMetadataCompilations() {
        val structure = Json.parseToJsonElement("""
            {
              "formatVersion": "0.3.3",
              "variants": [
                {"name":"jvmApiElements", "sourceSet":["jvmAndAndroidMain","commonMain"]},
                {"name":"androidApiElements", "sourceSet":["jvmAndAndroidMain","commonMain"]},
                {"name":"jsApiElements", "sourceSet":["webMain","commonMain"]}
              ],
              "sourceSets": [{"name":"commonMain","dependsOn":[]}]
            }
        """).jsonObject
        val nodes = sourceSetHierarchy(structure, listOf("jvm", "android", "js")).associateBy { it.name }
        assertEquals(listOf("jvm", "android"), nodes.getValue("jvmAndAndroidMain").targets)
        assertEquals(listOf("commonMain"), nodes.getValue("jvmAndAndroidMain").dependsOn)
        assertEquals(listOf("commonMain"), nodes.getValue("webMain").dependsOn)
        assertTrue(metadataCompilationRequired(nodes.getValue("commonMain").targets))
        assertFalse(metadataCompilationRequired(nodes.getValue("jvmAndAndroidMain").targets))
    }

    @Test
    fun usesJvmAnalysisAndLeafCompilationsForJvmAndroidPair() {
        assertEquals("jvm", analysisPlatform(listOf("android")))
        assertEquals("jvm", analysisPlatform(listOf("jvm", "android")))
        assertEquals("common", analysisPlatform(listOf("jvm", "android", "js")))
        assertFalse(metadataCompilationRequired(listOf("jvm", "android")))
        assertFalse(metadataCompilationRequired(listOf("wasmJs", "wasmWasi")))
        assertFalse(metadataCompilationRequired(listOf("android")))
    }
}
