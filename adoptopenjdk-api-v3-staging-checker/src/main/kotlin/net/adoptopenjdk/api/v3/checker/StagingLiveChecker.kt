package net.adoptopenjdk.api.v3.checker

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import net.adoptium.api.v3.dataSources.DefaultUpdaterHtmlClient
import net.adoptium.api.v3.dataSources.HttpClientFactory
import org.apache.http.HttpResponse
import org.apache.http.concurrent.FutureCallback
import org.skyscreamer.jsonassert.JSONCompare
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.JSONCompareResult
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.suspendCoroutine

class StagingLiveChecker(
    val stagingUrl: String,
    val liveUrl: String,
    val vendor: String
) {
    companion object {

        const val STAGING_URL = "https://staging-api.adoptium.net/"
        const val LIVE_URL = "https://api.adoptium.net"

        const val STAGING_ADOPTOPENJDK_URL = "https://staging-api.adoptopenjdk.net"
        const val LIVE_ADOPTOPENJDK_URL = "https://api.adoptopenjdk.net"

        val RANGE_OF_CALLS: Array<String> = arrayOf(

            "/v3/assets/feature_releases/10/ea",
            "/v3/assets/feature_releases/10/ga",
            "/v3/assets/feature_releases/11/ea",
            "/v3/assets/feature_releases/11/ea?architecture=x64",
            "/v3/assets/feature_releases/11/ea?before=2020-12-21T10:15:30Z",
            "/v3/assets/feature_releases/11/ea?heap_size=large",
            "/v3/assets/feature_releases/11/ea?image_type=jdk",
            "/v3/assets/feature_releases/11/ea?jvm_impl=hotspot",
            "/v3/assets/feature_releases/11/ea?jvm_impl=openj9",
            "/v3/assets/feature_releases/11/ea?os=linux",
            "/v3/assets/feature_releases/11/ea&page=1",
            "/v3/assets/feature_releases/11/ea?page=2",
            "/v3/assets/feature_releases/11/ea?sort_method=DATE",
            "/v3/assets/feature_releases/11/ea?sort_order=ASC",
            "/v3/assets/feature_releases/11/ea?vendor=openjdk",
            "/v3/assets/feature_releases/11/ga",
            "/v3/assets/feature_releases/11/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/11/ga&page=1",
            "/v3/assets/feature_releases/12/ea",
            "/v3/assets/feature_releases/12/ga",
            "/v3/assets/feature_releases/12/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/13/ea",
            "/v3/assets/feature_releases/13/ga",
            "/v3/assets/feature_releases/13/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/14/ea",
            "/v3/assets/feature_releases/14/ga",
            "/v3/assets/feature_releases/14/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/15/ea",
            "/v3/assets/feature_releases/15/ga",
            "/v3/assets/feature_releases/15/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/16/ea",
            "/v3/assets/feature_releases/16/ga",
            "/v3/assets/feature_releases/16/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/17/ea",
            "/v3/assets/feature_releases/17/ea?jvm_impl=hotspot",
            "/v3/assets/feature_releases/17/ea&page=1",
            "/v3/assets/feature_releases/17/ga",
            "/v3/assets/feature_releases/17/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/17/ga&page=1",
            "/v3/assets/feature_releases/18/ea?vendor=eclipse",
            "/v3/assets/feature_releases/18/ga",
            "/v3/assets/feature_releases/18/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/19/ga",
            "/v3/assets/feature_releases/19/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/1/ga",
            "/v3/assets/feature_releases/20/ga",
            "/v3/assets/feature_releases/20/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/21/ea",
            "/v3/assets/feature_releases/21/ea?jvm_impl=hotspot",
            "/v3/assets/feature_releases/21/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/22/ga",
            "/v3/assets/feature_releases/22/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/45/ga",
            "/v3/assets/feature_releases/7/ea",
            "/v3/assets/feature_releases/7/ea?jvm_impl=hotspot",
            "/v3/assets/feature_releases/8/ea",
            "/v3/assets/feature_releases/8/ea?jvm_impl=hotspot",
            "/v3/assets/feature_releases/8/ea&page=1",
            "/v3/assets/feature_releases/8/ga",
            "/v3/assets/feature_releases/8/ga?jvm_impl=hotspot",
            "/v3/assets/feature_releases/8/ga&page=1",
            "/v3/assets/feature_releases/9/ea",
            "/v3/assets/feature_releases/9/ga",
            "/v3/assets/feature_releases/9/ga?jvm_impl=hotspot",


            "/v3/assets/latest/10/hotspot",
            "/v3/assets/latest/10/openj9",
            "/v3/assets/latest/11/hotspot",
            "/v3/assets/latest/11/openi9",
            "/v3/assets/latest/11/openj9",
            "/v3/assets/latest/12/hotspot",
            "/v3/assets/latest/12/openi9",
            "/v3/assets/latest/12/openj9",
            "/v3/assets/latest/13/hotspot",
            "/v3/assets/latest/13/openj9",
            "/v3/assets/latest/14/hotspot",
            "/v3/assets/latest/14/openj9",
            "/v3/assets/latest/15/hotsp",
            "/v3/assets/latest/15/hotspot",
            "/v3/assets/latest/15/openj9",
            "/v3/assets/latest/16/hotspot",
            "/v3/assets/latest/16/openj9",
            "/v3/assets/latest/17/hotspot",
            "/v3/assets/latest/17/openj9",
            "/v3/assets/latest/18/hotspot",
            "/v3/assets/latest/18/openj9",
            "/v3/assets/latest/19/hotspot",
            "/v3/assets/latest/19/openj9",
            "/v3/assets/latest/20/hotspot",
            "/v3/assets/latest/21/hotspot",
            "/v3/assets/latest/8/hotspot",
            "/v3/assets/latest/8/hotspot)",
            "/v3/assets/latest/8/openj9",
            "/v3/assets/latest/9/hotspot",
            "/v3/assets/latest/9/openj9",


            "/v3/assets/version/[1.0,100.0]",
            "/v3/assets/version/[1.0,100.0]?jvm_impl=hotspot",
            "/v3/assets/version/(10,11)",
            "/v3/assets/version/[11,)",
            "/v3/assets/version/11.0.10+9",
            "/v3/assets/version/(11.0,12.0)",
            "/v3/assets/version/11.0.2+9",
            "/v3/assets/version/[11.0.5,11.0.6)",
            "/v3/assets/version/11.0.8+10",
            "/v3/assets/version/(11,12)",
            "/v3/assets/version/[11,12)",
            "/v3/assets/version/(11,12)?architecture=x64",
            "/v3/assets/version/(11,12)?heap_size=large",
            "/v3/assets/version/(11,12)?image_type=jdk",
            "/v3/assets/version/(11,12)?image_type=&release_type=ea",
            "/v3/assets/version/(11,12)?image_type=sbom&release_type=ea",
            "/v3/assets/version/(11,12)?jvm_impl=openj9",
            "/v3/assets/version/(11,12)?lts=true",
            "/v3/assets/version/(11,12)?os=linux",
            "/v3/assets/version/(11,12)?page=2",
            "/v3/assets/version/(11,12)?page_size=2",
            "/v3/assets/version/(11,12)?project=jdk",
            "/v3/assets/version/(11,12)?release_type=ea",
            "/v3/assets/version/(11,12)?sort_method=DATE",
            "/v3/assets/version/(11,12)?sort_order=ASC",
            "/v3/assets/version/(11,12)?vendor=openjdk",
            "/v3/assets/version/(11,99)",
            "/v3/assets/version/(11,99)?architecture=x64",
            "/v3/assets/version/(11,99)?heap_size=large",
            "/v3/assets/version/(11,99)?image_type=jdk",
            "/v3/assets/version/(11,99)?jvm_impl=openj9",
            "/v3/assets/version/(11,99)?lts=true",
            "/v3/assets/version/(11,99)?os=linux",
            "/v3/assets/version/(11,99)?page=2",
            "/v3/assets/version/(11,99)?page_size=2",
            "/v3/assets/version/(11,99)?project=jdk",
            "/v3/assets/version/(11,99)?release_type=ea",
            "/v3/assets/version/(11,99)?sort_method=DATE",
            "/v3/assets/version/(11,99)?sort_order=ASC",
            "/v3/assets/version/(11,99)?vendor=openjdk",
            "/v3/assets/version/(12,13)",
            "/v3/assets/version/[12,13)",
            "/v3/assets/version/(13,14)",
            "/v3/assets/version/[13,14)",
            "/v3/assets/version/(14,15)",
            "/v3/assets/version/[14,15)",
            "/v3/assets/version/(15,16)",
            "/v3/assets/version/[15,16)",
            "/v3/assets/version/[15,99)",
            "/v3/assets/version/(1.8.0,9)",
            "/v3/assets/version/[8,)",
            "/v3/assets/version/8.0.212+4",
            "/v3/assets/version/(8,12]",
            "/v3/assets/version/(8,18)?image_type=sbom&release_type=ea",
            "/v3/assets/version/[8,9)",
            "/v3/assets/version/(,9.0]",


            "/v3/binary/latest/10/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/10/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/10/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ea/linux/x64/jdk/hotspot/normal/eclipse",
            "/v3/binary/latest/11/ea/mac/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ea/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/aix/ppc64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/alpine-linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/aarch64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/aarch64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/arm/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/ppc64le/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/ppc64le/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/s390x/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/s390x/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/linux/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/mac/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/mac/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/windows/x32/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/11/ga/windows/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/12/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/12/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/13/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/13/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/13/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/14/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/14/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/14/ga/mac/aarch64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/14/ga/windows/x32/jre/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/15/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/15/ga/linux/aarch64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/15/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/15/ga/mac/aarch64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/15/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/16/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/16/ga/linux/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/16/ga/mac/aarch64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/17/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/17/ga/linux/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/17/ga/mac/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/17/ga/windows/x64/jre/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/8/ea/linux/x64/jdk/hotspot/normal/adoptium",
            "/v3/binary/latest/8/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/8/ea/linux/x64/jdk/hotspot/normal/ibm",
            "/v3/binary/latest/8/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/8/ga/linux/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/latest/99/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/99/ga/mac/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/99/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/9/ea/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/9/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/latest/9/ga/mac/x64/jdk/hotspot/normal/adoptopenjdk",


            "/v3/binary/version/jdk-11.0.11+9_openj9-0.26.0/linux/x64/jdk/openj9/normal/adoptopenjdk",
            "/v3/binary/version/jdk-11.0.17+8/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/version/jdk-11.0.17+8/linux/x64/jre/hotspot/normal/adoptopenjdk",
            "/v3/binary/version/jdk-15.0.1+9/linux/aarch64/jdk/hotspot/normal/adoptopenjdk.sha1",
            "/v3/binary/version/jdk-15.0.1+9/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/version/jdk-15.0.1+9/linux/x64/jdk/hotspot/normal/adoptopenjdk.sha1",
            "/v3/binary/version/jdk-15.0.1+9/mac/x64/jdk/hotspot/normal/adoptopenjdk.sha1",
            "/v3/binary/version/jdk-15.0.1+9/windows/x64/jdk/hotspot/normal/adoptopenjdk.sha1",
            "/v3/binary/version/jdk-17+35/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/version/jdk-457889/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/version/jdk8u242-b08/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/binary/version/jdk8u242-b08/linux/x64/jdk/hotspot/normal/adoptopenjdk.sha1",
            "/v3/binary/version/jdk8u242-b08/windows/x64/jdk/hotspot/normal/adoptopenjdk.sha1",
            "/v3/binary/version/jdk8u292-b10/linux/x64/jdk/hotspot/normal/adoptopenjdk",

            "/v3/info/available_releases",
            "/v3/info/release_names",
            "/v3/info/release_versions",


            "/v3/installer/latest/11/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/11/ga/windows/x64/jre/hotspot/large/adoptopenjdk",
            "/v3/installer/latest/14/ea/linux/s390x/jre/openj9/large/adoptopenjdk",
            "/v3/installer/latest/15/ga/windows/x64/jdk/hotspot/normal/openjdk",
            "/v3/installer/latest/15/ga/windows/x86/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/16/ga/windows/x86/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/17/ga/mac/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/17/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/17/ga/windows/x86/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/18/ga/windows/x86/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/19/ga/windows/x86/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/linux/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/mac/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/mac/x64/jre/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/windows/x32/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/windows/x32/jre/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/windows/x64/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/windows/x64/jre/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/windows/x86/jdk/hotspot/normal/adoptopenjdk",
            "/v3/installer/latest/8/ga/windows/x86/jre/hotspot/normal/adoptopenjdk",


            "/v3/version/jdk-11.0.6+10",
            "/v3/version/jdk8u212-b04",

            )

        @JvmStatic
        fun main(args: Array<String>) {
            runBlocking {
                if (args.size > 0 && args[0] == "adoptopenjdk") {
                    StagingLiveChecker(STAGING_ADOPTOPENJDK_URL, LIVE_ADOPTOPENJDK_URL, "adoptopenjdk").compareAll()
                } else {
                    StagingLiveChecker(STAGING_URL, LIVE_URL, "adoptium").compareAll()
                }
            }
        }
    }

    suspend fun compareAll() {
        RANGE_OF_CALLS
            .forEach { url ->
                try {
//                    val s = formUrl(stagingUrl, url)
                    val l = formUrl(liveUrl, url)

                    val redirect = !l.contains("binary")

                    val staging = get(URL(formUrl(stagingUrl, url)), redirect)
                    val live = get(URL(formUrl(liveUrl, url)), redirect)
//                    println("Failed url curl -o - \"$s\" | grep -v download_count > /tmp/a && curl -o - \"$l\" | grep -v download_count > /tmp/b && meld /tmp/a /tmp/b")

                    println("$l")
                    when {
                        live.statusLine.statusCode != staging.statusLine.statusCode -> {
                            println("Bad code $url")
                        }

                        live.statusLine.statusCode == 200 -> {
                            compareJsonData(live, staging, url, formUrl(stagingUrl, url), formUrl(liveUrl, url))
                        }

                        live.statusLine.statusCode == 307 -> {
                            val liveLocation = live.getHeaders("location")[0].getValue()
                            val stagingLocation = staging.getHeaders("location")[0].getValue()
                            if (liveLocation != stagingLocation) {
                                println("Different redirect:  $liveLocation $stagingLocation")
                            } else {
                                println("good $url ${live.statusLine.statusCode}")
                            }
                        }

                        else -> {
                            println("good $url ${live.statusLine.statusCode}")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
    }

    private fun formUrl(host: String, url: String): String {
        val finalSection = url.indexOfLast { c -> c == '/' } + 1

        val paramsIndex = if (url.contains('?')) {
            url.indexOfLast { c -> c == '?' }
        } else {
            url.length
        }

        val prefix = url.substring(0, finalSection)
        val suffix = url.substring(finalSection, paramsIndex)
        val query = url.substring(paramsIndex)

        val query2 = if (query.contains("vendor")) {
            query
        } else if (query.isEmpty()) {
            "?vendor=${vendor}"
        } else {
            "$query&vendor=${vendor}"
        }

        val urlEncoded = URLEncoder.encode(suffix, "UTF-8")

        val url2 = if (host.contains("adoptium") && urlEncoded.contains("adoptopenjdk")) {
            urlEncoded.replace("adoptopenjdk", "eclipse")
        } else {
            urlEncoded
        }

        return "$host$prefix$url2$query2"
    }

    private fun compareJsonData(
        live: HttpResponse,
        staging: HttpResponse,
        url: String,
        stagingUrl: String,
        liveUrl: String
    ) {
        val liveJsonSanetised = getData(live)
        val stagingJsonSanetised = getData(staging)

        removeOutOfSyncData(liveJsonSanetised, stagingJsonSanetised)

        try {
            val result: JSONCompareResult = JSONCompare.compareJSON(
                liveJsonSanetised?.toString(),
                stagingJsonSanetised?.toString(),
                JSONCompareMode.NON_EXTENSIBLE
            )
            if (result.failed()) {
                //val staging = formUrl(STAGING_URL, url)
                //val live = formUrl(LIVE_URL, url)
                println("====================")
                println("Failed url curl -L -o - \"$stagingUrl\" | grep -v download_count > /tmp/a && curl -L -o - \"$liveUrl\" | grep -v download_count > /tmp/b && meld /tmp/a /tmp/b")
                println(result.message)
                println("====================")
                liveJsonSanetised?.toString()
                stagingJsonSanetised?.toString()
            } else {
                println("good $url ${live.statusLine.statusCode}")
            }
        } catch (e: Exception) {
            e.toString()
        }
    }

    private fun removeOutOfSyncData(
        liveJsonSanetised: JsonElement?,
        stagingJsonSanetised: JsonElement?
    ) {
        // if array sizes differ it is likely that staging and live are slightly out of sync, remove elements that are out of sync
        if (liveJsonSanetised?.isJsonArray == true) {
            while (liveJsonSanetised.asJsonArray.size() > 0) {
                // probably due to updates out of sync, pop off the newer entries
                val liveId = liveJsonSanetised.asJsonArray?.get(0)?.asJsonObject?.get("id")
                val stagingId = stagingJsonSanetised?.asJsonArray?.get(0)?.asJsonObject?.get("id")

                if (liveId != stagingId) {
                    stagingJsonSanetised?.asJsonArray?.remove(0)
                    liveJsonSanetised.asJsonArray?.size()?.minus(1)?.let { liveJsonSanetised.asJsonArray?.remove(it) }
                } else {
                    break
                }
            }
        }
    }

    private fun getData(live: HttpResponse): JsonElement? {
        val liveBody = DefaultUpdaterHtmlClient.extractBody(live)
        var liveJson = JsonParser.parseString(liveBody)

        // Ignore download counts as they will probably always differ
        liveJson = removeFieldElement(liveJson) { key, value ->
            key == "download_count" ||
                key == "aqavit_results_link" ||
                value.isJsonObject &&
                value.asJsonObject.getAsJsonPrimitive("vendor")?.asString == "adoptium" ||
                key == "release_notes"
        }
        return liveJson
    }

    private fun removeFieldElement(jsonValue: JsonElement?, predicate: (String?, JsonElement) -> Boolean): JsonElement? {
        return when {
            jsonValue == null -> {
                null
            }

            jsonValue.isJsonNull -> {
                jsonValue.asJsonNull
            }

            jsonValue.isJsonObject -> {
                removeField(jsonValue.asJsonObject, predicate)
            }

            jsonValue.isJsonArray -> {
                removeField(jsonValue.asJsonArray, predicate)
            }

            else -> {
                jsonValue.asJsonPrimitive
            }
        }
    }

    private fun removeField(jsonObject: JsonObject?, predicate: (String?, JsonElement) -> Boolean): JsonElement? {
        if (jsonObject == null) return null

        val result = JsonObject()

        if (jsonObject.has("dateTime")) {
            return JsonPrimitive(jsonObject.get("dateTime").asString)
        }

        jsonObject
            .entrySet()
            .filter { !predicate(it.key, it.value) }
            .forEach {
                val v = removeFieldElement(it.value, predicate)
                if (v != null) result.add(it.key, v)
            }

        if (result.entrySet().size != jsonObject.entrySet().size) {
            "foo".toString()
        }
        return result
    }

    private fun removeField(array: JsonArray?, predicate: (String?, JsonElement) -> Boolean): JsonArray? {
        if (array == null) return null

        val jsonArray = JsonArray()

        array
            .filter { !predicate(null, it) }
            .forEach {
                jsonArray.add(removeFieldElement(it, predicate))
            }

        if (jsonArray.size() == 0) {
            return null
        }

        return jsonArray
    }

    private fun removeFieldElement(s: String, jsonValue: JsonElement?): JsonElement? {
        return when {
            jsonValue == null -> {
                null
            }

            jsonValue.isJsonNull -> {
                jsonValue.asJsonNull
            }

            jsonValue.isJsonObject -> {
                removeField(s, jsonValue.asJsonObject)
            }

            jsonValue.isJsonArray -> {
                removeField(s, jsonValue.asJsonArray)
            }

            else -> {
                jsonValue.asJsonPrimitive
            }
        }
    }

    private fun removeField(s: String, jsonObject: JsonObject?): JsonElement? {
        if (jsonObject == null) return null

        val result = JsonObject()

        if (jsonObject.has("dateTime")) {
            return JsonPrimitive(jsonObject.get("dateTime").asString)
        }

        jsonObject
            .entrySet()
            .filter { it.key != s }
            .forEach {
                result.add(it.key, removeFieldElement(s, it.value))
            }

        return result
    }

    private fun removeField(s: String, array: JsonArray?): JsonArray? {
        if (array == null) return null

        val jsonArray = JsonArray()

        array.forEach {
            jsonArray.add(removeFieldElement(s, it))
        }

        return jsonArray
    }

    private val httpClient = HttpClientFactory().getNonRedirectHttpClient()
    private val httpClientRedirect = HttpClientFactory().getHttpClient()

    private suspend fun get(url: URL, redirect: Boolean = false): HttpResponse {
        val request = org.apache.http.client.methods.RequestBuilder
            .get(url.toURI())
            .setConfig(HttpClientFactory.REQUEST_CONFIG)
            .build()

        var client = if (redirect) httpClientRedirect else httpClient

        return suspendCoroutine { continuation ->
            client.execute(
                request,
                object : FutureCallback<HttpResponse> {
                    override fun completed(p0: HttpResponse) {
                        continuation.resumeWith(Result.success(p0))
                    }

                    override fun failed(p0: Exception) {
                        continuation.resumeWith(Result.failure(p0))
                    }

                    override fun cancelled() {
                        TODO("Not yet implemented")
                    }
                }
            )
        }
    }
}
