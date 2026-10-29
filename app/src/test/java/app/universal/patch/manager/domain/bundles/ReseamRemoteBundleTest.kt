package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.service.HttpService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ReseamRemoteBundleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val endpoint = "https://raw.githubusercontent.com/example/patches/main/patches.json"
    private val key = "ab".repeat(32)
    private val requests = ConcurrentLinkedQueue<String>()
    private var manifest = ""
    private var download = byteArrayOf()
    private val responses = mutableMapOf<String, String>()
    private lateinit var client: HttpClient

    @Before fun setUp() {
        client = HttpClient(OkHttp) {
            engine {
                addInterceptor { chain ->
                    val request = chain.request()
                    requests += request.url.toString()
                    val body = responses[request.url.toString()]?.toByteArray()
                        ?: if (request.url.toString().endsWith("patches.json")) manifest.toByteArray() else download
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .header("Content-Type", "application/json")
                        .body(body.toResponseBody(null)).build()
                }
            }
        }
        // Deliberately omit UpmAPI: a Reseam index must not use repository-release lookup.
        startKoin { modules(module { single { HttpService(Json { ignoreUnknownKeys = true }, client) } }) }
    }

    @After fun tearDown() {
        stopKoin()
        client.close()
    }

    @Test fun versionPinKeepsDownloadSelectionButDiscoversNewReleases() = runBlocking {
        manifest = index()
        val base = source(temporary.newFolder(), useLatest = true)
        val selected = base.fetchAvailableVersions().first { it.version == "1" }
        requests.clear()
        val pinned = base.withRepositorySettings(base.repositorySettings.withVersionSelection(true, selected))
            .copy(autoUpdate = true)

        assertFalse(pinned.updatesAutomatically)
        assertEquals(selected, pinned.fetchLatestReleaseInfo())
        assertTrue(requests.isEmpty())
        assertEquals("2-dev", pinned.fetchAvailableUpdateInfo().version)
        assertEquals(selected, pinned.fetchLatestReleaseInfo())
        assertEquals(selected, pinned.selectedVersion)
        assertTrue(pinned.useSpecificVersion)
        assertTrue(pinned.autoUpdate)
        assertEquals(listOf(endpoint), requests.toList())
        assertEquals(listOf("2-dev", "1"), pinned.fetchAvailableVersions().map { it.version })
        assertTrue(pinned.withRepositorySettings(pinned.repositorySettings.withVersionSelection(false))
            .updatesAutomatically)
    }

    @Test fun pinnedNotificationsKeepThePreferredReleaseChannel() = runBlocking {
        manifest = index()
        val base = source(temporary.newFolder())
        val selected = base.fetchAvailableVersions().first { it.version == "2-dev" }
        val pinned = base.withRepositorySettings(base.repositorySettings.withVersionSelection(true, selected))
        assertEquals("1", pinned.fetchAvailableUpdateInfo().version)
        assertEquals(selected, pinned.fetchLatestReleaseInfo())
        assertFalse(pinned.usePrereleases)
        assertFalse(pinned.useLatest)
    }

    @Test fun pinnedNotificationsCompareTheInstalledTagAndRejectOlderReleases() = runBlocking {
        manifest = index()
        val directory = temporary.newFolder()
        val base = source(directory, installedVersionSignature = "v4.4.0-dev.2")
        val selected = base.fetchAvailableVersions().first().copy(version = "v4.4.0-dev.2")
        val pinned = base.withRepositorySettings(base.repositorySettings.withVersionSelection(true, selected))
        // A persisted release tag is authoritative even if the archive cannot be read.
        directory.resolve("patches.jar").writeText("unreadable archive")
        assertTrue(pinned.isUpdateAvailable(selected.copy(version = "v4.4.0"), forNotification = true))
        assertFalse(pinned.isUpdateAvailable(selected.copy(version = "v4.3.0"), forNotification = true))
        assertFalse(pinned.isUpdateAvailable(selected, forNotification = true))
        assertFalse(pinned.isUpdateAvailable(selected))
    }

    @Test fun githubIndexOwnsReleaseSelectionAndPinsPublisher() = runBlocking {
        manifest = index()
        val directory = temporary.newFolder()
        val source = source(directory, usePrereleases = true)
        val release = source.fetchLatestReleaseInfo()
        assertEquals("2-dev", release.version)
        assertEquals("https://raw.githubusercontent.com/example/patches/main/dev.reseam", release.downloadUrl)
        assertEquals(key, directory.resolve("reseam-signer").readText())
        assertEquals(listOf(endpoint), requests.toList())
    }

    @Test fun githubIndexKeyChangeCannotFallBackToRepositoryReleases() = runBlocking {
        manifest = index()
        val directory = temporary.newFolder()
        val previousKey = "cd".repeat(32)
        directory.resolve("reseam-signer").writeText(previousKey)
        val error = failure { source(directory).fetchLatestReleaseInfo() }
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message.orEmpty().contains("publisher key changed"))
        assertEquals(previousKey, directory.resolve("reseam-signer").readText())
        assertEquals(listOf(endpoint), requests.toList())
    }

    @Test fun malformedGithubIndexCannotFallBackToRepositoryReleases() = runBlocking {
        manifest = index().replace(key, "bad")
        val error = failure { source(temporary.newFolder()).fetchLatestReleaseInfo() }
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message.orEmpty().contains("Invalid Reseam publisher key"))
        assertEquals(listOf(endpoint), requests.toList())
    }

    @Test fun pinnedSourceRejectsJarWithoutReplacingInstalledBundle() = runBlocking {
        manifest = index()
        download = ByteArrayOutputStream().also { buffer ->
            val attributes = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Version", "3")
            }
            JarOutputStream(buffer, attributes).use { jar ->
                jar.putNextEntry(JarEntry("classes.dex"))
                jar.write(byteArrayOf(1, 2, 3, 4))
                jar.closeEntry()
            }
        }.toByteArray()
        val directory = temporary.newFolder()
        directory.resolve("reseam-signer").writeText(key)
        val original = "installed bundle".toByteArray()
        directory.resolve("patches.jar").writeBytes(original)
        val error = failure { source(directory).downloadLatest() }
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message.orEmpty().contains("signed Reseam bundle"))
        assertArrayEquals(original, directory.resolve("patches.jar").readBytes())
        assertFalse(directory.resolve("patches.jar.download").exists())
    }

    @Test fun legacyJsonManifestStillLoads() = runBlocking {
        manifest = """{"download_url":"https://example.com/patches.rvp",
            "created_at":"2026-10-04T00:00:00","description":"Legacy","version":"3"}"""
        val release = source(temporary.newFolder(), sourceEndpoint = "https://example.com/patches.json")
            .fetchLatestReleaseInfo()
        assertEquals("3", release.version)
        assertEquals("Legacy", release.description)
    }

    @Test fun forgejoRepositoryDiscoversStableReseamAssetWithoutManifest() = runBlocking {
        val repository = "https://git.reseam.app/reseam/patches"
        val api = "https://git.reseam.app/api/v1/repos/reseam/patches/releases?limit=50"
        responses[api] = releases()
        val release = source(temporary.newFolder(), sourceEndpoint = repository).fetchLatestReleaseInfo()
        assertEquals("v1", release.version)
        assertEquals("$repository/releases/download/v1/patches.reseam", release.downloadUrl)
        assertEquals("$repository/releases/tag/v1", release.pageUrl)
        assertEquals(listOf(api), requests.toList())
    }

    @Test fun forgejoPrereleasesAndLatestRespectChannelAndIgnoreDrafts() = runBlocking {
        val repository = "https://git.reseam.app/reseam/patches"
        val api = "https://git.reseam.app/api/v1/repos/reseam/patches/releases?limit=50"
        responses[api] = releases()
        assertEquals("v2-dev", source(temporary.newFolder(), true, repository).fetchLatestReleaseInfo().version)
        assertEquals("v2-dev", source(temporary.newFolder(), sourceEndpoint = repository, useLatest = true)
            .fetchLatestReleaseInfo().version)
        assertEquals(listOf(api, api), requests.toList())
    }

    @Test fun forgejoIndexRetainsPublisherValidation() = runBlocking {
        val indexEndpoint = "https://git.reseam.app/reseam/patches/raw/branch/main/patches.json"
        responses[indexEndpoint] = index()
        val directory = temporary.newFolder()
        val release = source(directory, sourceEndpoint = indexEndpoint).fetchLatestReleaseInfo()
        assertEquals("1", release.version)
        assertEquals(key, directory.resolve("reseam-signer").readText())
        assertEquals(listOf(indexEndpoint), requests.toList())
    }

    @Test fun selfHostedGitLabUsesItsOwnApiAndRelativeAssetLinks() = runBlocking {
        val endpoint = "https://git.example.org:8443/group/subgroup/patches"
        val api = "https://git.example.org:8443/api/v4/projects/group%2Fsubgroup%2Fpatches/releases?per_page=50"
        responses[api] = """[{"tag_name":"v1","released_at":"2026-10-05T00:00:00Z",
            "assets":{"links":[{"name":"patches.reseam",
            "url":"/group/subgroup/patches/-/releases/v1/downloads/patches.reseam"}]}}]"""
        val release = source(temporary.newFolder(), sourceEndpoint = endpoint).fetchLatestReleaseInfo()
        assertEquals("https://git.example.org:8443/group/subgroup/patches/-/releases/v1/downloads/patches.reseam",
            release.downloadUrl)
        assertEquals("https://git.example.org:8443/group/subgroup/patches/-/releases/v1", release.pageUrl)
        assertEquals(listOf(api), requests.toList())
    }

    @Test fun forgejoWithoutMatchingAssetsFailsBeforeDownload() = runBlocking {
        val repository = "https://git.reseam.app/reseam/patches"
        responses["https://git.reseam.app/api/v1/repos/reseam/patches/releases?limit=50"] = "[]"
        assertTrue(failure { source(temporary.newFolder(), sourceEndpoint = repository).downloadLatest() }
            is NoSuchElementException)
        assertEquals(1, requests.size)
    }

    private fun releases() = """[
        {"tag_name":"v3-draft","draft":true,"published_at":"2026-10-05T03:00:00Z",
         "assets":[{"name":"patches.reseam","browser_download_url":"https://example.org/draft.reseam"}]},
        {"tag_name":"v2-dev","prerelease":true,"published_at":"2026-10-05T02:00:00Z",
         "assets":[{"name":"patches.reseam","browser_download_url":"https://example.org/dev.reseam"}]},
        {"tag_name":"v1","published_at":"2026-10-05T01:00:00Z",
         "assets":[{"name":"patches.json","browser_download_url":"https://example.org/patches.json"},
                   {"name":"patches.reseam",
                    "browser_download_url":"https://git.reseam.app/reseam/patches/releases/download/v1/patches.reseam"}]}
        ]"""

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (error: Throwable) {
            return error
        }
        throw AssertionError("Expected operation to fail")
    }

    private fun source(directory: File, usePrereleases: Boolean = false, sourceEndpoint: String = endpoint,
        useLatest: Boolean = false, installedVersionSignature: String? = null) = JsonPatchBundle(
        name = "Test", uid = nextUid.incrementAndGet(), displayName = null,
        createdAt = null, updatedAt = null, installedVersionSignature = installedVersionSignature, error = null,
        directory = directory, endpoint = sourceEndpoint, autoUpdate = false, searchUpdate = false,
        lastNotifiedVersion = null, enabled = true, usePrereleases = usePrereleases, useLatest = useLatest
    )

    private fun index() = """{"bundle":{"name":"Test","public_key":"$key"},"releases":[
        {"version":"2-dev","download_url":"dev.reseam","prerelease":true},
        {"version":"1","download_url":"stable.reseam"}]}"""

    companion object {
        private val nextUid = AtomicInteger(10000)
    }
}
