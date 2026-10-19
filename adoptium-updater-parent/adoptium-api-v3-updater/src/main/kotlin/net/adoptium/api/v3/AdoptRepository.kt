package net.adoptium.api.v3

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import net.adoptium.api.v3.dataSources.github.GitHubApi
import net.adoptium.api.v3.dataSources.github.graphql.models.GHAsset
import net.adoptium.api.v3.dataSources.github.graphql.models.GHRelease
import net.adoptium.api.v3.dataSources.github.graphql.models.PageInfo
import net.adoptium.api.v3.dataSources.github.graphql.models.summary.GHReleasesSummary
import net.adoptium.api.v3.dataSources.github.graphql.models.summary.GHRepositorySummary
import net.adoptium.api.v3.dataSources.models.AdoptRepo
import net.adoptium.api.v3.dataSources.models.FeatureRelease
import net.adoptium.api.v3.dataSources.models.GitHubId
import net.adoptium.api.v3.mapping.ReleaseMapper
import net.adoptium.api.v3.mapping.adopt.AdoptReleaseMapperFactory
import net.adoptium.api.v3.mapping.upstream.UpstreamReleaseMapper
import net.adoptium.api.v3.models.Release
import net.adoptium.api.v3.models.Vendor
import org.slf4j.LoggerFactory

interface AdoptRepository {
    suspend fun getRelease(
        version: Int, filter: ReleaseIncludeFilter, previous: List<Release> = emptyList(),
        onExcluded: (GitHubId) -> Unit = {}
    ): FeatureRelease?
    suspend fun getSummary(version: Int): GHRepositorySummary
    suspend fun getReleaseById(gitHubId: GitHubId): ReleaseResult?
    suspend fun getReleaseFilesForId(gitHubId: GitHubId): List<GHAsset>?

}

