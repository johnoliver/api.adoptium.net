package net.adoptium.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.adoptium.api.testDoubles.InMemoryInternalDbStore
import net.adoptium.api.v3.TimeSource
import net.adoptium.api.v3.dataSources.UpdaterHtmlClient
import net.adoptium.api.v3.dataSources.UrlRequest
import net.adoptium.api.v3.dataSources.github.CachedGitHubHtmlClient
import net.adoptium.api.v3.dataSources.github.GitHubHtmlClient
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAsset
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAssets
import net.adoptium.api.v3.dataSources.github.graphql.models.GHRelease
import net.adoptium.api.v3.dataSources.github.graphql.models.PageInfo
import net.adoptium.api.v3.dataSources.models.GitHubId
import net.adoptium.api.v3.dataSources.mongo.InternalDbStore
import net.adoptium.api.v3.mapping.adopt.AdoptBinaryMapper
import net.adoptium.api.v3.mapping.adopt.AdoptReleaseMapperFactory
import net.adoptium.api.v3.models.Vendor
import org.apache.http.HttpVersion
import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicHttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.ZonedDateTime

class UpdaterRecoveryCompatibilityTest {
    private fun response(body: String = "body", status: Int = 200) =
        BasicHttpResponse(HttpVersion.HTTP_1_1, status, "").apply { entity = StringEntity(body) }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "null", "empty", "populated", "github-digest"])
    fun `Windows SBOM metadata supports checksum fallback without overriding a GitHub digest`(format: String) = runBlocking {
        val checksum = "a".repeat(64)
        val digest = "b".repeat(64)
        val name = "OpenJDK24U-sbom_x64_windows_hotspot_24_36.json"
        val sidecar = name.removeSuffix(".json") + "-metadata.json"
        val time = "2025-03-18T12:00:00Z"
        val field = when (format) {
            "missing" -> ""
            "null" -> ",\"sha256\":null"
            "empty" -> ",\"sha256\":\"\""
            else -> ",\"sha256\":\"$checksum\""
        }
        val html = mockk<GitHubHtmlClient>()
        coEvery { html.getUrl(sidecar) } returns """
            {"os":"windows","arch":"x64","variant":"hotspot","binary_type":"sbom",
             "version":{"major":24,"minor":0,"security":0,"build":36,
             "adopt_build_number":0,"version":"24+36","semver":"24.0.0+36"},
             "scmRef":"jdk-24+36","version_data":"jdk24"$field}
        """.trimIndent()
        coEvery { html.getUrl("$name.sha256.txt") } returns "$checksum  $name"
        coEvery { html.getAsset(any()) } coAnswers { html.getUrl(firstArg<GHAsset>().downloadUrl) }
        val assets = listOf(name, sidecar, "$name.sha256.txt").map {
            GHAsset(it, 1, it, 1, time, digest = if (it == name && format == "github-digest") "sha256:$digest" else null)
        }
        val release = GHRelease(GitHubId("release"), "jdk-24+36", false, time, time,
            GHAssets(assets, PageInfo(false, null), assets.size), "",
            "https://github.com/adoptium/temurin24-binaries/releases/tag/jdk-24+36")
        val result = AdoptReleaseMapperFactory(AdoptBinaryMapper(html), html).get(Vendor.getDefault()).toAdoptRelease(release)
        assertTrue(result.succeeded(), result.error)
        assertEquals(if (format == "github-digest") digest else checksum, result.result!!.single().binaries.single().`package`.checksum)
        coVerify(exactly = if (format in setOf("populated", "github-digest")) 0 else 1) { html.getUrl("$name.sha256.txt") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "empty", "blank"])
    fun `unusable URL cache bodies recover immediately despite recent validation`(format: String) = runBlocking {
        val store = InMemoryInternalDbStore()
        val now = TimeSource.now()
        val modified = CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(now.minusYears(1))
        store.putCachedWebpage("recover", modified, now, when (format) { "null" -> null; "empty" -> ""; else -> "  " }).join()
        val http = mockk<UpdaterHtmlClient>()
        coEvery { http.getFullResponse(UrlRequest("recover")) } returns response("recovered")
        val client = CachedGitHubHtmlClient(store, http)
        repeat(2) { assertEquals("recovered", client.getUrl("recover")) }
        coVerify(exactly = 1) { http.getFullResponse(UrlRequest("recover")) }
        coVerify(exactly = 0) { http.getFullResponse(UrlRequest("recover", modified)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null-response", "empty-body", "no-body", "server-error", "not-modified"])
    fun `failed demand responses do not poison the cache and can recover on the next access`(failure: String) = runBlocking {
        val store = InMemoryInternalDbStore()
        val http = mockk<UpdaterHtmlClient>()
        coEvery { http.getFullResponse(UrlRequest("retry")) } returns when (failure) {
            "null-response" -> null
            "empty-body" -> response("")
            "no-body" -> BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "")
            "server-error" -> response("unavailable", 503)
            else -> response(status = 304)
        }
        val client = CachedGitHubHtmlClient(store, http)
        assertNull(client.getUrl("retry"))
        assertNull(store.getCachedWebpage("retry"))
        coEvery { http.getFullResponse(UrlRequest("retry")) } returns response("available")
        assertEquals("available", client.getUrl("retry"))
        assertEquals("available", store.getCachedWebpage("retry")!!.data)
    }

    @ParameterizedTest
    @ValueSource(strings = ["null-response", "empty-body"])
    fun `failed background URL refresh preserves the usable body and checked time`(failure: String) = runBlocking {
        val backing = InMemoryInternalDbStore()
        val now = TimeSource.now()
        val checked = now.minusDays(2)
        val modified = CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(now)
        backing.putCachedWebpage("stale", modified, checked, "usable").join()
        backing.putCachedWebpage("marker", modified, checked, "marker body").join()
        val drained = CompletableDeferred<Unit>()
        val store = object : InternalDbStore by backing {
            override suspend fun updateCheckedTime(url: String, dateTime: ZonedDateTime) {
                backing.updateCheckedTime(url, dateTime)
                if (url == "marker") drained.complete(Unit)
            }
        }
        val http = mockk<UpdaterHtmlClient>()
        coEvery { http.getFullResponse(UrlRequest("stale", modified)) } returns if (failure == "null-response") null else response("")
        coEvery { http.getFullResponse(UrlRequest("marker", modified)) } returns response(status = 304)
        val client = CachedGitHubHtmlClient(store, http)
        assertEquals("usable", client.getUrl("stale"))
        assertEquals("marker body", client.getUrl("marker"))
        withTimeout(5000) { drained.await() }
        assertEquals("usable", backing.getCachedWebpage("stale")!!.data)
        assertEquals(checked, backing.getCachedWebpage("stale")!!.lastChecked)
    }

    @Test
    fun `URL demand cancellation propagates without caching or retrying`() = runBlocking {
        val store = InMemoryInternalDbStore()
        val http = mockk<UpdaterHtmlClient>()
        coEvery { http.getFullResponse(UrlRequest("cancel")) } throws CancellationException("Stopping updater")
        val client = CachedGitHubHtmlClient(store, http)
        assertThrows(CancellationException::class.java) { runBlocking { client.getUrl("cancel") } }
        assertNull(store.getCachedWebpage("cancel"))
        coVerify(exactly = 1) { http.getFullResponse(UrlRequest("cancel")) }
    }
}
