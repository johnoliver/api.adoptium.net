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
import net.adoptium.api.v3.models.GitHubDownloadStatsDbEntry
import net.adoptium.api.v3.models.Vendor
import net.adoptium.api.v3.stats.GitHubDownloadStatsCalculator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    @ParameterizedTest
    @ValueSource(strings = ["excluded", "retryable"])
    fun `full publication removes dynamically excluded old splits but retains retryable failures and permits recovery`(failure: String): Unit = runBlocking {
        healthyMetadata()
        val oldTime = DateTimeFormatter.ISO_INSTANT.format(TimeSource.now().minusDays(APIConfig.UPDATE_DAY_CUTOFF.toLong() + 2))
        val original = release().copy(
            name = "jdk8u-2026-10-01-12-00-beta", publishedAt = oldTime, updatedAt = oldTime,
            url = "https://github.com/adoptium/temurin8-binaries/releases/tag/ea"
        )
        val eclipseMapper = factory.get(Vendor.eclipse)
        val old = eclipseMapper.toAdoptRelease(original).result!!
        assertEquals(2, old.size)
        val unrelated = original.copy(id = GitHubId("ea-other"), url = original.url + "-other")
        val otherSplits = eclipseMapper.toAdoptRelease(unrelated).result!!
        val before = repos(old + otherSplits)
        var current = original.copy(updatedAt = time)
        if (failure == "excluded") {
            current = current.copy(releaseAssets = current.releaseAssets.copy(
                assets = current.releaseAssets.assets.filterNot { it.name.endsWith(".json") }
            ))
        } else {
            coEvery { html.getUrl("$second.json") } returns null
        }
        val api = mockk<GitHubApi>()
        coEvery { api.getRepository(any(), any(), any()) } answers {
            val include = thirdArg<(String, Boolean) -> Boolean>()
            GHRepository(GHReleases(
                if (secondArg<String>() == "temurin8-binaries") listOf(current, unrelated).filter {
                    include(it.updatedAt, it.isPrerelease)
                } else emptyList(), PageInfo(false, null)
            ))
        }
        val versions = versions()
        val builder = spyk(AdoptReposBuilder(AdoptRepositoryImpl(api, factory), versions))
        // Do not let the incremental pass hide a broken full-publication merge.
        coEvery { builder.incrementalUpdate(any(), any(), any(), any()) } answers { secondArg<AdoptRepos>() }
        val database = spyk(InMemoryApiPersistence(before, mockk()))
        val updater = V3Updater(builder, mockk(), APIDataStoreImpl(database), database, mockk(relaxed = true),
            ReleaseVersionResolver(versions), mockk(relaxed = true), versions)
        val updateLegacy = APIConfig.UPDATE_ADOPTOPENJDK
        try {
            APIConfig.UPDATE_ADOPTOPENJDK = false
            val filter = ReleaseIncludeFilter(TimeSource.now(), ReleaseFilterType.ALL)
            assertFalse(filter.filter(Vendor.eclipse, oldTime, true))
            assertTrue(filter.filter(Vendor.eclipse, current.updatedAt, true))
            val excludedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<GitHubId>()
            val rebuilt = builder.build(filter, before) { excludedIds.add(it) }
            assertEquals(if (failure == "excluded") setOf(original.id) else emptySet<GitHubId>(), excludedIds)
            val expected = if (failure == "excluded") otherSplits.associateBy { it.id } else nodes(before)
            assertEquals(expected, nodes(V3Updater.copyOldReleasesIntoNewRepo(before, rebuilt, filter, excludedIds)))
            val published = updater.runUpdate(before, AtomicBoolean(true), mockk())
            assertEquals(expected, nodes(published))
            assertEquals(expected, nodes(database.repos))
            coVerify(atLeast = 1) { database.updateAllRepos(any(), any()) }

            healthyMetadata()
            current = original.copy(updatedAt = time)
            val recovered = eclipseMapper.toAdoptRelease(current).result!!
            val afterRecovery = updater.runUpdate(published, AtomicBoolean(true), mockk())
            assertEquals((recovered + otherSplits).associateBy { it.id }, nodes(afterRecovery))
            assertEquals(nodes(afterRecovery), nodes(database.repos))
        } finally {
            APIConfig.UPDATE_ADOPTOPENJDK = updateLegacy
        }
    }

    @ParameterizedTest
    @CsvSource(
        "static,full,false", "static,full,true", "dynamic,full,false", "dynamic,full,true",
        "static,incremental,false", "static,incremental,true", "dynamic,incremental,false", "dynamic,incremental,true",
        "static,empty,false", "dynamic,empty,false"
    )
    fun `intentional exclusions adjust saved statistics without permitting unrelated losses`(policy: String, phase: String, loss: Boolean): Unit = runBlocking {
        healthyMetadata()
        val original = release().copy(name = if (policy == "static") "jdk-11.0.13'+8" else "jdk8u-2026-10-01-12-00-beta")
        val excludedSplits = mapper.toAdoptRelease(original).result!!
        assertEquals(2, excludedSplits.size)
        val healthyOriginal = release("ea-other", false).let {
            it.copy(releaseAssets = it.releaseAssets.copy(assets = it.releaseAssets.assets.take(2)))
        }
        val healthySplits = if (phase == "empty") emptyList() else mapper.toAdoptRelease(healthyOriginal).result!!
        val before = repos(excludedSplits + healthySplits)
        val damaged = original.copy(releaseAssets = original.releaseAssets.copy(
            assets = original.releaseAssets.assets.filterNot { it.name.endsWith(".json") }
        ))
        val healthy = healthyOriginal.copy(releaseAssets = healthyOriginal.releaseAssets.copy(
            assets = healthyOriginal.releaseAssets.assets.map { it.copy(downloadCount = if (loss) 9 else 11) }
        ))
        val expected = if (phase == "empty") emptyList() else mapper.toAdoptRelease(healthy).result!!
        val api = github { listOf(damaged) + if (phase == "empty") emptyList() else listOf(healthy) }
        val versions = versions()
        val realBuilder = AdoptReposBuilder(AdoptRepositoryImpl(api, factory), versions)
        val builder = spyk(realBuilder)
        var blockFull = phase == "incremental"
        coEvery { builder.build(any(), any(), any()) } coAnswers {
            if (blockFull) error("Full rebuild unavailable during incremental regression")
            else realBuilder.build(firstArg(), secondArg(), thirdArg())
        }
        if (phase != "incremental") {
            // Neither incremental pruning nor a later pass may conceal a rejected full publication.
            coEvery { builder.incrementalUpdate(any(), any(), any(), any()) } answers { secondArg<AdoptRepos>() }
        }
        val database = InMemoryApiPersistence(before, mockk())
        database.updateAllRepos(before, V3Updater.calculateChecksum(before))
        val initialStats = GitHubDownloadStatsCalculator.getStats(before).single()
        database.addGithubDownloadStatsEntries(listOf(GitHubDownloadStatsDbEntry(
            TimeSource.now().minusDays(1), initialStats.downloads, initialStats.jvmImplDownloads, 8
        )))
        assertTrue(initialStats.downloads > 0)
        fun updater() = V3Updater(builder, mockk(), APIDataStoreImpl(database), database, mockk(relaxed = true),
            ReleaseVersionResolver(versions), mockk(relaxed = true), versions)
        val updateLegacy = APIConfig.UPDATE_ADOPTOPENJDK
        try {
            APIConfig.UPDATE_ADOPTOPENJDK = true
            val published = updater().runUpdate(before, AtomicBoolean(true), mockk())
            if (loss) {
                assertEquals(nodes(before), nodes(published))
                assertEquals(nodes(before), nodes(database.repos))
                assertEquals(initialStats.downloads, database.getLatestGithubStatsForFeatureVersion(8)!!.downloads)
                assertEquals(1, database.getStatsForFeatureVersion(8).size)
            } else {
                assertEquals(expected.associateBy { it.id }, published.allReleases.nodes)
                assertEquals(published.allReleases.nodes, database.repos.allReleases.nodes)
                assertEquals(expected.sumOf { it.download_count }, database.getLatestGithubStatsForFeatureVersion(8)!!.downloads)
                assertEquals(initialStats.downloads, database.getStatsForFeatureVersion(8).first().downloads)
                // A fresh updater must use the durable adjusted baseline, not process-local credits.
                blockFull = false
                val again = updater().runUpdate(published, AtomicBoolean(true), mockk())
                assertEquals(published.allReleases.nodes, again.allReleases.nodes)
                assertEquals(again.allReleases.nodes, database.repos.allReleases.nodes)
            }
        } finally {
            APIConfig.UPDATE_ADOPTOPENJDK = updateLegacy
        }
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

    @ParameterizedTest
    @CsvSource("full,stats", "full,releases", "incremental,stats", "incremental,releases")
    fun `failed exclusion publication is retryable after restart without allowing unrelated losses`(phase: String, failure: String): Unit = runBlocking {
        healthyMetadata()
        val original = release().copy(name = "jdk8u-2026-10-01-12-00-beta")
        val healthy = release("healthy", false).let {
            it.copy(releaseAssets = it.releaseAssets.copy(assets = it.releaseAssets.assets.take(2)))
        }
        fun withDownloads(count: Int) = healthy.copy(releaseAssets = healthy.releaseAssets.copy(
            assets = healthy.releaseAssets.assets.map { it.copy(downloadCount = count.toLong()) }
        ))
        val before = repos(mapper.toAdoptRelease(original).result!! + mapper.toAdoptRelease(healthy).result!!)
        val damaged = original.copy(releaseAssets = original.releaseAssets.copy(
            assets = original.releaseAssets.assets.filterNot { it.name.endsWith(".json") }
        ))
        var current = damaged
        var currentHealthy = withDownloads(11)
        val versions = versions()
        val realBuilder = AdoptReposBuilder(AdoptRepositoryImpl(github { listOf(current, currentHealthy) }, factory), versions)
        val builder = spyk(realBuilder)
        var blockFull = phase == "incremental"
        coEvery { builder.build(any(), any(), any()) } coAnswers {
            if (blockFull) error("Full rebuild unavailable") else realBuilder.build(firstArg(), secondArg(), thirdArg())
        }
        if (phase == "full") {
            coEvery { builder.incrementalUpdate(any(), any(), any(), any()) } answers { secondArg<AdoptRepos>() }
        }
        val database = object : InMemoryApiPersistence(before, mockk()) {
            var failStats = false
            var failReleases = false
            override suspend fun addGithubDownloadStatsEntries(stats: List<GitHubDownloadStatsDbEntry>) {
                check(!failStats) { "Injected statistics failure" }
                super.addGithubDownloadStatsEntries(stats)
            }
            override suspend fun updateAllRepos(repos: AdoptRepos, checksum: String) {
                check(!failReleases) { "Injected release failure" }
                super.updateAllRepos(repos, checksum)
            }
        }
        val initial = GitHubDownloadStatsCalculator.getStats(before).single()
        database.addGithubDownloadStatsEntries(listOf(GitHubDownloadStatsDbEntry(TimeSource.now().minusDays(1), initial.downloads, null, 8)))
        fun updater() = V3Updater(builder, mockk(), APIDataStoreImpl(database), database, mockk(relaxed = true),
            ReleaseVersionResolver(versions), mockk(relaxed = true), versions)
        val updateLegacy = APIConfig.UPDATE_ADOPTOPENJDK
        try {
            APIConfig.UPDATE_ADOPTOPENJDK = true
            database.failStats = failure == "stats"
            database.failReleases = failure == "releases"
            updater().runUpdate(before, AtomicBoolean(true), mockk())
            assertEquals(nodes(before), nodes(database.repos))
            assertEquals(V3Updater.calculateChecksum(before), database.getUpdatedAt().checksum)
            assertEquals(if (failure == "stats") initial.downloads else 11L, database.getLatestGithubStatsForFeatureVersion(8)!!.downloads)

            database.failStats = false
            database.failReleases = false
            blockFull = false
            current = original // No exclusions: the early adjustment must not waive a healthy loss.
            currentHealthy = withDownloads(9)
            val rejected = updater().runUpdate(database.repos, AtomicBoolean(true), mockk())
            assertEquals(nodes(before), nodes(rejected))
            assertEquals(nodes(before), nodes(database.repos))

            current = damaged
            currentHealthy = withDownloads(11)
            val recovered = updater().runUpdate(database.repos, AtomicBoolean(true), mockk())
            assertEquals(mapper.toAdoptRelease(currentHealthy).result!!.associateBy { it.id }, nodes(recovered))
            assertEquals(nodes(recovered), nodes(database.repos))
            assertEquals(11L, database.getLatestGithubStatsForFeatureVersion(8)!!.downloads)
            assertEquals(nodes(recovered), nodes(updater().runUpdate(recovered, AtomicBoolean(true), mockk())))
        } finally {
            APIConfig.UPDATE_ADOPTOPENJDK = updateLegacy
        }
    }

    @Test
    fun `full rebuild waits for failed publication before capturing generation and can recover`(): Unit = runBlocking {
        healthyMetadata()
        val original = release().copy(name = "jdk8u-2026-10-01-12-00-beta")
        val healthy = release("healthy", false).let {
            it.copy(releaseAssets = it.releaseAssets.copy(assets = it.releaseAssets.assets.take(2)))
        }
        suspend fun healthyRepo(count: Long) = repos(mapper.toAdoptRelease(healthy.copy(
            releaseAssets = healthy.releaseAssets.copy(assets = healthy.releaseAssets.assets.map { it.copy(downloadCount = count) })
        )).result!!)
        val before = repos(mapper.toAdoptRelease(original).result!! + mapper.toAdoptRelease(healthy).result!!)
        val partial = healthyRepo(15)
        val unsafe = healthyRepo(11)
        val recovered = healthyRepo(16)
        val writing = CountDownLatch(1)
        val finishWriting = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val generationRead = CountDownLatch(1)
        val observeReads = AtomicBoolean(false)
        val database = object : InMemoryApiPersistence(before, mockk()) {
            var failOnce = true
            override suspend fun getUpdatedAt() = super.getUpdatedAt().also {
                if (observeReads.get()) generationRead.countDown()
            }
            override suspend fun updateAllRepos(repos: AdoptRepos, checksum: String) {
                if (failOnce) {
                    failOnce = false
                    super.updateAllRepos(before, "incomplete:concurrent-failure")
                    writing.countDown()
                    check(finishWriting.await(10, TimeUnit.SECONDS)) { "Timed out releasing publication" }
                    // The write fails after changing data but without changing its incomplete marker.
                    super.updateAllRepos(partial, "incomplete:concurrent-failure")
                    error("Injected partial release write failure")
                }
                super.updateAllRepos(repos, checksum)
            }
        }
        database.addGithubDownloadStatsEntries(listOf(GitHubDownloadStatsDbEntry(TimeSource.now().minusDays(1), 30, null, 8)))
        val versions = versions()
        val builder = mockk<AdoptReposBuilder>()
        val builds = AtomicInteger()
        coEvery { builder.build(any(), any(), any()) } answers {
            thirdArg<(GitHubId) -> Unit>()(original.id)
            if (builds.getAndIncrement() == 0) partial else unsafe
        }
        val updater = V3Updater(builder, mockk(), APIDataStoreImpl(database), database, mockk(relaxed = true),
            ReleaseVersionResolver(versions), mockk(relaxed = true), versions)
        // Isolate one full pass so subsequent incremental/full passes cannot conceal stale publication.
        val fullUpdate = V3Updater::class.java.getDeclaredMethod("fullUpdate", AdoptRepos::class.java, Boolean::class.javaPrimitiveType)
            .also { it.isAccessible = true }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit<AdoptRepos?> { fullUpdate.invoke(updater, before, false) as AdoptRepos? }
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            observeReads.set(true)
            val reader = executor.submit<AdoptRepos?> {
                readerStarted.countDown()
                fullUpdate.invoke(updater, before, false) as AdoptRepos?
            }
            assertTrue(readerStarted.await(10, TimeUnit.SECONDS))
            val readDuringPublication = generationRead.await(1, TimeUnit.SECONDS)
            finishWriting.countDown()
            assertNull(writer.get(10, TimeUnit.SECONDS))
            val latest = reader.get(10, TimeUnit.SECONDS)!!
            assertFalse(readDuringPublication, "Do not capture an in-flight incomplete generation")
            assertEquals(nodes(partial), nodes(latest))
            assertEquals(nodes(partial), nodes(database.repos))
            assertEquals(1, builds.get(), "A stale input must reload before rebuilding or spending exclusions")
            assertEquals("incomplete:concurrent-failure", database.getUpdatedAt().checksum)
            assertEquals(15L, database.getLatestGithubStatsForFeatureVersion(8)!!.downloads)

            // A stable incomplete generation is recoverable, not a permanent publication veto.
            coEvery { builder.build(any(), any(), any()) } returns recovered
            assertEquals(nodes(recovered), nodes(fullUpdate.invoke(updater, latest, false) as AdoptRepos))
            assertEquals(nodes(recovered), nodes(database.repos))
            assertEquals(V3Updater.calculateChecksum(recovered), database.getUpdatedAt().checksum)
        } finally {
            finishWriting.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["before-build", "during-build"])
    fun `stale full rebuild cannot reuse an already published exclusion credit`(timing: String): Unit = runBlocking {
        healthyMetadata()
        val original = release().copy(name = "jdk8u-2026-10-01-12-00-beta")
        val healthy = release("healthy", false).let {
            it.copy(releaseAssets = it.releaseAssets.copy(assets = it.releaseAssets.assets.take(2)))
        }
        fun withDownloads(count: Int) = healthy.copy(releaseAssets = healthy.releaseAssets.copy(
            assets = healthy.releaseAssets.assets.map { it.copy(downloadCount = count.toLong()) }
        ))
        val before = repos(mapper.toAdoptRelease(original).result!! + mapper.toAdoptRelease(healthy).result!!)
        val newer = repos(mapper.toAdoptRelease(withDownloads(15)).result!!)
        val damaged = original.copy(releaseAssets = original.releaseAssets.copy(
            assets = original.releaseAssets.assets.filterNot { it.name.endsWith(".json") }
        ))
        val database = InMemoryApiPersistence(before, mockk())
        database.addGithubDownloadStatsEntries(listOf(GitHubDownloadStatsDbEntry(TimeSource.now().minusDays(1), 30, null, 8)))
        suspend fun publishNewer() {
            database.updateAllRepos(newer, V3Updater.calculateChecksum(newer))
            database.addGithubDownloadStatsEntries(listOf(GitHubDownloadStatsDbEntry(TimeSource.now(), 15, null, 8)))
        }
        val versions = versions()
        val realBuilder = AdoptReposBuilder(AdoptRepositoryImpl(github { listOf(damaged, withDownloads(11)) }, factory), versions)
        val builder = spyk(realBuilder)
        var interleave = timing == "during-build"
        coEvery { builder.build(any(), any(), any()) } coAnswers {
            val candidate = realBuilder.build(firstArg(), secondArg(), thirdArg())
            if (interleave) { interleave = false; publishNewer() }
            candidate
        }
        coEvery { builder.incrementalUpdate(any(), any(), any(), any()) } answers { secondArg<AdoptRepos>() }
        val updater = V3Updater(builder, mockk(), APIDataStoreImpl(database), database, mockk(relaxed = true),
            ReleaseVersionResolver(versions), mockk(relaxed = true), versions)
        val updateLegacy = APIConfig.UPDATE_ADOPTOPENJDK
        try {
            APIConfig.UPDATE_ADOPTOPENJDK = true
            if (timing == "before-build") publishNewer()
            val published = updater.runUpdate(before, AtomicBoolean(true), mockk())
            assertEquals(nodes(newer), nodes(published))
            assertEquals(nodes(newer), nodes(database.repos))
            assertEquals(V3Updater.calculateChecksum(newer), database.getUpdatedAt().checksum)
            assertEquals(15L, database.getLatestGithubStatsForFeatureVersion(8)!!.downloads)
            assertEquals(2, database.getStatsForFeatureVersion(8).size)
        } finally {
            APIConfig.UPDATE_ADOPTOPENJDK = updateLegacy
        }
    }
}
