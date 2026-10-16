package net.adoptium.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.adoptium.api.v3.AdoptReposBuilder
import net.adoptium.api.v3.V3Updater
import net.adoptium.api.v3.dataSources.APIDataStore
import net.adoptium.api.v3.dataSources.models.AdoptCdxaRepos
import net.adoptium.api.v3.dataSources.models.AdoptRepos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class UpdaterExecutionTest {
    private fun updater(builder: AdoptReposBuilder = mockk(), store: APIDataStore = mockk()) =
        V3Updater(builder, mockk(), store, mockk(), mockk(), mockk(), mockk(), mockk())

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `daily and incremental jobs share the latest accepted snapshots`(instant: Boolean) {
        val updater = spyk(updater())
        val worker = mockk<ScheduledExecutorService>()
        val tasks = mutableListOf<Runnable>()
        val timing = mutableListOf<Triple<Long, Long, TimeUnit>>()
        every { worker.scheduleWithFixedDelay(capture(tasks), any(), any(), any()) } answers {
            timing.add(Triple(secondArg(), thirdArg(), arg(3)))
            mockk<ScheduledFuture<Any>>()
        }
        val initial = mockk<AdoptRepos>()
        val full = mockk<AdoptRepos>()
        val incremental = mockk<AdoptRepos>()
        val nextFull = mockk<AdoptRepos>()
        val initialCdxa = mockk<AdoptCdxaRepos>()
        val fullCdxa = mockk<AdoptCdxaRepos>()
        val incrementalCdxa = mockk<AdoptCdxaRepos>()
        every { updater.runUpdate(initial) } returns full
        every { updater.runUpdate(incremental) } returns nextFull
        every { updater.runCdxaUpdate(initialCdxa) } returns fullCdxa
        every { updater.runCdxaUpdate(incrementalCdxa) } returns incrementalCdxa
        coEvery { updater.incrementalUpdate(full) } returns incremental
        coEvery { updater.incrementalUpdate(nextFull) } returns null
        coEvery { updater.incrementalCdxaUpdate(fullCdxa) } returns incrementalCdxa
        coEvery { updater.incrementalCdxaUpdate(incrementalCdxa) } returns null

        updater.scheduleUpdates(initial, initialCdxa, instant, worker)
        assertEquals(listOf(Triple(if (instant) 0L else 1L, 1L, TimeUnit.DAYS)), timing)
        tasks[0].run()
        assertEquals(Triple(1L, 6L, TimeUnit.MINUTES), timing[1])
        tasks[1].run()
        tasks[0].run()
        // Null means no accepted change; the next cycle must retain the full snapshot.
        tasks[1].run()
        tasks[1].run()
        verify(exactly = 1) { updater.runUpdate(incremental) }
        coVerify(exactly = 2) { updater.incrementalUpdate(nextFull) }
        verify(exactly = 1) { updater.runCdxaUpdate(incrementalCdxa) }
        assertEquals(2, tasks.size, "Repeated daily runs must not register more jobs")
    }

    @Test
    fun `requests arriving during an update belong to the next cycle`() = runBlocking {
        val builder = mockk<AdoptReposBuilder>()
        val store = mockk<APIDataStore>()
        val updater = updater(builder, store)
        val repo = AdoptReposTestDataGenerator.generate()
        every { store.loadDataFromDb(true) } returns repo
        val name = repo.allReleases.nodeList.first().release_name
        val requests = mutableListOf<Set<String>>()
        coEvery { builder.incrementalUpdateAndPublish(capture(requests), repo, any(), any()) } answers {
            if (requests.size == 1) updater.addToUpdate(name)
            repo
        }

        assertTrue(updater.addToUpdate(name).isNotEmpty())
        updater.incrementalUpdate(repo)
        updater.incrementalUpdate(repo)
        updater.incrementalUpdate(repo)
        assertEquals(listOf(setOf(name), setOf(name), emptySet<String>()), requests)
    }

    @ParameterizedTest
    @ValueSource(strings = ["failure", "cancellation", "interruption"])
    fun `failed cycles retain explicit refresh requests`(failure: String) = runBlocking {
        val builder = mockk<AdoptReposBuilder>()
        val store = mockk<APIDataStore>()
        val updater = updater(builder, store)
        val repo = AdoptReposTestDataGenerator.generate()
        every { store.loadDataFromDb(true) } returns repo
        val name = repo.allReleases.nodeList.first().release_name
        val error = when (failure) {
            "cancellation" -> CancellationException("Stopped")
            "interruption" -> InterruptedException("Stopped")
            else -> IllegalStateException("Unavailable")
        }
        coEvery { builder.incrementalUpdateAndPublish(setOf(name), repo, any(), any()) } throws error
        updater.addToUpdate(name)
        if (failure != "failure") {
            assertSame(error, assertThrows(error.javaClass) { runBlocking { updater.incrementalUpdate(repo) } })
        } else {
            updater.incrementalUpdate(repo)
        }
        coEvery { builder.incrementalUpdateAndPublish(setOf(name), repo, any(), any()) } returns repo
        updater.incrementalUpdate(repo)
        coVerify(exactly = 2) { builder.incrementalUpdateAndPublish(setOf(name), repo, any(), any()) }
    }

    @Test
    fun `shutdown cancels pending schedules and startup cannot register duplicate jobs`() {
        val store = mockk<APIDataStore>()
        coEvery { store.isConnectedToDb() } returns true
        every { store.loadDataFromDb(true) } returns AdoptRepos(emptyList())
        every { store.loadCdxaDataFromDb(true) } returns AdoptCdxaRepos(emptyList())
        val updater = updater(store = store)
        try {
            updater.run(false)
            assertThrows(IllegalStateException::class.java) { updater.run(false) }
        } finally {
            updater.stop()
            updater.stop()
        }
    }
}
