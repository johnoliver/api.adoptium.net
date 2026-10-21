package net.adoptium.api.v3

import com.mongodb.MongoException
import io.quarkus.arc.profile.UnlessBuildProfile
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.adoptium.api.v3.config.APIConfig
import net.adoptium.api.v3.dataSources.APIDataStore
import net.adoptium.api.v3.dataSources.ReleaseVersionResolver
import net.adoptium.api.v3.dataSources.UpdatableVersionSupplier
import net.adoptium.api.v3.dataSources.UpdaterJsonMapper
import net.adoptium.api.v3.dataSources.models.AdoptRepos
import net.adoptium.api.v3.dataSources.models.AdoptCdxaRepos
import net.adoptium.api.v3.dataSources.models.GitHubId
import net.adoptium.api.v3.dataSources.models.FeatureRelease
import net.adoptium.api.v3.dataSources.models.Releases
import net.adoptium.api.v3.dataSources.persitence.ApiPersistence
import net.adoptium.api.v3.models.GitHubDownloadStatsDbEntry
import net.adoptium.api.v3.models.Release
import net.adoptium.api.v3.releaseNotes.AdoptReleaseNotes
import net.adoptium.api.v3.stats.GitHubDownloadStatsCalculator
import net.adoptium.api.v3.stats.StatsInterface
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timerTask

@UnlessBuildProfile("test")
@ApplicationScoped
@ApplicationPath("/")
@Startup
class V3UpdaterApp : Application()

