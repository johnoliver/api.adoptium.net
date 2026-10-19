package net.adoptium.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.adoptium.api.testDoubles.InMemoryApiPersistence
import net.adoptium.api.v3.AdoptReposBuilder
import net.adoptium.api.v3.AdoptRepositoryImpl
import net.adoptium.api.v3.ReleaseFilterType
import net.adoptium.api.v3.ReleaseIncludeFilter
import net.adoptium.api.v3.TimeSource
import net.adoptium.api.v3.V3Updater
import net.adoptium.api.v3.config.APIConfig
import net.adoptium.api.v3.dataSources.APIDataStoreImpl
import net.adoptium.api.v3.dataSources.ReleaseVersionResolver
import net.adoptium.api.v3.dataSources.UpdatableVersionSupplier
import net.adoptium.api.v3.dataSources.UpdaterJsonMapper
import net.adoptium.api.v3.dataSources.github.GitHubApi
import net.adoptium.api.v3.dataSources.github.GitHubHtmlClient
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAsset
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAssets
import net.adoptium.api.v3.dataSources.github.graphql.models.GHRelease
import net.adoptium.api.v3.dataSources.github.graphql.models.GHReleases
import net.adoptium.api.v3.dataSources.github.graphql.models.GHRepository
import net.adoptium.api.v3.dataSources.github.graphql.models.PageInfo
import net.adoptium.api.v3.dataSources.models.AdoptRepos
import net.adoptium.api.v3.dataSources.models.FeatureRelease
import net.adoptium.api.v3.dataSources.models.GitHubId
import net.adoptium.api.v3.dataSources.models.Releases
import net.adoptium.api.v3.mapping.adopt.AdoptBinaryMapper
import net.adoptium.api.v3.mapping.adopt.AdoptReleaseMapperFactory
import net.adoptium.api.v3.models.Release
import net.adoptium.api.v3.models.Vendor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class EaFailureRetentionTest {
    private val time = DateTimeFormatter.ISO_INSTANT.format(TimeSource.now().minusDays(2))
    private val repositoryName = if (Vendor.getDefault() == Vendor.adoptopenjdk) "openjdk8-binaries" else "temurin8-binaries"
    private val owner = if (Vendor.getDefault() == Vendor.adoptopenjdk) "AdoptOpenJDK" else "adoptium"
    private val first = "OpenJDK8U-jre_x64_linux_hotspot-1.tar.gz"
    private val second = "OpenJDK8U-jre_x64_linux_hotspot-2.tar.gz"
    private val html = mockk<GitHubHtmlClient>()
    private val factory = AdoptReleaseMapperFactory(AdoptBinaryMapper(html), html)
    private val mapper = factory.get(Vendor.getDefault())

    private fun release(id: String = "ea", ea: Boolean = true) = GHRelease(
        GitHubId(id), "jdk8u242-b02", ea, time, time,
        GHAssets(listOf(first, "$first.json", second, "$second.json").map {
            GHAsset(it, 1, it, 10, time)
        }, PageInfo(false, null), 4), "", "https://github.com/$owner/$repositoryName/releases/tag/$id"
    )

    private fun metadata(build: Int) = """
        {"os":"linux","arch":"x64","variant":"hotspot","binary_type":"jre",
         "version":{"major":8,"minor":0,"security":242,"build":$build,
         "adopt_build_number":1,"version":"8.0.242+$build","semver":"8.0.242+$build.1"},
         "scmRef":"test","version_data":"jdk8u","sha256":"checksum"}
    """.trimIndent()

    private fun healthyMetadata() {
        coEvery { html.getUrl(any()) } answers { metadata(if (firstArg<String>().contains("-2")) 2 else 1) }
    }

    private fun repos(releases: List<Release>) = AdoptRepos(listOf(FeatureRelease(8, Releases(releases))))
    private fun nodes(repo: AdoptRepos) = repo.getFeatureRelease(8)!!.releases.nodes
    private fun versions() = mockk<UpdatableVersionSupplier>().also {
        every { it.getAllVersions() } returns arrayOf(8)
        every { it.getLtsVersions() } returns arrayOf(8)
        every { it.getTipVersion() } returns 8
        coEvery { it.updateVersions() } returns Unit
    }

    private fun github(releases: () -> List<GHRelease>) = mockk<GitHubApi>().also { github ->
        coEvery { github.getRepository(any(), any(), any()) } answers {
            GHRepository(GHReleases(if (secondArg<String>() == repositoryName) releases() else emptyList(), PageInfo(false, null)))
        }
        coEvery { github.getRepositorySummary(any(), any()) } answers {
            GHSummaryTestDataGenerator.generateGHRepositorySummary(GHRepository(GHReleases(
                if (secondArg<String>() == repositoryName) releases() else emptyList(), PageInfo(false, null))))
        }
        coEvery { github.getReleaseById(any()) } answers { releases().firstOrNull { it.id == firstArg<GitHubId>() } }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid", "exception", "missing-sidecar", "pagination"])
    fun `full and incremental updates retain every failed EA split while accepting other releases and recovering`(failure: String) = runBlocking {
        healthyMetadata()
        val ea = release()
        val old = mapper.toAdoptRelease(ea).result!!
        assertEquals(2, old.size)
        // An unrelated old upstream ID must not survive just because an EA failed.
        val removed = mapper.toAdoptRelease(release("ea-other")).result!!
        val before = repos(old + removed)
        var current = ea
        when (failure) {
            "missing-sidecar" -> current = ea.copy(releaseAssets = ea.releaseAssets.copy(
                assets = ea.releaseAssets.assets.filterNot { it.name == "$second.json" }))
            "pagination" -> current = ea.copy(releaseAssets = ea.releaseAssets.copy(pageInfo = PageInfo(true, "next")))
            else -> coEvery { html.getUrl("$second.json") } answers {
                when (failure) { "missing" -> null; "invalid" -> "invalid JSON"; else -> error("Temporary metadata failure") }
            }
        }
        val ga = release("ga", false).copy(releaseAssets = ea.releaseAssets.copy(assets = ea.releaseAssets.assets.take(2)))
        val healthy = mapper.toAdoptRelease(ga).result!!
        val versions = versions()
        val builder = AdoptReposBuilder(AdoptRepositoryImpl(github { listOf(current, ga) }, factory), versions)
        val expected = (old + healthy).associateBy { it.id }
        assertEquals(expected, nodes(builder.build(ReleaseIncludeFilter.INCLUDE_ALL, before)))
        assertEquals(expected, nodes(builder.incrementalUpdate(setOf(ea.name), before) { null }))
        assertEquals(healthy.associateBy { it.id }, nodes(builder.build(ReleaseIncludeFilter.INCLUDE_ALL)))

        val database = spyk(InMemoryApiPersistence(before, mockk()))
        val updater = V3Updater(builder, mockk(), APIDataStoreImpl(database), database, mockk(relaxed = true),
            ReleaseVersionResolver(versions), mockk(relaxed = true), versions)
        val updateLegacy = APIConfig.UPDATE_ADOPTOPENJDK
        try {
            APIConfig.UPDATE_ADOPTOPENJDK = true
            updater.runUpdate(before, AtomicBoolean(true), mockk())
        } finally {
            APIConfig.UPDATE_ADOPTOPENJDK = updateLegacy
        }
        assertEquals(expected, nodes(database.repos))
        coVerify(atLeast = 1) { database.updateAllRepos(any(), any()) }

        healthyMetadata()
        current = ea.copy(releaseAssets = ea.releaseAssets.copy(assets = ea.releaseAssets.assets.take(2)))
        val recovered = mapper.toAdoptRelease(current).result!!
        val after = builder.build(ReleaseIncludeFilter.INCLUDE_ALL, repos(old + healthy))
        assertEquals((recovered + healthy).associateBy { it.id }, nodes(after))
    }

    @ParameterizedTest
    @CsvSource("false,true", "false,false", "true,true", "true,false")
    fun `upstream classification uses the release name instead of the prerelease flag`(ga: Boolean, prerelease: Boolean) {
        val upstream = release().copy(
            name = if (ga) "OpenJDK 11.0.6 GA Release" else "OpenJDK 11.0.6 Early Access Release",
            isPrerelease = prerelease,
            url = "https://github.com/AdoptOpenJDK/openjdk11-upstream-binaries/releases/tag/test"
        )
        assertEquals(!ga, upstream.isEarlyAccess())
    }

    @ParameterizedTest
    @CsvSource("true,exception", "false,exception", "true,parse", "false,parse")
    fun `upstream mapping failures retain every EA split but abort GA rebuilds`(ea: Boolean, failure: String): Unit = runBlocking {
        assumeTrue(Vendor.validVendor(Vendor.openjdk))
        healthyMetadata()
        val previous = mapper.toAdoptRelease(release()).result!!
        assertEquals(2, previous.size)
        val upstream = release().copy(
            name = if (failure == "parse") {
                if (ea) "Unparseable Early Access Release" else "Unparseable GA Release"
            } else {
                if (ea) "OpenJDK 11.0.6 Early Access Release" else "OpenJDK 11.0.6 GA Release"
            },
            isPrerelease = !ea,
            publishedAt = if (failure == "exception") "invalid date" else time,
            releaseAssets = GHAssets(emptyList(), PageInfo(false, null)),
            url = "https://github.com/AdoptOpenJDK/openjdk11-upstream-binaries/releases/tag/test"
        )
        val api = mockk<GitHubApi>()
        coEvery { api.getRepository(any(), any(), any()) } answers {
            GHRepository(GHReleases(
                if (secondArg<String>() == "openjdk11-upstream-binaries") listOf(upstream) else emptyList(),
                PageInfo(false, null)
            ))
        }
        val repository = AdoptRepositoryImpl(api, factory)
        if (ea) {
            assertEquals(previous.associateBy { it.id }, repository.getRelease(11, ReleaseIncludeFilter.INCLUDE_ALL, previous).releases.nodes)
            assertTrue(repository.getRelease(11, ReleaseIncludeFilter.INCLUDE_ALL).releases.nodes.isEmpty())
        } else {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.getRelease(11, ReleaseIncludeFilter.INCLUDE_ALL, previous) }
            }
        }
    }

    @Test
    fun `version parse failure excludes only EAs and valid date named metadata still maps`() = runBlocking {
        healthyMetadata()
        val dated = release().copy(name = "jdk8u-2026-10-01-12-00-beta")
        assertTrue(mapper.toAdoptRelease(dated).succeeded())
        val noMetadata = dated.copy(releaseAssets = dated.releaseAssets.copy(assets = dated.releaseAssets.assets.filterNot { it.name.endsWith(".json") }))
        val excluded = mapper.toAdoptRelease(noMetadata)
        assertFalse(excluded.succeeded())
        assertFalse(excluded.retryable)
        val ga = mapper.toAdoptRelease(noMetadata.copy(isPrerelease = false))
        assertFalse(ga.succeeded())
        assertTrue(ga.retryable)

        val old = mapper.toAdoptRelease(dated).result!!
        val builder = AdoptReposBuilder(AdoptRepositoryImpl(github { listOf(noMetadata) }, factory), versions())
        assertTrue(nodes(builder.build(ReleaseIncludeFilter.INCLUDE_ALL, repos(old))).isEmpty())
        assertTrue(nodes(builder.incrementalUpdate(setOf(dated.name), repos(old)) { null }).isEmpty())
    }

    @Test
    fun `metadata cancellation propagates rather than publishing an EA fallback`(): Unit = runBlocking {
        healthyMetadata()
        val ea = release()
        val before = repos(mapper.toAdoptRelease(ea).result!!)
        coEvery { html.getUrl(any()) } throws CancellationException("Stopping updater")
        val builder = AdoptReposBuilder(AdoptRepositoryImpl(github { listOf(ea) }, factory), versions())
        assertThrows(CancellationException::class.java) { runBlocking { builder.build(ReleaseIncludeFilter.INCLUDE_ALL, before) } }
        assertThrows(CancellationException::class.java) { runBlocking { builder.incrementalUpdate(setOf(ea.name), before) { null } } }
    }

    @ParameterizedTest
    @CsvSource(
        "8,jdk8u-2023-02-04-05-54-beta", "8,jdk8u-2022-03-04-12-42-beta", "8,jdk8u-2022-02-17-13-04-beta",
        "11,jdk11u-2023-02-03-15-18-beta", "11,jdk11u-2023-02-03-02-11-beta", "11,jdk11u-2022-03-01-15-33-beta",
        "17,jdk17u-2023-02-03-06-44-beta", "17,jdk17u-2022-05-20-12-57-beta",
        "18,jdk18-2022-02-26-05-39-beta", "18,jdk18-2022-02-15-15-05-beta",
        "20,jdk20u-2023-04-05-10-53-beta", "21,jdk21u-2023-12-06-16-18-beta",
        "25,jdk25u-2026-01-27-11-20-beta", "25,jdk25u-2025-11-24-16-43-beta"
    )
    fun `known damaged EAs are excluded before fetching metadata and do not affect other repositories`(major: Int, name: String) = runBlocking {
        healthyMetadata()
        var current = release().copy(name = name, url = "https://github.com/adoptium/temurin$major-binaries/releases/tag/$name")
        val api = mockk<GitHubApi>()
        coEvery { api.getReleaseById(current.id) } answers { current }
        val repository = AdoptRepositoryImpl(api, factory)
        val outcome = repository.getReleaseById(current.id)!!
        assertFalse(outcome.succeeded())
        assertFalse(outcome.retryable)
        coVerify(exactly = 0) { html.getUrl(any()) }
        current = current.copy(url = "https://github.com/AdoptOpenJDK/openjdk$major-binaries/releases/tag/$name")
        assertTrue(repository.getReleaseById(current.id)!!.succeeded())
    }

    @Test
    fun `known exclusions are pruned from summaries without fetching individual releases`() = runBlocking {
        healthyMetadata()
        val upstream = release()
        val before = repos(mapper.toAdoptRelease(upstream).result!!)
        val excluded = upstream.copy(name = "jdk17u-2022-05-27-19-32-beta")
        val api = github { listOf(excluded) }
        val builder = AdoptReposBuilder(AdoptRepositoryImpl(api, factory), versions())
        assertTrue(nodes(builder.incrementalUpdate(emptySet(), before) { null }).isEmpty())
        coVerify(exactly = 0) { api.getReleaseById(any()) }
    }

    @Test
    fun `EA classification does not add a field to release JSON`() {
        val json = UpdaterJsonMapper.mapper.readTree(UpdaterJsonMapper.mapper.writeValueAsString(release()))
        assertFalse(json.has("earlyAccess"))
        assertFalse(json.has("isEarlyAccess"))
    }

    @Test
    fun `full publication removes known exclusions even outside the EA age window`() = runBlocking {
        healthyMetadata()
        val upstream = release().copy(name = "jdk8u-2023-02-04-05-54-beta",
            url = "https://github.com/adoptium/temurin8-binaries/releases/tag/jdk8u-2023-02-04-05-54-beta")
        val old = repos(mapper.toAdoptRelease(upstream).result!!)
        val filter = ReleaseIncludeFilter(TimeSource.now(), ReleaseFilterType.RELEASES_ONLY)
        val retained = V3Updater.copyOldReleasesIntoNewRepo(old, repos(emptyList()), filter)
        assertTrue(retained.allReleases.nodeList.isEmpty())
    }
}
