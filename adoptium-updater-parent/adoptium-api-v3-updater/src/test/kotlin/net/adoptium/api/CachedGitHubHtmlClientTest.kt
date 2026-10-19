package net.adoptium.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.adoptium.api.testDoubles.InMemoryInternalDbStore
import net.adoptium.api.v3.JsonMapper
import net.adoptium.api.v3.TimeSource
import net.adoptium.api.v3.dataSources.UpdaterHtmlClient
import net.adoptium.api.v3.dataSources.UrlRequest
import net.adoptium.api.v3.dataSources.github.CachedGitHubHtmlClient
import net.adoptium.api.v3.dataSources.mongo.CacheDbEntry
import net.adoptium.api.v3.dataSources.mongo.InternalDbStore
import org.apache.http.HttpVersion
import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicHttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.ZonedDateTime
import java.util.stream.Stream

@ExtendWith(MockKExtension::class)
class CachedGitHubHtmlClientTest {

    private data class TestData(
        val name: String,
        val modifiedTime: String,
        val lastChecked: ZonedDateTime,
        val expectUpdate: Boolean
    )

    private val tests = listOf(
        TestData(
            "recently modified files is not pulled within a day",
            CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(TimeSource.now().minusDays(15)),
            TimeSource.now(),
            false
        ),
        TestData(
            "recently modified files is not pulled within a day 2",
            CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(TimeSource.now().minusDays(15)),
            TimeSource.now().minusHours(23),
            false
        ),
        TestData(
            "recently modified files is pulled after a day",
            CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(TimeSource.now().minusDays(15)),
            TimeSource.now().minusHours(26),
            true
        ),
        TestData(
            "old files are not pulled within a week",
            CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(TimeSource.now().minusDays(31)),
            TimeSource.now().minusDays(6),
            false
        ),
        TestData(
            "old files are pulled after a week",
            CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(TimeSource.now().minusDays(31)),
            TimeSource.now().minusDays(8),
            true
        )
    )

    @TestFactory
    fun `run CachedGitHubHtmlClient update scenarios`(): Stream<DynamicTest> {
        return tests
            .map {
                DynamicTest.dynamicTest(it.name) {
                    performRequest(it.modifiedTime, it.lastChecked, it.expectUpdate)
                }
            }
            .stream()
    }

    @Test
    fun `cacheDbEntry serialization works with absent lastChecked`() {
        val cacheDbEntry = JsonMapper.mapper.readValue(
            """
            {
                "url": "foo",
                "lastModified": "bar",
                "data": "some data"
            }
            """.trimIndent(),
            CacheDbEntry::class.java
        )

        assertEquals("foo", cacheDbEntry.url)
        assertNull(cacheDbEntry.lastChecked)
        assertNull(cacheDbEntry.lastAccessed)
    }

