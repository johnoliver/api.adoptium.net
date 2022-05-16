import com.fasterxml.jackson.module.kotlin.readValue
import net.adoptium.api.v3.JsonMapper
import net.adoptium.api.v3.models.Release
import net.adoptium.marketplace.client.MarketplaceMapper
import net.adoptium.marketplace.schema.*
import org.eclipse.jetty.client.HttpClient
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.json.Json
import kotlin.io.path.absolutePathString

class ExtractIbmReleases {

    companion object {
        val VERSIONS = listOf(8, 11, 17)
    }

    //@Disabled("For manual execution")
    @Test
    fun buildSemeruRepo() {
        val httpClient = HttpClient()
        httpClient.isFollowRedirects = true
        httpClient.start()

        val dir = Files.createTempDirectory("repo")

        // Write ./index.json file
        createTopLevelIndexFile(dir)

        // Only doing LTS for now
        VERSIONS
            .map { version ->
                buildRepoForVersion(dir, version, httpClient)
            }
        httpClient.stop()

        println("Repo created at ${dir.absolutePathString()}")
    }

    private fun buildRepoForVersion(
        dir: Path,
        version: Int,
        httpClient: HttpClient
    ) {
        // Get directory to write to, i.e `./8/`
        val versionDir = Path.of(dir.toFile().absolutePath, "$version").toFile()
        versionDir.mkdirs()

        // Get non-certified editions
        val semeruReleases = getIbmReleases(httpClient, "ibm", version)
        val semeruMarketplaceReleases = convertToMarketplaceSchema(Distribution.semeru, semeruReleases)

        // Get certified editions
        val semeruCeReleases = getIbmReleases(httpClient, "ibm_ce", version)
        val semeruCeMarketplaceReleases = convertToMarketplaceSchema(Distribution.semeru_ce, semeruCeReleases)

        // Merge lists
        val marketplaceReleases = semeruMarketplaceReleases.plus(semeruCeMarketplaceReleases)

        // Create index file
        createIndexFile(marketplaceReleases, versionDir)

        // Write all releases to file
        marketplaceReleases
            .forEach { release ->
                // write to file, i.e ./8/jdk8u302_b08.json
                val fos = FileWriter(Paths.get(versionDir.absolutePath, toFileName(release.releases.first())).toFile())

                // Serialize object to file
                fos.use {
                    it.write(MarketplaceMapper.repositoryObjectMapper.writeValueAsString(release))
                }
            }
    }

    private fun createIndexFile(marketplaceReleases: List<ReleaseList>, versionDir: File) {
        // Create index file i.e './8/index.json
        val indexFile = IndexFile(
            IndexFile.LATEST_VERSION,
            emptyList(),
            marketplaceReleases
                .map { toFileName(it.releases.first()) }
        )
        val indexfw = FileWriter(Paths.get(versionDir.absolutePath, "index.json").toFile())
        indexfw.use {
            it.write(MarketplaceMapper.repositoryObjectMapper.writeValueAsString(indexFile))
        }
    }

    private fun createTopLevelIndexFile(dir: Path) {
        /* Write top level index file, produces:
            {
              "indexes": [
                "8/index.json",
                "11/index.json",
                "17/index.json"
              ],
              "releases": []
            }
         */
        val indexFile = IndexFile(
            IndexFile.LATEST_VERSION,
            VERSIONS
                .map { "$it/index.json" },
            emptyList()
        )

        val indexfw = FileWriter(Paths.get(dir.toFile().absolutePath, "index.json").toFile())
        indexfw.use {
            it.write(MarketplaceMapper.repositoryObjectMapper.writeValueAsString(indexFile))
        }
    }

    private fun convertToMarketplaceSchema(distribution: Distribution, releases: List<Release>): List<ReleaseList> {
        val marketplaceReleases = releases
            .map { release ->
                ReleaseList(listOf(toMarketplaceRelease(release, toMarketplaceBinaries(distribution, release))))
            }
            .toList()
        return marketplaceReleases
    }

