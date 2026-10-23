package net.adoptium.api.v3

import net.adoptium.api.v3.dataSources.models.AdoptRepos
import net.adoptium.api.v3.dataSources.models.GitHubId

internal object ReleaseExclusions {
    private val malformedTags = setOf("jdk17u-2022-05-27-19-32-beta", "jdk-11.0.13'+8")

    // Audited 2026-10-19: empty or damaged historical EAs with no usable version
    // metadata. Do not add transient fetch failures or checksum-schema issues.
    private val historicalEas = mapOf(
        "temurin8-binaries" to setOf(
            "jdk8u-2023-02-04-05-54-beta", "jdk8u-2022-03-04-12-42-beta", "jdk8u-2022-02-17-13-04-beta"
        ),
        "temurin11-binaries" to setOf(
            "jdk11u-2023-02-03-15-18-beta", "jdk11u-2023-02-03-02-11-beta", "jdk11u-2022-03-01-15-33-beta"
        ),
        "temurin17-binaries" to setOf("jdk17u-2023-02-03-06-44-beta", "jdk17u-2022-05-20-12-57-beta"),
        "temurin18-binaries" to setOf("jdk18-2022-02-26-05-39-beta", "jdk18-2022-02-15-15-05-beta"),
        "temurin20-binaries" to setOf("jdk20u-2023-04-05-10-53-beta"),
        "temurin21-binaries" to setOf("jdk21u-2023-12-06-16-18-beta"),
        "temurin25-binaries" to setOf(
            "jdk25u-2026-01-27-11-20-beta",
            // This release has version metadata, but several binary sidecars are absent.
            "jdk25u-2025-11-24-16-43-beta"
        )
    )

    fun isExcluded(url: String, name: String): Boolean = name in malformedTags ||
        historicalEas.any { (repository, tags) ->
            url.startsWith("https://github.com/adoptium/$repository/releases/") && name in tags
        }

    fun removeExcluded(repo: AdoptRepos, excludedIds: Set<GitHubId> = emptySet()): AdoptRepos = repo.allReleases.nodeList
        .filter { GitHubId(it.id) in excludedIds || isExcluded(it.release_link, it.release_name) }
        .fold(repo) { current, release -> current.removeRelease(release.version_data.major, release) }
}
