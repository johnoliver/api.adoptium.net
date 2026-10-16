package net.adoptium.api

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.adoptium.api.v3.AdoptRepositoryImpl
import net.adoptium.api.v3.dataSources.github.GitHubApi
import net.adoptium.api.v3.dataSources.github.GitHubHtmlClient
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAsset
import net.adoptium.api.v3.mapping.adopt.AdoptBinaryMapper
import net.adoptium.api.v3.mapping.adopt.AdoptReleaseMapperFactory
import net.adoptium.api.v3.models.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryExecutionTest {
    @Test
    fun `cancelling a summary cancels and joins all repository tasks`() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>()
            var active = 0
            var finished = 0
            val github = mockk<GitHubApi>()
            coEvery { github.getRepositorySummary(any(), any()) } coAnswers {
                active++
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    active--
                    finished++
                }
            }
            val repository = AdoptRepositoryImpl(github, AdoptReleaseMapperFactory(AdoptBinaryMapper(mockk()), mockk()))
            val update = launch { repository.getSummary(8) }
            entered.await()
            update.cancelAndJoin()
            assertTrue(finished > 0)
            assertEquals(0, active, "No detached work may survive its requesting update")
        }
    }

    @Test
    fun `a repository failure cancels siblings before returning`() = runBlocking {
        withTimeout(5000) {
            val firstEntered = CompletableDeferred<Unit>()
            var calls = 0
            var active = 0
            // The adoptium deployment enables one repository; the legacy profile
            // also exercises cancellation of an already-running sibling.
            val hasSiblings = Vendor.validVendor(Vendor.adoptopenjdk)
            val github = mockk<GitHubApi>()
            coEvery { github.getRepositorySummary(any(), any()) } coAnswers {
                if (++calls == 1 && hasSiblings) {
                    active++
                    firstEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        active--
                    }
                } else {
                    if (hasSiblings) firstEntered.await()
                    error("Repository unavailable")
                }
            }
            val repository = AdoptRepositoryImpl(github, AdoptReleaseMapperFactory(AdoptBinaryMapper(mockk()), mockk()))
            val error = runCatching { repository.getSummary(8) }.exceptionOrNull()
            assertEquals("Repository unavailable", error?.message)
            assertEquals(0, active)
        }
    }

    private fun assets(): Pair<List<GHAsset>, List<GHAsset>> {
        val binaries = (1..40).map { index ->
            GHAsset("OpenJDK8U-jdk_x64_linux_hotspot_$index.tar.gz", 1, "binary-$index", 1, "2026-10-01T00:00:00Z")
        }
        val checksums = binaries.map { it.copy(name = it.name + ".sha256.txt", downloadUrl = it.downloadUrl + ".sha256.txt") }
        return binaries to binaries + checksums
    }

    @Test
    fun `binary I O is bounded and output order is stable`() = runBlocking {
        withTimeout(5000) {
            val (binaries, allAssets) = assets()
            val firstBatch = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var started = 0
            var active = 0
            var peak = 0
            val html = mockk<GitHubHtmlClient>()
            coEvery { html.getAsset(any()) } coAnswers {
                started++
                active++
                peak = maxOf(peak, active)
                if (started == 16) firstBatch.complete(Unit)
                try {
                    release.await()
                    "checksum filename"
                } finally {
                    active--
                }
            }
            val mapper = AdoptBinaryMapper(html)
            val update = async { mapper.toBinaryList(binaries, allAssets, emptyMap()) }
            firstBatch.await()
            assertEquals(16, started)
            release.complete(Unit)
            val result = update.await()
            assertEquals(binaries.map { it.name }, result.map { it.`package`.name })
            assertEquals(16, peak)
            assertEquals(0, active)
        }
    }

    @Test
    fun `cancelling binary mapping joins active tasks and never starts later batches`() = runBlocking {
        withTimeout(5000) {
            val (binaries, allAssets) = assets()
            val firstBatch = CompletableDeferred<Unit>()
            var started = 0
            var active = 0
            val html = mockk<GitHubHtmlClient>()
            coEvery { html.getAsset(any()) } coAnswers {
                active++
                if (++started == 16) firstBatch.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
            val update = launch { AdoptBinaryMapper(html).toBinaryList(binaries, allAssets, emptyMap()) }
            firstBatch.await()
            update.cancelAndJoin()
            assertEquals(16, started)
            assertEquals(0, active)
        }
    }
}