    private fun getIbmReleases(httpClient: HttpClient, vendorName: String, version: Int): List<Release> {
        // Possibly might need to check next page...one day
        val response = httpClient.GET("https://ibm.com/semeru-runtimes/api/v3/assets/feature_releases/${version}/ga?vendor=${vendorName}&page_size=100")

        if (response.status != 200) {
            System.err.println("No data found for $version $vendorName")
            return emptyList()
        }

        val fixedData = fixVendorValue(response.contentAsString)

        return JsonMapper.mapper.readValue(fixedData)
    }

    /**
     * Since we dont have the IBM vendor values substitute them out for one we do have, so we can deserialize them using adoptium models
     */
    private fun fixVendorValue(data: String): String {
        return StringWriter().use { releaseListWriter ->

            Json.createWriter(releaseListWriter).use { releaseListJsonWriter ->

                // Parse JSON and substitute out the vendor value
                Json.createReader(StringReader(data)).use { reader ->
                    val releases = reader.readArray()

                    // Since IBM have a vendor we cannot deserialize, temporarily substitute one we do have
                    // Select a vendor to use
                    val tmpVendor = net.adoptium.api.v3.models.Vendor.VALID_VENDORS.toList()[0].name

                    val releaseArrayBuilder = Json.createArrayBuilder()
                    releases
                        .map { release -> release.asJsonObject() }
                        .map {
                            // Replace vendor value in the json
                            val builder = Json.createObjectBuilder()
                            val entries = it.toMutableMap()
                            entries.replace("vendor", Json.createValue(tmpVendor))
                            entries
                                .entries
                                .forEach { k -> builder.add(k.key, k.value) }
                            builder.build()
                        }
                        .forEach { releaseArrayBuilder.add(it) }

                    releaseListJsonWriter.writeArray(releaseArrayBuilder.build())
                }
            }

            releaseListWriter.buffer.toString()
        }
    }

    private fun toFileName(it: net.adoptium.marketplace.schema.Release) = it
        .releaseName
        .replace("+", "_")
        .replace(".", "_")
        .replace("-", "_")
        .plus(".json")


    private fun toMarketplaceRelease(release: Release, binaries: List<Binary>): net.adoptium.marketplace.schema.Release {
        return Release(
            release.release_link,
            release.release_name,
            Date.from(release.timestamp.dateTime.toInstant()),
            binaries,
            Vendor.ibm, // ENSURE YOU DO NOT RELY ON release.vendor IT IS WRONG
            OpenjdkVersionData(
                release.version_data.major,
                release.version_data.minor,
                release.version_data.security,
                release.version_data.patch,
                release.version_data.pre,
                release.version_data.build,
                release.version_data.optional,
                release.version_data.openjdk_version
            ),
            if (release.source != null) {
                SourcePackage(
                    release.source!!.name,
                    release.source!!.link
                )
            } else null,
            null
        )
    }

    private fun toMarketplaceBinaries(distribution: Distribution, release: Release) = release
        .binaries
        .map { binary ->
            val arch = if (binary.os == net.adoptium.api.v3.models.OperatingSystem.`alpine-linux`) {
                OperatingSystem.alpine_linux
            } else {
                OperatingSystem.valueOf(binary.os.name)
            }

            val aqaLink = "<Insert AQA link here>"

            Binary(
                arch,
                Architecture.valueOf(binary.architecture.name),
                ImageType.valueOf(binary.image_type.name),
                if (binary.c_lib != null) CLib.valueOf(binary.c_lib!!.name) else null,
                JvmImpl.valueOf(binary.jvm_impl.name),
                Package(
                    binary.`package`.name,
                    binary.`package`.link,
                    binary.`package`.checksum,
                    binary.`package`.checksum_link,
                    binary.`package`.signature_link
                ),
                if (binary.installer != null) {
                    listOf(Installer(
                        binary.installer!!.name,
                        binary.installer!!.link,
                        binary.installer!!.checksum,
                        binary.installer!!.checksum_link,
                        binary.installer!!.signature_link,
                        null
                    )
                    )
                } else null,
                Date.from(binary.updated_at.dateTime.toInstant()),
                binary.scm_ref,
                binary.scm_ref,
                distribution,
                aqaLink,
                "<Insert TCK Affidavit Here>"
            )
        }
        .toList()
}