    @Test
    fun `uncached demand fetch initializes usage`() = runBlocking {
        val store = InMemoryInternalDbStore()
        val http = mockk<UpdaterHtmlClient>()
        coEvery { http.getFullResponse(UrlRequest("demand")) } returns
            BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK").apply { entity = StringEntity("body") }
        val before = TimeSource.now()
        assertEquals("body", CachedGitHubHtmlClient(store, http).getUrl("demand"))
        assertEquals(0L, store.evictUnusedCache(before.minusDays(30)))
        assertTrue(!store.getCachedWebpage("demand")!!.lastAccessed!!.isBefore(before))
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "empty", "blank"])
    fun `unusable cached bodies are fetched unconditionally even when recently checked`(format: String) = runBlocking {
        val store = InMemoryInternalDbStore()
        val now = TimeSource.now()
        val modified = CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(now.minusYears(1))
        val oldBody = when (format) { "null" -> null; "empty" -> ""; else -> "  " }
        store.putCachedWebpage("recover", modified, now, oldBody).join()
        val http = mockk<UpdaterHtmlClient>()
        coEvery { http.getFullResponse(UrlRequest("recover")) } returns
            BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK").apply { entity = StringEntity("recovered") }
        val client = CachedGitHubHtmlClient(store, http)
        assertEquals("recovered", client.getUrl("recover"))
        assertEquals("recovered", client.getUrl("recover"))
        coVerify(exactly = 1) { http.getFullResponse(UrlRequest("recover")) }
        coVerify(exactly = 0) { http.getFullResponse(UrlRequest("recover", modified)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null-response", "empty-body", "no-body", "server-error", "not-modified"])
    fun `failed demand responses are not cached and the next access can recover`(failure: String) = runBlocking {
        val store = InMemoryInternalDbStore()
        val http = mockk<UpdaterHtmlClient>()
        val client = CachedGitHubHtmlClient(store, http)
        coEvery { http.getFullResponse(UrlRequest("retry")) } returns when (failure) {
            "null-response" -> null
            "empty-body" -> BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK").apply { entity = StringEntity("") }
            "no-body" -> BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK")
            "server-error" -> BasicHttpResponse(HttpVersion.HTTP_1_1, 503, "Unavailable").apply { entity = StringEntity("not metadata") }
            else -> BasicHttpResponse(HttpVersion.HTTP_1_1, 304, "Not Modified")
        }
        assertNull(client.getUrl("retry"))
        assertNull(store.getCachedWebpage("retry"))
        coEvery { http.getFullResponse(UrlRequest("retry")) } returns
            BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK").apply { entity = StringEntity("now available") }
        assertEquals("now available", client.getUrl("retry"))
        assertEquals("now available", store.getCachedWebpage("retry")!!.data)
        coVerify(exactly = 2) { http.getFullResponse(UrlRequest("retry")) }
    }

    @Test
    fun `cancelled demand fetch is not cached or retried`() = runBlocking {
        val store = InMemoryInternalDbStore()
        val http = mockk<UpdaterHtmlClient>()
        coEvery { http.getFullResponse(UrlRequest("cancel")) } throws CancellationException("Stopping updater")
        val client = CachedGitHubHtmlClient(store, http)
        assertThrows(CancellationException::class.java) { runBlocking { client.getUrl("cancel") } }
        assertNull(store.getCachedWebpage("cancel"))
        coVerify(exactly = 1) { http.getFullResponse(UrlRequest("cancel")) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null-response", "empty-body"])
    fun `failed background refresh preserves the old body and validation time`(failure: String) = runBlocking {
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
        coEvery { http.getFullResponse(UrlRequest("stale", modified)) } returns if (failure == "null-response") null
            else BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK").apply { entity = StringEntity("") }
        coEvery { http.getFullResponse(UrlRequest("marker", modified)) } returns
            BasicHttpResponse(HttpVersion.HTTP_1_1, 304, "Not Modified")
        val client = CachedGitHubHtmlClient(store, http)
        assertEquals("usable", client.getUrl("stale"))
        assertEquals("marker body", client.getUrl("marker"))
        // The single refresh worker reaches the marker only after stale completes.
        withTimeout(5000) { drained.await() }
        assertEquals("usable", backing.getCachedWebpage("stale")!!.data)
        assertEquals(checked, backing.getCachedWebpage("stale")!!.lastChecked)
    }

    @TestFactory
    fun `background validation does not prolong demand usage`(): Stream<DynamicTest> = listOf(200, 304).map { status ->
        DynamicTest.dynamicTest("HTTP $status preserves usage") {
            runBlocking {
                val backing = InMemoryInternalDbStore()
                val now = TimeSource.now()
                val modified = CachedGitHubHtmlClient.LAST_MODIFIED_PARSER.format(now)
                backing.putCachedWebpage("background", modified, now.minusDays(2), "old", now.minusDays(31)).join()
                val releaseResponse = CompletableDeferred<Unit>()
                val validated = CompletableDeferred<Unit>()
                var demandUsage: ZonedDateTime? = null
                val store = object : InternalDbStore by backing {
                    override suspend fun getCachedWebpage(url: String): CacheDbEntry? =
                        backing.getCachedWebpage(url)?.also { demandUsage = it.lastAccessed }

                    override fun putCachedWebpage(url: String, lastModified: String?, date: ZonedDateTime, data: String?, accessedAt: ZonedDateTime?): Job {
                        assertNull(accessedAt, "Background HTTP 200 must not be a demand write")
                        return backing.putCachedWebpage(url, lastModified, date, data, accessedAt)
                            .also { validated.complete(Unit) }
                    }

                    override suspend fun updateCheckedTime(url: String, dateTime: ZonedDateTime) {
                        backing.updateCheckedTime(url, dateTime)
                        validated.complete(Unit)
                    }
                }
                val http = mockk<UpdaterHtmlClient>()
                coEvery { http.getFullResponse(UrlRequest("background", modified)) } coAnswers {
                    releaseResponse.await()
                    BasicHttpResponse(HttpVersion.HTTP_1_1, status, "OK").apply { entity = StringEntity("new") }
                }
                assertEquals("old", CachedGitHubHtmlClient(store, http).getUrl("background"))
                assertTrue(!demandUsage!!.isBefore(now), "Cache hits are demand reads")
                releaseResponse.complete(Unit)
                withTimeout(5000) { validated.await() }
                // The refresh has finished; usage must still be exactly the original demand touch.
                assertEquals(1L, backing.evictUnusedCache(demandUsage!!))
            }
        }
    }.stream()

    private fun performRequest(modifiedTime: String?, lastChecked: ZonedDateTime, expectUpdate: Boolean) {
        runBlocking {
            val internalDbStore = InMemoryInternalDbStore()
            internalDbStore.putCachedWebpage("foo", modifiedTime, lastChecked, "bar")

            val updaterHtmlClient: UpdaterHtmlClient = mockk()
            coEvery { updaterHtmlClient.get("foo") }.returns("bar")
            val client = CachedGitHubHtmlClient(internalDbStore, updaterHtmlClient)

            client.getUrl("foo")
            val request = UrlRequest("foo", modifiedTime)

            if (expectUpdate) {
                coVerify(exactly = 1, timeout = 3000) {
                    updaterHtmlClient.getFullResponse(request)
                }
            } else {
                while (client.getQueueLength() > 0) {
                    delay(1000)
                }

                coVerify(timeout = 3000, exactly = 0) {
                    updaterHtmlClient.getFullResponse(request)
                }

                confirmVerified(updaterHtmlClient)
            }
        }
    }
}
