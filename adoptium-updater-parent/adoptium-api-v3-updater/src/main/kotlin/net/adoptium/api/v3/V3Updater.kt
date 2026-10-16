package net.adoptium.api.v3

import com.mongodb.MongoException
import io.quarkus.arc.profile.UnlessBuildProfile
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.Startup
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application
import kotlinx.coroutines.CancellationException
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
import net.adoptium.api.v3.dataSources.persitence.ApiPersistence
import net.adoptium.api.v3.models.Release
import net.adoptium.api.v3.releaseNotes.AdoptReleaseNotes
import net.adoptium.api.v3.stats.GitHubDownloadStatsCalculator
import net.adoptium.api.v3.stats.StatsInterface
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    private val toUpdate = ConcurrentLinkedQueue<String>()
    private var executor: ScheduledExecutorService? = null

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

        fun copyOldReleasesIntoNewRepo(currentRepo: AdoptRepos, newRepoData: AdoptRepos, filter: ReleaseIncludeFilter) = currentRepo
            .removeReleases { vendor, startTime, isPrerelease -> filter.filter(vendor, startTime, isPrerelease) }
            .addAll(newRepoData
                .allReleases
                .getReleases()
                .toList()
            )
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
            this.toUpdate.add(toUpdate)
        }

        return toUpdateList
    }

    internal suspend fun incrementalUpdate(oldRepo: AdoptRepos): AdoptRepos? {
        val requests = generateSequence { toUpdate.poll() }.toSet()
        try {
            LOGGER.info("Starting Incremental update")
            val updatedRepo = adoptReposBuilder.incrementalUpdate(requests, oldRepo, database::getGhReleaseMetadata)
            if (updatedRepo != oldRepo) {
                val after = writeIncrementalUpdate(updatedRepo, oldRepo)
                if (after != updatedRepo) toUpdate.addAll(requests)
                printRepoDebugInfo(oldRepo, after, null)
                return after
            }
        } catch (e: Exception) {
            toUpdate.addAll(requests)
            if (e is CancellationException || e is InterruptedException) throw e
            LOGGER.error("Failed to perform incremental update", e)
        }
        return null
    }

    internal suspend fun incrementalCdxaUpdate(oldRepo: AdoptCdxaRepos): AdoptCdxaRepos? {
        try {
            LOGGER.info("Starting Cdxa Incremental update")
            val updatedRepo = adoptCdxaReposBuilder.incrementalUpdate(oldRepo, database.getCdxaUpdatedAt())
            if (updatedRepo != oldRepo) {
                val after = writeIncrementalCdxaUpdate(updatedRepo, oldRepo)
                printCdxaReposDebugInfo(oldRepo, after, null)
                return after
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException) throw e
            LOGGER.error("Failed to perform incremental cdxa update", e)
        }
        return null
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

    private suspend fun writeIncrementalUpdate(updatedRepo: AdoptRepos, oldRepo: AdoptRepos): AdoptRepos {
        val checksum = calculateChecksum(updatedRepo)
        val oldChecksum = calculateChecksum(oldRepo)

        if (checksum == oldChecksum) {
            return updatedRepo
        }

        return mutex.withLock {
            // Ensure that the database has not been updated since calculating the incremental update
            if (database.getUpdatedAt().checksum == oldChecksum) {
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

    @Synchronized
    fun run(instantFullUpdate: Boolean) {
        check(executor == null) { "Updater is already running" }
        assertConnectedToDb()

        val repo: AdoptRepos = try {
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

        val cdxaRepo: AdoptCdxaRepos = try {
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

        // This worker owns scheduled snapshots through fetching and publication.
        val worker = Executors.newSingleThreadScheduledExecutor()
        executor = worker
        scheduleUpdates(repo, cdxaRepo, instantFullUpdate, worker)
    }

    internal fun scheduleUpdates(
        initialRepo: AdoptRepos,
        initialCdxaRepo: AdoptCdxaRepos,
        instantFullUpdate: Boolean,
        worker: ScheduledExecutorService
    ) {
        var repo = initialRepo
        var cdxaRepo = initialCdxaRepo
        var incrementalScheduled = false
        worker.scheduleWithFixedDelay({
            repo = runUpdate(repo)
            cdxaRepo = runCdxaUpdate(cdxaRepo)
            if (!incrementalScheduled) {
                worker.scheduleWithFixedDelay({
                    runBlocking {
                        repo = incrementalUpdate(repo) ?: repo
                        cdxaRepo = incrementalCdxaUpdate(cdxaRepo) ?: cdxaRepo
                    }
                }, 1, 6, TimeUnit.MINUTES)
                incrementalScheduled = true
            }
        }, if (instantFullUpdate) 0 else 1, 1, TimeUnit.DAYS)
    }

    @PreDestroy
    @Synchronized
    fun stop() {
        executor?.shutdownNow()
    }

    fun runUpdate(repo: AdoptRepos): AdoptRepos = runBlocking {
        var repo1 = repo
        repo1 = fullUpdate(repo1, true) ?: repo1
        repo1 = incrementalUpdate(repo1) ?: repo1
        repo1 = fullUpdate(repo1, false) ?: repo1
        repo1
    }

    fun runCdxaUpdate(repo: AdoptCdxaRepos): AdoptCdxaRepos = runBlocking {
        var repo1 = repo
        repo1 = fullCdxaUpdate(repo1) ?: repo1
        repo1 = incrementalCdxaUpdate(repo1) ?: repo1
        repo1
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
    private suspend fun fullUpdate(currentRepo: AdoptRepos, releasesOnly: Boolean): AdoptRepos? {
        // Must catch errors or may kill the scheduler
        try {
            LOGGER.info("Starting Full update {}", releasesOnly)

            updatableVersionSupplier.updateVersions()

            val filterType = if (releasesOnly) ReleaseFilterType.RELEASES_ONLY else ReleaseFilterType.ALL
            val filter = ReleaseIncludeFilter(TimeSource.now(), filterType)
            val newRepoData = adoptReposBuilder.build(filter)
            val repo = copyOldReleasesIntoNewRepo(currentRepo, newRepoData, filter)

            printRepoDebugInfo(currentRepo, repo, null)
            val checksum = calculateChecksum(repo)

            val dataInDb = mutex.withLock {
                if (!validateStats(repo)) {
                    LOGGER.error("Stats do not look correct, not saving update")
                    throw InvalidUpdateException("Stats are not sane")
                }

                database.updateAllRepos(repo, checksum)
                statsInterface.update(repo)
                database.setReleaseInfo(releaseVersionResolver.formReleaseInfo(repo))
                apiDataStore.loadDataFromDb(forceUpdate = true, logEntries = false)
            }

            LOGGER.info("Updating Release Notes")
            adoptReleaseNotes.updateReleaseNotes(repo)

            printRepoDebugInfo(currentRepo, repo, dataInDb)
            LOGGER.info("Full update done")
            return repo
        } catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException) throw e
            LOGGER.error("Failed to perform full update", e)
        } catch (e: Throwable) {
            // Log and rethrow, may be unrecoverable error such as OutOfMemoryError
            LOGGER.error("Error during full update", e)
            throw e
        }
        return null
    }

    @Throws(InvalidUpdateException::class)
    private suspend fun fullCdxaUpdate(currentRepo: AdoptCdxaRepos): AdoptCdxaRepos? {
        // Must catch errors or may kill the scheduler
        try {
            LOGGER.info("Starting Full Cdxa update")

            updatableVersionSupplier.updateVersions()

            val repo = adoptCdxaReposBuilder.build()

            printCdxaReposDebugInfo(currentRepo, repo, null)

            val checksum = calculateCdxaChecksum(repo)

            val dataInDb = mutex.withLock {
                database.updateCdxaRepos(repo, checksum)

                apiDataStore.loadCdxaDataFromDb(forceUpdate = true, logEntries = true)
            }

            printCdxaReposDebugInfo(currentRepo, repo, dataInDb)

            LOGGER.info("Full Cdxa update done")
            return repo
        } catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException) throw e
            LOGGER.error("Failed to perform full Cdxa update", e)
        } catch (e: Throwable) {
            // Log and rethrow, may be unrecoverable error such as OutOfMemoryError
            LOGGER.error("Error during full update", e)
            throw e
        }
        return null
    }

    class InvalidUpdateException(message: String) : Exception(message)

    private suspend fun validateStats(repo: AdoptRepos): Boolean {
        return GitHubDownloadStatsCalculator
            .getStats(repo)
            .filter { newEntry ->
                val lastDownloads = database.getLatestGithubStatsForFeatureVersion(newEntry.feature_version)?.downloads ?: 0

                if (lastDownloads > newEntry.downloads) {
                    LOGGER.error("Stats for ${newEntry.feature_version} are lower than the latest in the db $lastDownloads > ${newEntry.downloads}")
                }
                return@filter lastDownloads > newEntry.downloads
            }
            .isEmpty()
    }

}
