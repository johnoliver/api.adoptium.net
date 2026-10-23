package net.adoptium.api.v3.dataSources.github.graphql.models

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonDeserialize
import net.adoptium.api.v3.dataSources.models.GitHubId

data class GHReleases @JsonCreator constructor(
    @JsonProperty("nodes") val releases: List<GHRelease>,
    @JsonProperty("pageInfo") val pageInfo: PageInfo
)

data class GHReleaseResult @JsonCreator constructor(
    @JsonProperty("node") val release: GHRelease,
    @JsonProperty("rateLimit") override val rateLimit: RateLimit
) : HasRateLimit(rateLimit)

data class GHRelease @JsonCreator constructor(
    @JsonProperty("id")
    @JsonDeserialize(using = GitHubIdDeserializer::class)
    val id: GitHubId,
    @JsonProperty("name") val name: String,
    @JsonProperty("isPrerelease") val isPrerelease: Boolean,
    @JsonProperty("publishedAt") val publishedAt: String,
    @JsonProperty("updatedAt") val updatedAt: String,
    @JsonProperty("releaseAssets") val releaseAssets: GHAssets,
    @JsonProperty("resourcePath") val resourcePath: String,
    @JsonProperty("url") val url: String
) {
    @JsonIgnore
    fun isEarlyAccess(): Boolean = if (url.matches(UPSTREAM_REPOSITORY)) {
        // Upstream releases identify GA builds by name, not GitHub's prerelease flag.
        !name.contains(" GA ")
    } else if (url.matches(BINARY_REPOSITORY)) {
        // Modern binaries repositories maintain GitHub's prerelease flag.
        isPrerelease
    } else {
        // Historical nightly repositories identify EAs by date instead.
        DATED_RELEASE.matches(name)
    }

    companion object {
        private val UPSTREAM_REPOSITORY = Regex(".*/openjdk[0-9]+-upstream-binaries/.*")
        private val BINARY_REPOSITORY = Regex(".*/(temurin|openjdk|semeru)[0-9]+-binaries/.*")
        private val DATED_RELEASE = Regex(""".*(20[0-9]{2}-[0-9]{2}-[0-9]{2}|20[0-9]{6}).*""")
    }
}

data class GHAssets @JsonCreator constructor(
    @JsonProperty("nodes") val assets: List<GHAsset>,
    @JsonProperty("pageInfo") val pageInfo: PageInfo,
    @JsonProperty("totalCount") val totalCount: Int = 0,
)
