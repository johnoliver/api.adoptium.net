package net.adoptium.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import net.adoptium.api.testDoubles.InMemoryApiPersistence
import net.adoptium.api.v3.AdoptReposBuilder
import net.adoptium.api.v3.V3Updater
import net.adoptium.api.v3.config.APIConfig
import net.adoptium.api.v3.dataSources.APIDataStoreImpl
import net.adoptium.api.v3.dataSources.UpdatableVersionSupplier
import net.adoptium.api.v3.dataSources.models.AdoptCdxaRepos
import net.adoptium.api.v3.dataSources.models.AdoptRepos
import net.adoptium.api.v3.dataSources.models.FeatureRelease
import net.adoptium.api.v3.dataSources.models.Releases
import net.adoptium.api.v3.models.Release
import net.adoptium.api.v3.models.ReleaseType
import net.adoptium.api.v3.models.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.atomic.AtomicBoolean

class PublicationInvariantTest {
    private fun release(id: String, type: ReleaseType, version: Int = 8, vendor: Vendor = Vendor.getDefault()): Release {
        val template = BaseTest.adoptRepos.getFeatureRelease(version)!!.releases.nodeList.first()
        return Release(id, type, template.release_link, template.release_name, template.timestamp,
            template.updated_at, emptyArray(), 50, vendor, template.version_data)
    }

    private fun repos(vararg releases: Release) = AdoptRepos(releases.groupBy { it.version_data.major }
        .map { (version, entries) -> FeatureRelease(version, Releases(entries)) })

    private fun updater(builder: AdoptReposBuilder, database: InMemoryApiPersistence): V3Updater {
        val versions = mockk<UpdatableVersionSupplier>()
        coEvery { versions.updateVersions() } returns Unit
        coEvery { builder.incrementalUpdate(any(), any(), any(), any()) } answers { secondArg() }
        return V3Updater(builder, mockk(), APIDataStoreImpl(database), database,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), versions)
    }

    @Test
    fun `full replacement preserves excluded-vendor data from persistence rather than startup state`() = runBlocking {
        val preserved = release("latest-excluded", ReleaseType.ea, vendor = Vendor.adoptopenjdk)
        val rebuilt = release("rebuilt", ReleaseType.ga, version = 11, vendor = Vendor.eclipse)
        val latest = repos(preserved)
        val database = InMemoryApiPersistence(latest, AdoptCdxaRepos(emptyList()))
        database.updateAllRepos(latest, V3Updater.calculateChecksum(latest))
        val builder = mockk<AdoptReposBuilder>()
        var builds = 0
        coEvery { builder.build(any()) } answers {
            if (++builds == 1) repos(rebuilt) else error("Only the first full build is under test")
        }
        val previousPolicy = APIConfig.UPDATE_ADOPTOPENJDK
        try {
            APIConfig.UPDATE_ADOPTOPENJDK = false
            updater(builder, database).runUpdate(repos(), AtomicBoolean(true), mockk())
        } finally {
            APIConfig.UPDATE_ADOPTOPENJDK = previousPolicy
        }
        assertEquals(setOf(preserved.id, rebuilt.id), database.repos.allReleases.nodes.keys)
        assertEquals(50L, database.repos.allReleases.nodes.getValue(preserved.id).download_count)
    }

    @Test
    fun `full publication reloads rather than overwriting a generation changed during its build`() = runBlocking {
        val ga = release("baseline", ReleaseType.ga)
        val added = release("incremental", ReleaseType.ea)
        val before = repos(ga)
        val concurrent = repos(ga, added)
        val database = spyk(InMemoryApiPersistence(before, AdoptCdxaRepos(emptyList())))
        val builder = mockk<AdoptReposBuilder>()
        var builds = 0
        coEvery { builder.build(any()) } coAnswers {
            if (++builds != 1) error("Only the first full build is under test")
            database.updateAllRepos(concurrent, V3Updater.calculateChecksum(concurrent))
            before
        }

        val after = updater(builder, database).runUpdate(before, AtomicBoolean(true), mockk())

        assertEquals(concurrent, database.repos)
        assertEquals(concurrent, after)
        coVerify(exactly = 1) { database.updateAllRepos(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(value = ReleaseType::class, names = ["ga", "ea"])
    fun `guard checks a completely absent version and exempts only omitted EAs`(type: ReleaseType) = runBlocking {
        val before = repos(release("previous", type))
        val database = spyk(InMemoryApiPersistence(before, AdoptCdxaRepos(emptyList())))
        val builder = mockk<AdoptReposBuilder>()
        coEvery { builder.build(any()) } returns repos()
        val previousPolicy = APIConfig.UPDATE_ADOPTOPENJDK
        val after = try {
            APIConfig.UPDATE_ADOPTOPENJDK = true
            updater(builder, database).runUpdate(before, AtomicBoolean(true), mockk())
        } finally {
            APIConfig.UPDATE_ADOPTOPENJDK = previousPolicy
        }

        val expected = if (type == ReleaseType.ga) before else repos()
        assertEquals(expected, after)
        assertEquals(expected, database.repos)
        coVerify(exactly = if (type == ReleaseType.ga) 0 else 2) { database.updateAllRepos(any(), any()) }
    }
}