@ApplicationScoped
class V3Updater @Inject constructor(
    private val adoptReposBuilder: AdoptReposBuilder,
    private val adoptCdxaReposBuilder: AdoptCdxaReposBuilder,
    private val apiDataStore: APIDataStore,
    private val database: ApiPersistence,
    private val statsInterface: StatsInterface,
    private val releaseVersionResolver: ReleaseVersionResolver,
    private val adoptReleaseNotes: AdoptReleaseNotes,
    private val updatableVersionSupplier: UpdatableVersionSupplier
) : Updater {

    private val mutex = Mutex()
    private val toUpdate: ConcurrentSkipListSet<String> = ConcurrentSkipListSet<String>()

    companion object {
        @JvmStatic
        private val LOGGER = LoggerFactory.getLogger(this::class.java)

        fun calculateChecksum(repo: AdoptRepos): String {
            val md = MessageDigest.getInstance("SHA256")
            val outputStream = object : OutputStream() {
                override fun write(b: Int) {
                    md.update(b.toByte())
                }
            }
            UpdaterJsonMapper.mapper.writeValue(outputStream, repo)

            return String(Base64.getEncoder().encode(md.digest()))
        }

        fun calculateCdxaChecksum(repo: AdoptCdxaRepos?): String {
            if (repo == null) {
                return "null"
            }

            val md = MessageDigest.getInstance("SHA256")
            val outputStream = object : OutputStream() {
                override fun write(b: Int) {
                    md.update(b.toByte())
                }
            }
            UpdaterJsonMapper.mapper.writeValue(outputStream, repo)

            return String(Base64.getEncoder().encode(md.digest()))
        }

        fun copyOldReleasesIntoNewRepo(
            currentRepo: AdoptRepos, newRepoData: AdoptRepos, filter: ReleaseIncludeFilter,
            excludedIds: Set<GitHubId> = emptySet()
        ) = ReleaseExclusions.removeExcluded(currentRepo
            .removeReleases { vendor, startTime, isPrerelease -> filter.filter(vendor, startTime, isPrerelease) }
            .addAll(newRepoData
                .allReleases
                .getReleases()
                .toList()
            ), excludedIds)
    }

    override fun addToUpdate(toUpdate: String): List<Release> {
        val repo = apiDataStore.loadDataFromDb(true)
        val toUpdateList = repo
            .allReleases
            .getReleases()
            .filter { release ->
                release.release_name == toUpdate
            }
            .toList()

        if (toUpdateList.isNotEmpty()) {
            toUpdate.plus(toUpdate)
        }

        return toUpdateList
    }

    private fun incrementalUpdate(oldRepo: AdoptRepos): AdoptRepos? {
        return runBlocking {
            // Must catch errors or may kill the scheduler
            try {
                LOGGER.info("Starting Incremental update")
                val toUpdateTmp = HashSet<String>()
                toUpdateTmp.addAll(toUpdate)
                toUpdate.clear()
                val excludedIds = mutableSetOf<GitHubId>()
                val updatedRepo = adoptReposBuilder.incrementalUpdate(
                    toUpdateTmp,
                    oldRepo,
                    { excludedIds.add(it) },
                    database::getGhReleaseMetadata
                )

                if (updatedRepo != oldRepo) {
                    val after = writeIncrementalUpdate(updatedRepo, oldRepo, excludedIds)
                    printRepoDebugInfo(oldRepo, after, null)
                    return@runBlocking after
                }
            } catch (e: Exception) {
                LOGGER.error("Failed to perform incremental update", e)
            }
            return@runBlocking null
        }
    }

    private fun incrementalCdxaUpdate(oldRepo: AdoptCdxaRepos): AdoptCdxaRepos? {
        return runBlocking {
            // Must catch errors or may kill the scheduler
            try {
                LOGGER.info("Starting Cdxa Incremental update")
                val updatedRepo = adoptCdxaReposBuilder.incrementalUpdate( oldRepo, database.getCdxaUpdatedAt() )

                if (updatedRepo != oldRepo) {
                    val after = writeIncrementalCdxaUpdate(updatedRepo, oldRepo)
                    printCdxaReposDebugInfo(oldRepo, after, null)
                    return@runBlocking after
                }
            } catch (e: Exception) {
                LOGGER.error("Failed to perform incremental cdxa update", e)
            }
            return@runBlocking null
        }
    }

    private fun printRepoDebugInfo(
        oldRepo: AdoptRepos,
        afterInMemory: AdoptRepos,
        afterInDb: AdoptRepos?) {

        if (APIConfig.DEBUG) {
            LOGGER.debug("Updated and db version comparison {} {} {} {}", calculateChecksum(oldRepo), oldRepo.hashCode(), calculateChecksum(afterInMemory), afterInMemory.hashCode())

            LOGGER.debug("Compare db and updated")
            deepDiffDebugPrint(oldRepo, afterInMemory)

            if (afterInDb != null) {
                LOGGER.debug("Compare in memory and in db")
                deepDiffDebugPrint(afterInMemory, afterInDb)
            }
        }
    }

    private fun printCdxaReposDebugInfo(
        oldRepo: AdoptCdxaRepos,
        afterInMemory: AdoptCdxaRepos,
        afterInDb: AdoptCdxaRepos?) {

        if (APIConfig.DEBUG) {
            LOGGER.debug("Cdxa old and new in-memory CRC comparison oldRepoChecksum={} oldRepoHashCode={} newInMemoryChecksum={} newInMemoryHashCode={}", calculateCdxaChecksum(oldRepo), oldRepo.hashCode(), calculateCdxaChecksum(afterInMemory), afterInMemory.hashCode())

            LOGGER.debug("Deep-compare Cdxa old and new in-memory")
            deepDiffCdxaDebugPrint(oldRepo, afterInMemory)

            if (afterInDb != null) {
                LOGGER.debug("Deep-compare Cdxa new in memory and new in db")
                deepDiffCdxaDebugPrint(afterInMemory, afterInDb)
            }
        }
    }

    private fun deepDiffDebugPrint(repoA: AdoptRepos, repoB: AdoptRepos) {
        repoA
            .allReleases
            .getReleases()
            .forEach { releaseA ->
                val releaseB = repoB.allReleases.getReleaseById(releaseA.id)
                if (releaseB == null) {
                    LOGGER.debug("Release disappeared ${releaseA.id} ${releaseA.version_data.semver}")
                } else if (releaseA != releaseB) {
                    LOGGER.debug("Release changedA {}", releaseA)
                    LOGGER.debug("Release changedB {}", releaseB)
                    releaseA
                        .binaries
                        .forEach { binaryA ->
                            val binaryB = releaseB
                                .binaries
                                .firstOrNull { it.`package`.link == binaryA.`package`.link }
                            if (binaryB == null) {
                                LOGGER.debug("Binary disapeared ${binaryA.`package`.name}")
                            } else if (binaryA != binaryB) {
                                LOGGER.debug("Binary updated ${binaryA.`package`.name}")
                                LOGGER.debug(JsonMapper.mapper.writeValueAsString(binaryA))
                                LOGGER.debug(JsonMapper.mapper.writeValueAsString(binaryB))
                            }
                        }
                }
            }

        repoB
            .allReleases
            .getReleases()
            .forEach { releaseB ->
                val releaseA = repoA.allReleases.getReleaseById(releaseB.id)
                if (releaseA == null) {
                    LOGGER.info("Release Added ${releaseB.id} ${releaseB.version_data.semver}")
                }
            }
    }

    private fun deepDiffCdxaDebugPrint(repoA: AdoptCdxaRepos, repoB: AdoptCdxaRepos) {
        var theSame = true
        repoA.repos.forEach { attA ->
                val attB = repoB.repos.firstOrNull { it.id == attA.id }
                if (attB == null) {
                    LOGGER.debug("Cdxa disappeared ${attA.id} ${attA.filename}")
                    theSame = false
                } else if (attA != attB) {
                    theSame = false
                    LOGGER.debug("Cdxa changedA {}", attA)
                    LOGGER.debug("Cdxa changedB {}", attB)
                }
            }

        repoB.repos.forEach { attB ->
                val attA = repoA.repos.firstOrNull { it.id == attB.id }
                if (attA == null) {
                    theSame = false
                    LOGGER.info("Cdxa Added ${attB.id} ${attB.filename}")
                }
            }
        if ( theSame ) {
            LOGGER.debug("deepDiffCdxaDebugPrint: Identical")
        } else {
            LOGGER.debug("deepDiffCdxaDebugPrint: Differences found")
        }
    }

    private suspend fun writeIncrementalUpdate(
        updatedRepo: AdoptRepos, oldRepo: AdoptRepos, excludedIds: Set<GitHubId>
    ): AdoptRepos {
        val checksum = calculateChecksum(updatedRepo)
        val oldChecksum = calculateChecksum(oldRepo)

        if (checksum == oldChecksum) {
            return updatedRepo
        }

        return mutex.withLock {
            // Ensure that the database has not been updated since calculating the incremental update
            if (database.getUpdatedAt().checksum == oldChecksum) {
                val removed = excludedDownloads(oldRepo, updatedRepo, excludedIds)
                if (!validateStats(updatedRepo, oldRepo, removed)) {
                    throw InvalidUpdateException("Stats are not sane after exclusions")
                }
                // Write accounting first. The previous-snapshot floor in validation prevents
                // a failed release write from making this adjustment a reusable loss allowance.
                saveExclusionStats(updatedRepo, oldRepo, removed)
                database.updateAllRepos(updatedRepo, checksum)
                database.setReleaseInfo(releaseVersionResolver.formReleaseInfo(updatedRepo))

                LOGGER.info("Incremental update done")
                LOGGER.info("Saved version: $checksum ${updatedRepo.hashCode()}")
                return@withLock updatedRepo
            } else {
                LOGGER.info("Incremental update done")
                LOGGER.warn("Not applying incremental update due to checksum miss $checksum ${updatedRepo.hashCode()} $oldChecksum ${oldRepo.hashCode()} ${database.getUpdatedAt().checksum}")

                // re-calculate checksum in case of schema change
                val dbVersion = apiDataStore.loadDataFromDb(true)
                val dbChecksum = calculateChecksum(dbVersion)
                if (dbChecksum != database.getUpdatedAt().checksum) {
                    database.updateAllRepos(dbVersion, dbChecksum)
                }

                return@withLock dbVersion
            }
        }
    }

    private suspend fun writeIncrementalCdxaUpdate(updatedRepo: AdoptCdxaRepos, oldRepo: AdoptCdxaRepos): AdoptCdxaRepos {
        val checksum = calculateCdxaChecksum(updatedRepo)
        val oldChecksum = calculateCdxaChecksum(oldRepo)

        if (checksum == oldChecksum) {
            return updatedRepo
        }

        return mutex.withLock {
            // Ensure that the database has not been updated since calculating the incremental update
            if (database.getCdxaUpdatedAt().checksum == oldChecksum) {
                database.updateCdxaRepos(updatedRepo, checksum)

                LOGGER.info("Incremental cdxa update done")
                LOGGER.info("Saved cdxa version: $checksum ${updatedRepo.hashCode()}")
                return@withLock updatedRepo
            } else {
                LOGGER.info("Incremental cdxa update done")
                LOGGER.warn("Not applying incremental cdxa update due to checksum miss $checksum ${updatedRepo.hashCode()} $oldChecksum ${oldRepo.hashCode()} ${database.getCdxaUpdatedAt().checksum}")

                // re-calculate checksum in case of schema change
                val dbVersion = apiDataStore.loadCdxaDataFromDb(true)
                val dbChecksum = calculateCdxaChecksum(dbVersion)
                if (dbChecksum != database.getCdxaUpdatedAt().checksum) {
                    database.updateCdxaRepos(dbVersion, dbChecksum)
                }

                return@withLock dbVersion
            }
        }
    }

    fun run(instantFullUpdate: Boolean) {
        assertConnectedToDb()

        val executor = Executors.newScheduledThreadPool(2)

        val delay = if (instantFullUpdate) 0L else 1L

        var repo: AdoptRepos = try {
            apiDataStore.loadDataFromDb(true)
        } catch (e: java.lang.Exception) {
            LOGGER.error("Failed to load db", e)
            if (e is MongoException) {
                LOGGER.error("Failed to connect to db, exiting")
                Quarkus.asyncExit(2)
                Quarkus.waitForExit()
            }
            AdoptRepos(emptyList())
        }

        var cdxaRepo: AdoptCdxaRepos = try {
            apiDataStore.loadCdxaDataFromDb(true)
        } catch (e: java.lang.Exception) {
            LOGGER.error("Failed to load cdxa db", e)
            if (e is MongoException) {
                LOGGER.error("Failed to connect to cdxa db, exiting")
                Quarkus.asyncExit(2)
                Quarkus.waitForExit()
            }   
            AdoptCdxaRepos(emptyList())
        } 

        val incrementalUpdateScheduled = AtomicBoolean(false)
        val incrementalCdxaUpdateScheduled = AtomicBoolean(false)

        executor.scheduleWithFixedDelay(
            timerTask {
                try {
                    runUpdate(repo, incrementalUpdateScheduled, executor)
                    runCdxaUpdate(cdxaRepo, incrementalCdxaUpdateScheduled, executor)
                } catch (e: InvalidUpdateException) {
                    LOGGER.error("Failed to perform update", e)
                }
            },
            delay, 1, TimeUnit.DAYS
        )
    }

    fun runUpdate(
        repo: AdoptRepos,
        incrementalUpdateScheduled: AtomicBoolean,
        executor: ScheduledExecutorService
    ): AdoptRepos {
        var repo1 = repo
        repo1 = fullUpdate(repo1, true) ?: repo1
        repo1 = incrementalUpdate(repo1) ?: repo1
        if (!incrementalUpdateScheduled.getAndSet(true)) {
            executor.scheduleWithFixedDelay(
                timerTask {
                    repo1 = incrementalUpdate(repo1) ?: repo1
                },
                1, 6, TimeUnit.MINUTES
            )
        }
        repo1 = fullUpdate(repo1, false) ?: repo1
        return repo1
    }

    fun runCdxaUpdate(
        repo: AdoptCdxaRepos,
        incrementalCdxaUpdateScheduled: AtomicBoolean,
        executor: ScheduledExecutorService
    ): AdoptCdxaRepos {
        var repo1 = repo
        repo1 = fullCdxaUpdate(repo1) ?: repo1
        repo1 = incrementalCdxaUpdate(repo1) ?: repo1
        if (!incrementalCdxaUpdateScheduled.getAndSet(true)) {
            executor.scheduleWithFixedDelay(
                timerTask {
                    repo1 = incrementalCdxaUpdate(repo1) ?: repo1
                },
                1, 6, TimeUnit.MINUTES
            )
        }
        return repo1
    }

    private fun assertConnectedToDb() {
        val connected = try {
            runBlocking {
                return@runBlocking apiDataStore.isConnectedToDb()
            }
        } catch (e: java.lang.Exception) {
            LOGGER.error("Failed to load db", e)
            false
        }

        if (!connected) {
            LOGGER.error("Failed to connect to db, exiting process")
            Quarkus.asyncExit(2)
            Quarkus.waitForExit()
        }
    }

    @Throws(InvalidUpdateException::class)
    private fun fullUpdate(currentRepo: AdoptRepos, releasesOnly: Boolean): AdoptRepos? {
        // Must catch errors or may kill the scheduler
        try {
            return runBlocking {
                LOGGER.info("Starting Full update {}", releasesOnly)

                val previousChecksum = calculateChecksum(currentRepo)
                val expectedChecksum = mutex.withLock {
                    // Capture only after any in-flight publication finishes, even if it fails
                    // and leaves an incomplete marker. Such a stable marker can still recover.
                    val storedChecksum = database.getUpdatedAt().checksum
                    if (storedChecksum != previousChecksum) {
                        // Loading filters invalid assets and empty features, so its checksum can
                        // differ from the raw stored checksum without representing a stale snapshot.
                        val latest = apiDataStore.loadDataFromDb(forceUpdate = true, logEntries = false)
                        if (calculateChecksum(latest) != previousChecksum) return@runBlocking latest
                    }
                    storedChecksum
                }

                updatableVersionSupplier.updateVersions()

                val filterType: ReleaseFilterType = if (releasesOnly) {
                    ReleaseFilterType.RELEASES_ONLY
                } else {
                    ReleaseFilterType.ALL
                }

                val filter = ReleaseIncludeFilter(TimeSource.now(), filterType)

                // Repository rebuilds run concurrently; exclusions belong only to this attempt.
                val excludedIds = ConcurrentHashMap.newKeySet<GitHubId>()
                val newRepoData = adoptReposBuilder.build(filter, currentRepo) { excludedIds.add(it) }

                val repo = copyOldReleasesIntoNewRepo(currentRepo, newRepoData, filter, excludedIds)
                val removed = excludedDownloads(currentRepo, repo, excludedIds)

                printRepoDebugInfo(currentRepo, repo, null)

                val checksum = calculateChecksum(repo)

                val dataInDb = mutex.withLock {
                    val storedChecksum = database.getUpdatedAt().checksum
                    if (storedChecksum != expectedChecksum) {
                        LOGGER.warn("Discarding stale full rebuild: $expectedChecksum != $storedChecksum")
                        return@runBlocking apiDataStore.loadDataFromDb(forceUpdate = true, logEntries = false)
                    }
                    if (!validateStats(repo, currentRepo, removed)) {
                        LOGGER.error("Stats do not look correct, not saving update")
                        throw InvalidUpdateException("Stats are not sane")
                    }

                    saveExclusionStats(repo, currentRepo, removed)
                    database.updateAllRepos(repo, checksum)
                    statsInterface.update(repo)
                    database.setReleaseInfo(releaseVersionResolver.formReleaseInfo(repo))
                    apiDataStore.loadDataFromDb(forceUpdate = true, logEntries = false)
                }

                LOGGER.info("Updating Release Notes")
                adoptReleaseNotes.updateReleaseNotes(repo)

                printRepoDebugInfo(currentRepo, repo, dataInDb)

                LOGGER.info("Full update done")
                return@runBlocking repo
            }
        } catch (e: Exception) {
            LOGGER.error("Failed to perform full update", e)
        } catch (e: Throwable) {
            // Log and rethrow, may be unrecoverable error such as OutOfMemoryError
            LOGGER.error("Error during full update", e)
            throw e
        }
        return null
    }

    @Throws(InvalidUpdateException::class)
    private fun fullCdxaUpdate(currentRepo: AdoptCdxaRepos): AdoptCdxaRepos? {
        // Must catch errors or may kill the scheduler
        try {
            return runBlocking {
                LOGGER.info("Starting Full Cdxa update")

                updatableVersionSupplier.updateVersions()

                val repo = adoptCdxaReposBuilder.build()

                printCdxaReposDebugInfo(currentRepo, repo, null)

                val checksum = calculateCdxaChecksum(repo)

                val dataInDb = mutex.withLock {
                    runBlocking {
                        database.updateCdxaRepos(repo, checksum)

                        apiDataStore.loadCdxaDataFromDb(forceUpdate = true, logEntries = true)
                    }
                }

                printCdxaReposDebugInfo(currentRepo, repo, dataInDb)

                LOGGER.info("Full Cdxa update done")
                return@runBlocking repo
            }
        } catch (e: Exception) {
            LOGGER.error("Failed to perform full Cdxa update", e)
        } catch (e: Throwable) {
            // Log and rethrow, may be unrecoverable error such as OutOfMemoryError
            LOGGER.error("Error during full update", e)
            throw e
        }
        return null
    }

    class InvalidUpdateException(message: String) : Exception(message)

    private fun publicationStats(repo: AdoptRepos, previous: AdoptRepos): List<GitHubDownloadStatsDbEntry> {
        // A removed feature version must be validated and saved as zero, not disappear from validation.
        val emptyFeatures = previous.repos.mapValues { (version, _) -> FeatureRelease(version, Releases(emptyList())) }
        return GitHubDownloadStatsCalculator.getStats(AdoptRepos(emptyFeatures + repo.repos))
    }

    private fun excludedDownloads(previous: AdoptRepos, repo: AdoptRepos, excludedIds: Set<GitHubId>): Map<Int, Long> {
        val retainedIds = repo.allReleases.nodeList.map { GitHubId(it.id) }.toSet()
        val excluded = previous.allReleases.nodeList.filter {
            GitHubId(it.id) !in retainedIds &&
                (GitHubId(it.id) in excludedIds || ReleaseExclusions.isExcluded(it.release_link, it.release_name))
        }
        val byVersion = excluded.groupBy { it.version_data.major }
            .map { (version, releases) -> FeatureRelease(version, Releases(releases)) }
        return GitHubDownloadStatsCalculator.getStats(AdoptRepos(byVersion))
            .filter { it.downloads > 0 }.associate { it.feature_version to it.downloads }
    }

    private suspend fun saveExclusionStats(repo: AdoptRepos, previous: AdoptRepos, removed: Map<Int, Long>) {
        val adjusted = publicationStats(repo, previous).filter { it.feature_version in removed }
        if (adjusted.isNotEmpty()) database.addGithubDownloadStatsEntries(adjusted)
    }

    private suspend fun validateStats(repo: AdoptRepos, previous: AdoptRepos, removed: Map<Int, Long>): Boolean {
        val previousStats = GitHubDownloadStatsCalculator.getStats(previous).associateBy { it.feature_version }
        return publicationStats(repo, previous)
            .filter { newEntry ->
                val lastEntry = database.getLatestGithubStatsForFeatureVersion(newEntry.feature_version)
                val lastDownloads = lastEntry?.downloads ?: 0
                val allowance = removed[newEntry.feature_version] ?: 0
                // A saved adjustment may precede a failed release write. Always protect the
                // previous snapshot too, even when this attempt excludes nothing.
                val previousDownloads = if (lastEntry != null) previousStats[newEntry.feature_version]?.downloads ?: 0 else 0
                val minimum = maxOf(lastDownloads, previousDownloads) - allowance

                if (minimum > newEntry.downloads) {
                    LOGGER.error("Stats for ${newEntry.feature_version} are below the exclusion-adjusted minimum $minimum > ${newEntry.downloads}")
                }
                return@filter minimum > newEntry.downloads
            }
            .isEmpty()
    }

}