@ApplicationScoped
open class AdoptRepositoryImpl @Inject constructor(
    val client: GitHubApi,
    adoptReleaseMapperFactory: AdoptReleaseMapperFactory
) : AdoptRepository {

    companion object {
        const val ADOPT_ORG = "AdoptOpenJDK"
        const val ADOPTIUM_ORG = "adoptium"

        @JvmStatic
        private val LOGGER = LoggerFactory.getLogger(this::class.java)
    }

    private val mappers = mapOf(
        ".*/openjdk\\d+-openj9-releases/.*".toRegex() to adoptReleaseMapperFactory.get(Vendor.adoptopenjdk),
        ".*/openjdk\\d+-openj9-nightly/.*".toRegex() to adoptReleaseMapperFactory.get(Vendor.adoptopenjdk),
        ".*/openjdk\\d+-nightly/.*".toRegex() to adoptReleaseMapperFactory.get(Vendor.adoptopenjdk),
        ".*/openjdk\\d+-binaries/.*".toRegex() to adoptReleaseMapperFactory.get(Vendor.adoptopenjdk),

        ".*/openjdk\\d+-upstream-binaries/.*".toRegex() to UpstreamReleaseMapper,

        ".*/openjdk\\d+-dragonwell-binaries/.*".toRegex() to adoptReleaseMapperFactory.get(Vendor.alibaba),

        ".*/temurin\\d+-binaries/.*".toRegex() to adoptReleaseMapperFactory.get(Vendor.eclipse),

        ".*/semeru\\d+-binaries/.*".toRegex() to adoptReleaseMapperFactory.get(Vendor.ibm),
    )

    private fun getMapperForRepo(url: String): ReleaseMapper {
        val mapper = mappers
            .entries
            .firstOrNull { url.matches(it.key) }

        if (mapper == null) {
            throw IllegalStateException("No mapper found for repo $url")
        }

        return mapper.value
    }

    override suspend fun getReleaseById(gitHubId: GitHubId): ReleaseResult? {
        val release = client.getReleaseById(gitHubId)

        if (release == null) return null

        return mapRelease(release)
    }

    private suspend fun mapRelease(release: GHRelease): ReleaseResult {
        // These known malformed upstream tags are permanent policy exclusions,
        // unlike unavailable metadata or a mapping failure that can recover.
        if (ReleaseExclusions.isExcluded(release.url, release.name)) {
            return ReleaseResult(error = "Excluded upstream tag ${release.name}", retryable = false)
        }
        if (release.releaseAssets.pageInfo.hasNextPage) {
            return ReleaseResult(error = "Incomplete asset pages for ${release.name}")
        }
        return getMapperForRepo(release.url).toAdoptRelease(release)
    }

    override suspend fun getReleaseFilesForId(gitHubId: GitHubId): List<GHAsset>? {
        LOGGER.info("Getting files for " + gitHubId.id)
        return client.getReleaseById(gitHubId)
            ?.releaseAssets
            ?.assets
    }

    override suspend fun getRelease(
        version: Int, filter: ReleaseIncludeFilter, previous: List<Release>, onExcluded: (GitHubId) -> Unit
    ): FeatureRelease {
        val snapshots = previous.groupBy { GitHubId(it.id) }
        val repo = getDataForEachRepo(
            version,
            filter,
            { vendor, owner, repoName -> getRepository(filter, vendor, owner, repoName, snapshots, onExcluded) }
        )
            .await()
            .filterNotNull()
            .map { AdoptRepo(it) }

        return FeatureRelease(version, repo)
    }

    override suspend fun getSummary(version: Int): GHRepositorySummary {
        val releaseSummaries = getDataForEachRepo(
            version,
            ReleaseIncludeFilter.INCLUDE_ALL
        ) { _: Vendor, owner: String, repoName: String ->
            client.getRepositorySummary(owner, repoName).releases.releases.filterNot {
                ReleaseExclusions.isExcluded("https://github.com/$owner/$repoName/releases/", it.name)
            }
        }
            .await()
            .filterNotNull()
            .flatten()
        return GHRepositorySummary(GHReleasesSummary(releaseSummaries, PageInfo(false, "")))
    }

    private suspend fun getRepository(
        filter: ReleaseIncludeFilter, vendor: Vendor, owner: String, repoName: String,
        previous: Map<GitHubId, List<Release>>, onExcluded: (GitHubId) -> Unit
    ): List<Release> {
        return client
            .getRepository(owner, repoName) { updatedAt, isPrerelease -> filter.filter(vendor, updatedAt, isPrerelease) }
            .getReleases()
            .flatMap {
                try {
                    val result = mapRelease(it)
                    if (result.succeeded()) {
                        result.result!!
                    } else if (result.retryable) {
                        error("Incomplete snapshot ${it.id.id}: ${result.error}")
                    } else {
                        onExcluded(it.id)
                        return@flatMap emptyList<Release>()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (it.isEarlyAccess()) {
                        LOGGER.warn("Failed to refresh EA ${it.url}; retaining its previous snapshot", e)
                        return@flatMap previous[it.id].orEmpty()
                    }
                    // GA failures still abort rather than publishing incomplete data.
                    throw IllegalStateException("Failed to map release ${it.id.id}", e)
                }
            }
    }

    private fun <E> getDataForEachRepo(
        version: Int,
        filter: ReleaseIncludeFilter,
        getFun: suspend (Vendor, String, String) -> E
    ): Deferred<List<E?>> {
        LOGGER.info("getting $version")
        return GlobalScope.async {

            return@async listOf(
                getRepoDataAsync(ADOPT_ORG, Vendor.adoptopenjdk, "openjdk$version-openj9-releases", getFun, filter),
                getRepoDataAsync(ADOPT_ORG, Vendor.adoptopenjdk, "openjdk$version-openj9-nightly", getFun, filter),
                getRepoDataAsync(ADOPT_ORG, Vendor.adoptopenjdk, "openjdk$version-nightly", getFun, filter),
                getRepoDataAsync(ADOPT_ORG, Vendor.adoptopenjdk, "openjdk$version-binaries", getFun, filter),

                getRepoDataAsync(ADOPT_ORG, Vendor.openjdk, "openjdk$version-upstream-binaries", getFun, filter),

                getRepoDataAsync(ADOPT_ORG, Vendor.alibaba, "openjdk$version-dragonwell-binaries", getFun, filter),

                getRepoDataAsync(ADOPTIUM_ORG, Vendor.eclipse, "temurin$version-binaries", getFun, filter),

                getRepoDataAsync(ADOPT_ORG, Vendor.ibm, "semeru$version-binaries", getFun, filter)
            )
                .map { repo -> repo.await() }
        }
    }

    private fun <E> getRepoDataAsync(
        owner: String,
        vendor: Vendor,
        repoName: String,
        getFun: suspend (Vendor, String, String) -> E,
        filter: ReleaseIncludeFilter
    ): Deferred<E?> {
        return GlobalScope.async {
            if (!Vendor.validVendor(vendor) || !filter.filterVendor(vendor)) {
                return@async null
            }
            LOGGER.info("getting $owner $repoName")
            val releases = getFun(vendor, owner, repoName)
            LOGGER.info("Done getting $owner $repoName")
            return@async releases
        }
    }
}
