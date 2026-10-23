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
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdaterExecutionTest {
    private fun updater(builder: AdoptReposBuilder = mockk(), store: APIDataStore = mockk()) =
        V3Updater(builder, mockk(), store, mockk(), mockk(), mockk(), mockk(), mockk())

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `daily and incremental jobs share the latest accepted snapshots`(instant: Boolean) {
        val updater = spyk(updater())
        val worker = mockk<ScheduledExecutorService>()
        every { worker.isShutdown } returns false
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

    @ParameterizedTest
    @CsvSource(
        "daily-repo,cancellation", "daily-repo,interruption", "daily-cdxa,cancellation", "daily-cdxa,interruption",
        "incremental-repo,cancellation", "incremental-repo,interruption", "incremental-cdxa,cancellation", "incremental-cdxa,interruption"
    )
    fun `periodic jobs run again after cycle local cancellation`(stage: String, failure: String) {
        val updater = spyk(updater())
        val repo = AdoptRepos(emptyList())
        val cdxa = AdoptCdxaRepos(emptyList())
        val attempts = AtomicInteger()
        val dailyDone = CountDownLatch(1)
        val incrementalDone = CountDownLatch(1)
        fun visit(current: String) {
            if (current == stage && attempts.incrementAndGet() == 1) {
                if (failure == "interruption") {
                    Thread.currentThread().interrupt()
                    throw InterruptedException("Cycle interrupted")
                }
                throw CancellationException("Cycle cancelled")
            }
        }
        every { updater.runUpdate(repo) } answers { visit("daily-repo"); repo }
        every { updater.runCdxaUpdate(cdxa) } answers {
            visit("daily-cdxa")
            dailyDone.countDown()
            cdxa
        }
        coEvery { updater.incrementalUpdate(repo) } coAnswers { visit("incremental-repo"); null }
        coEvery { updater.incrementalCdxaUpdate(cdxa) } coAnswers {
            visit("incremental-cdxa")
            incrementalDone.countDown()
            null
        }
        val futures = Collections.synchronizedList(mutableListOf<ScheduledFuture<*>>())
        val worker = object : ScheduledThreadPoolExecutor(1) {
            override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                super.scheduleWithFixedDelay(command, 0, 1, TimeUnit.MILLISECONDS).also { futures.add(it) }
        }
        try {
            updater.scheduleUpdates(repo, cdxa, true, worker)
            assertTrue(dailyDone.await(10, TimeUnit.SECONDS), "Daily job must recover")
            assertTrue(incrementalDone.await(10, TimeUnit.SECONDS), "Incremental job must be registered and recover")
            assertTrue(attempts.get() >= 2, "The cancelled stage must run on a later cycle")
            synchronized(futures) {
                assertEquals(2, futures.size)
                assertTrue(futures.none { it.isDone }, "Cancellation must not terminate either periodic future")
            }
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @CsvSource("daily,cancellation", "daily,interruption", "incremental,cancellation", "incremental,interruption")
    fun `scheduler boundaries preserve shutdown cancellation`(job: String, failure: String) {
        val updater = spyk(updater())
        val repo = AdoptRepos(emptyList())
        val cdxa = AdoptCdxaRepos(emptyList())
        val worker = mockk<ScheduledExecutorService>()
        val tasks = mutableListOf<Runnable>()
        every { worker.isShutdown } returns false
        every { worker.scheduleWithFixedDelay(capture(tasks), any(), any(), any()) } returns mockk<ScheduledFuture<Any>>()
        every { updater.runUpdate(repo) } returns repo
        every { updater.runCdxaUpdate(cdxa) } returns cdxa
        updater.scheduleUpdates(repo, cdxa, true, worker)
        if (job == "incremental") tasks[0].run()
        val error = if (failure == "cancellation") CancellationException("Shutdown") else InterruptedException("Shutdown")
        fun stopCycle(): Nothing {
            every { worker.isShutdown } returns true
            throw error
        }
        if (job == "daily") every { updater.runUpdate(repo) } answers { stopCycle() }
        else coEvery { updater.incrementalUpdate(repo) } coAnswers { stopCycle() }
        assertSame(error, assertThrows(error.javaClass) { tasks[if (job == "daily") 0 else 1].run() })
        assertEquals(if (job == "daily") 1 else 2, tasks.size, "Shutdown must not register another job")
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
