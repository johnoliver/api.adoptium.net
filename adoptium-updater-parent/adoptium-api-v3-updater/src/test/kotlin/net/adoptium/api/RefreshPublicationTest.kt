package net.adoptium.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.adoptium.api.v3.AdoptReposBuilder
import net.adoptium.api.v3.AdoptRepository
import net.adoptium.api.v3.ReleaseResult
import net.adoptium.api.v3.TimeSource
import net.adoptium.api.v3.V3Updater
import net.adoptium.api.v3.dataSources.APIDataStore
import net.adoptium.api.v3.dataSources.VersionSupplier
import net.adoptium.api.v3.dataSources.github.graphql.models.PageInfo
import net.adoptium.api.v3.dataSources.github.graphql.models.summary.GHAssetsSummary
import net.adoptium.api.v3.dataSources.github.graphql.models.summary.GHReleaseSummary
import net.adoptium.api.v3.dataSources.github.graphql.models.summary.GHReleasesSummary
import net.adoptium.api.v3.dataSources.github.graphql.models.summary.GHRepositorySummary
import net.adoptium.api.v3.dataSources.models.AdoptRepos
import net.adoptium.api.v3.dataSources.models.FeatureRelease
import net.adoptium.api.v3.dataSources.models.GitHubId
import net.adoptium.api.v3.dataSources.models.Releases
import net.adoptium.api.v3.dataSources.persitence.ApiPersistence
import net.adoptium.api.v3.dataSources.persitence.mongo.UpdatedInfo
import net.adoptium.api.v3.models.DateTime
import net.adoptium.api.v3.models.GitHubDownloadStatsDbEntry
import net.adoptium.api.v3.models.Release
import net.adoptium.api.v3.models.ReleaseType
import net.adoptium.api.v3.models.Vendor
import net.adoptium.api.v3.models.VersionData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RefreshPublicationTest {
    private val now = TimeSource.now().withNano(0)

    @ParameterizedTest
    @CsvSource(
        "summary,replaced", "summary,empty", "summary,excluded",
        "publication,replaced", "publication,empty", "publication,excluded",
        "checksum,replaced", "checksum,empty", "checksum,excluded",
        "cancellation,replaced", "interruption,replaced"
    )
    fun `pending historical retries survive rejected combined snapshots`(failure: String, outcome: String) = runBlocking {
        val old = release("historical.old-split", now.minusDays(40))
        val other = release("historical.other-split", now.minusDays(40))
        val latest = release("latest", now.minusDays(20))
        val laterFeature = release("later-feature", now.minusDays(20), major = 11)
        val before = repo(listOf(old, other, latest, laterFeature))
        val repository = mockk<AdoptRepository>()
        coEvery { repository.getSummary(any()) } answers {
            val releases = if (firstArg<Int>() == 8) listOf(old, latest) else listOf(laterFeature)
            GHRepositorySummary(GHReleasesSummary(releases.map(::summary), PageInfo(false, null)))
        }
        coEvery { repository.getReleaseById(any()) } answers {
            ReleaseResult(listOf(if (firstArg<GitHubId>() == GitHubId(latest.id)) latest else laterFeature))
        }
        coEvery { repository.getReleaseById(GitHubId(old.id)) } returns ReleaseResult(error = "Retry")
        val builder = AdoptReposBuilder(repository, mockk())
        assertEquals(before, builder.incrementalUpdate(setOf(old.release_name), before) { null })

        val result = when (outcome) {
            "empty" -> ReleaseResult(emptyList())
            "excluded" -> ReleaseResult(error = "Excluded", retryable = false)
            else -> ReleaseResult(listOf(old))
        }
        coEvery { repository.getReleaseById(GitHubId(old.id)) } returns result
        val store = mockk<APIDataStore>()
        val database = mockk<ApiPersistence>(relaxed = true)
        val updater = V3Updater(builder, mockk(), store, database, mockk(), mockk(relaxed = true), mockk(), mockk())
        val oldChecksum = V3Updater.calculateChecksum(before)
        coEvery { database.getUpdatedAt() } returns UpdatedInfo(now, oldChecksum, before.hashCode())
        val error = when (failure) {
            "cancellation" -> CancellationException("Publication cancelled")
            "interruption" -> InterruptedException("Publication interrupted")
            else -> IllegalStateException("Temporary failure")
        }
        var snapshot = before
        when (failure) {
            "summary" -> coEvery { repository.getSummary(11) } throws error
            "checksum" -> {
                // Another process changed the database without refreshing the historical EA.
                snapshot = before.addRelease(11, release("concurrent", now.minusDays(30), major = 11))
                coEvery { database.getUpdatedAt() } returns UpdatedInfo(now, V3Updater.calculateChecksum(snapshot), snapshot.hashCode())
                every { store.loadDataFromDb(true) } returns snapshot
            }
            else -> coEvery { database.updateAllRepos(any(), any()) } throws error
        }
        if (failure in setOf("cancellation", "interruption")) {
            assertThrows(error.javaClass) { runBlocking { updater.incrementalUpdate(before) } }
        } else {
            assertEquals(if (failure == "checksum") snapshot else null, updater.incrementalUpdate(before))
        }
        // Neither updater cycle drains explicit requests; only builder state remembers this ID.
        coVerify(exactly = 2) { repository.getReleaseById(GitHubId(old.id)) }
        coEvery { repository.getSummary(11) } returns GHRepositorySummary(
            GHReleasesSummary(listOf(summary(laterFeature)), PageInfo(false, null)))
        coEvery { database.getUpdatedAt() } returns UpdatedInfo(now, V3Updater.calculateChecksum(snapshot), snapshot.hashCode())
        coEvery { database.updateAllRepos(any(), any()) } returns Unit
        val expected = repo((if (outcome == "replaced") listOf(old) else emptyList()) + latest + laterFeature)
        assertEquals(expected, updater.incrementalUpdate(snapshot))
        coVerify(exactly = 3) { repository.getReleaseById(GitHubId(old.id)) }
        assertEquals(null, updater.incrementalUpdate(expected))
        coVerify(exactly = 3) { repository.getReleaseById(GitHubId(old.id)) }
    }

    @ParameterizedTest
    @CsvSource(
        "validation,false", "validation,true", "publication,false", "publication,true",
        "cancellation,false", "interruption,false", "success,false", "success,true"
    )
    fun `full build retry cleanup waits for accepted publication`(outcome: String, filtered: Boolean) = runBlocking {
        val old = release("historical", now.minusDays(40))
        val latest = release("latest", now.minusDays(20))
        val before = repo(listOf(old, latest))
        val repository = mockk<AdoptRepository>()
        coEvery { repository.getSummary(8) } returns GHRepositorySummary(
            GHReleasesSummary(listOf(old, latest).map(::summary), PageInfo(false, null)))
        coEvery { repository.getReleaseById(GitHubId(latest.id)) } returns ReleaseResult(listOf(latest))
        coEvery { repository.getReleaseById(GitHubId(old.id)) } returns ReleaseResult(error = "Retry")
        val versions = mockk<VersionSupplier>()
        every { versions.getAllVersions() } returns arrayOf(8)
        val builder = AdoptReposBuilder(repository, versions)
        assertEquals(before, builder.incrementalUpdate(setOf(old.release_name), before) { null })
        coEvery { repository.getRelease(8, any()) } returns
            (if (filtered) repo(listOf(latest)) else before).repos.getValue(8)
        val database = mockk<ApiPersistence>(relaxed = true)
        val store = mockk<APIDataStore>()
        every { store.loadDataFromDb(forceUpdate = true, logEntries = false) } returns before
        coEvery { database.getLatestGithubStatsForFeatureVersion(any()) } returns
            if (outcome == "validation") GitHubDownloadStatsDbEntry(now, 1, emptyMap(), 8) else null
        val error = when (outcome) {
            "cancellation" -> CancellationException("Publication cancelled")
            "interruption" -> InterruptedException("Publication interrupted")
            else -> IllegalStateException("Publication failed")
        }
        if (outcome !in setOf("success", "validation")) {
            coEvery { database.updateAllRepos(any(), any()) } throws error
        }
        val updater = V3Updater(builder, mockk(), store, database, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        if (outcome in setOf("cancellation", "interruption")) {
            assertThrows(error.javaClass) { runBlocking { updater.fullUpdate(before, filtered) } }
        } else {
            assertEquals(if (outcome == "success") before else null, updater.fullUpdate(before, filtered))
        }
        coVerify(exactly = if (outcome == "validation") 0 else 1) { database.updateAllRepos(any(), any()) }
        coEvery { repository.getReleaseById(GitHubId(old.id)) } returns ReleaseResult(listOf(old))
        // No explicit request is drained: only builder state can trigger the old EA.
        assertEquals(before, builder.incrementalUpdate(emptySet(), before) { null })
        val expectedFetches = if (outcome == "success" && !filtered) 1 else 2
        coVerify(exactly = expectedFetches) { repository.getReleaseById(GitHubId(old.id)) }
        builder.incrementalUpdate(emptySet(), before) { null }
        coVerify(exactly = expectedFetches) { repository.getReleaseById(GitHubId(old.id)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["replaced", "empty", "excluded"])
    fun `no change incremental success accepts pending outcome cleanup`(outcome: String) = runBlocking {
        val old = release("historical", now.minusDays(40))
        val latest = release("latest", now.minusDays(20))
        val repository = mockk<AdoptRepository>()
        coEvery { repository.getSummary(8) } returns GHRepositorySummary(
            GHReleasesSummary(listOf(old, latest).map(::summary), PageInfo(false, null)))
        coEvery { repository.getReleaseById(GitHubId(latest.id)) } returns ReleaseResult(listOf(latest))
        coEvery { repository.getReleaseById(GitHubId(old.id)) } returns ReleaseResult(error = "Retry")
        val builder = AdoptReposBuilder(repository, mockk())
        val before = repo(if (outcome == "replaced") listOf(old, latest) else listOf(latest))
        assertEquals(before, builder.incrementalUpdate(setOf(old.release_name), before) { null })
        coEvery { repository.getReleaseById(GitHubId(old.id)) } returns when (outcome) {
            "empty" -> ReleaseResult(emptyList())
            "excluded" -> ReleaseResult(error = "Excluded", retryable = false)
            else -> ReleaseResult(listOf(old))
        }
        // The snapshot is unchanged, so publication must not be attempted.
        val database = mockk<ApiPersistence>(relaxed = true)
        val updater = V3Updater(builder, mockk(), mockk(), database, mockk(), mockk(), mockk(), mockk())
        repeat(2) { assertEquals(null, updater.incrementalUpdate(before)) }
        coVerify(exactly = 2) { repository.getReleaseById(GitHubId(old.id)) }
        coVerify(exactly = 0) { database.updateAllRepos(any(), any()) }
    }

    private fun summary(release: Release) = GHReleaseSummary(GitHubId(release.id),
        DateTimeFormatter.ISO_INSTANT.format(release.timestamp.dateTime), DateTimeFormatter.ISO_INSTANT.format(release.updated_at.dateTime),
        release.release_name, GHAssetsSummary(0), release.release_link, true, release.vendor)

    private fun repo(releases: List<Release>) = AdoptRepos(releases.groupBy { it.version_data.major }
        .map { (major, nodes) -> FeatureRelease(major, Releases(nodes)) })

    private fun release(id: String, published: ZonedDateTime, major: Int = 8) = Release(
        id, ReleaseType.ea, "https://github.com/adoptium/temurin$major-binaries/releases/tag/$id", id,
        DateTime(published), DateTime(published), emptyArray(), 0, Vendor.eclipse,
        VersionData(major, 0, 512, "beta", 0, 5, "", ""))
}
