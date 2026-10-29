package net.adoptium.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import net.adoptium.api.v3.dataSources.github.GitHubHtmlClient
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAsset
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAssets
import net.adoptium.api.v3.dataSources.github.graphql.models.GHRelease
import net.adoptium.api.v3.dataSources.github.graphql.models.PageInfo
import net.adoptium.api.v3.dataSources.models.GitHubId
import net.adoptium.api.v3.mapping.adopt.AdoptBinaryMapper
import net.adoptium.api.v3.mapping.adopt.AdoptReleaseMapperFactory
import net.adoptium.api.v3.models.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MetadataChecksumTest {
    private val name = "OpenJDK24U-sbom_x64_windows_hotspot_24_36.json"
    private val sidecar = name.removeSuffix(".json") + "-metadata.json"
    private val checksum = "a".repeat(64)
    private val time = "2025-03-18T12:00:00Z"
    private fun asset(name: String) = GHAsset(name, 1, name, 1, time)

    private fun metadata(field: String) = """
        {"os":"windows","arch":"x64","variant":"hotspot","binary_type":"sbom",
         "version":{"major":24,"minor":0,"security":0,"build":36,
         "adopt_build_number":0,"version":"24+36","semver":"24.0.0+36"},
         "scmRef":"jdk-24+36","version_data":"jdk24"$field}
    """.trimIndent()

    @ParameterizedTest
    @ValueSource(strings = ["missing", "null", "empty", "populated"])
    fun `historical Windows SBOM metadata uses checksum fallback only when needed`(format: String) = runBlocking {
        val field = when (format) {
            "missing" -> ""
            "null" -> ",\"sha256\":null"
            "empty" -> ",\"sha256\":\"\""
            else -> ",\"sha256\":\"$checksum\""
        }
        val client = mockk<GitHubHtmlClient>()
        coEvery { client.getUrl(sidecar) } returns metadata(field)
        coEvery { client.getUrl("$name.sha256.txt") } returns "$checksum  $name"
        val assets = listOf(name, sidecar, "$name.sha256.txt").map(::asset)
        val release = GHRelease(GitHubId("release"), "jdk-24+36", false, time, time,
            GHAssets(assets, PageInfo(false, null), assets.size), "",
            "https://github.com/adoptium/temurin24-binaries/releases/tag/jdk-24+36")
        val result = AdoptReleaseMapperFactory(AdoptBinaryMapper(client), client).get(Vendor.getDefault()).toAdoptRelease(release)
        assertTrue(result.succeeded(), result.error)
        assertEquals(checksum, result.result!!.single().binaries.single().`package`.checksum)
        coVerify(exactly = if (format == "populated") 0 else 1) { client.getUrl("$name.sha256.txt") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "null"])
    fun `absent checksum sources retain the existing nullable package checksum policy`(format: String) = runBlocking {
        val client = mockk<GitHubHtmlClient>()
        coEvery { client.getUrl(sidecar) } returns metadata(if (format == "null") ",\"sha256\":null" else "")
        val assets = listOf(name, sidecar).map(::asset)
        val release = GHRelease(GitHubId("release"), "jdk-24+36", false, time, time,
            GHAssets(assets, PageInfo(false, null), assets.size), "",
            "https://github.com/adoptium/temurin24-binaries/releases/tag/jdk-24+36")
        val result = AdoptReleaseMapperFactory(AdoptBinaryMapper(client), client).get(Vendor.getDefault()).toAdoptRelease(release)
        assertTrue(result.succeeded(), result.error)
        assertNull(result.result!!.single().binaries.single().`package`.checksum)
        coVerify(exactly = 1) { client.getUrl(any()) }
    }
}
