import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.adoptium.api.v3.JsonMapper
import net.adoptium.api.v3.models.Release
import net.adoptium.marketplace.client.MarketplaceMapper
import net.adoptium.marketplace.schema.*
import org.eclipse.jetty.client.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.absolutePathString

class ExtractIbmReleases {

    private lateinit var httpClient: HttpClient
    private lateinit var mapper: ObjectMapper

    companion object {
        val VERSIONS = listOf(8, 11, 17)
    }

    @BeforeEach
    fun setup() {
        mapper = createMapper()

        httpClient = HttpClient()
        httpClient.isFollowRedirects = true
        httpClient.start()
    }

    @AfterEach
    fun shutdown() {
        httpClient.stop()
    }

    // Creates an object mapper that ignores the Vendor value. Required as the IBM api uses a vendor that we do not have in our models
    private fun createMapper(): ObjectMapper {
        // For this test ignore value and just return a vendor
        return JsonMapper.mapper.registerModule(object : com.fasterxml.jackson.databind.module.SimpleModule() {
            override fun setupModule(context: SetupContext?) {
                addDeserializer(net.adoptium.api.v3.models.Vendor::class.java, object : JsonDeserializer<net.adoptium.api.v3.models.Vendor>() {
                    override fun deserialize(p0: JsonParser?, p1: DeserializationContext?): net.adoptium.api.v3.models.Vendor {

                        // Ignore value and return adoptium
                        return net.adoptium.api.v3.models.Vendor.adoptium
                    }
                })
                super.setupModule(context)
            }
        })
    }

    //@Disabled("For manual execution")
    @Test
    fun buildSemeruRepo() {

        val dir = Files.createTempDirectory("repo")

        // Write ./index.json file
        createTopLevelIndexFile(dir)

        // Only doing LTS for now
        VERSIONS
            .map { version ->
                buildRepoForVersion(dir, version)
            }

        println("Repo created at ${dir.absolutePathString()}")
    }

    private fun buildRepoForVersion(
        dir: Path,
        version: Int
    ) {
        // Get directory to write to, i.e `./8/`
        val versionDir = Path.of(dir.toFile().absolutePath, "$version").toFile()
        versionDir.mkdirs()

        // Get non-certified editions
        val semeruReleases = getIbmReleases("ibm", version)
        val semeruMarketplaceReleases = convertToMarketplaceSchema(Distribution.semeru, semeruReleases)

        // Get certified editions
        val semeruCeReleases = getIbmReleases("ibm_ce", version)
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

    private fun getIbmReleases(vendorName: String, version: Int): List<Release> {
        // Possibly might need to check next page...one day
        val response = httpClient.GET("https://ibm.com/semeru-runtimes/api/v3/assets/feature_releases/${version}/ga?vendor=${vendorName}&page_size=100")

        if (response.status != 200) {
            System.err.println("No data found for $version $vendorName")
            return emptyList()
        }

        return mapper.readValue(response.content)
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
