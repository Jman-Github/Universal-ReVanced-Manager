package app.revanced.manager.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.revanced.manager.R
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.network.api.ExternalBundlesApi
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.network.dto.ExternalBundlePatch
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.network.utils.getOrNull
import app.revanced.manager.util.simpleMessage
import app.revanced.manager.util.toast
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path

class BundleDiscoveryViewModel(
    private val api: ExternalBundlesApi,
    private val patchBundleRepository: PatchBundleRepository,
    private val app: Application,
    private val http: HttpService,
) : ViewModel() {
    var bundles: List<ExternalBundleSnapshot>? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    private val patchesByBundle = mutableStateMapOf<Int, List<ExternalBundlePatch>>()
    private val patchesLoading = mutableStateMapOf<Int, Boolean>()
    private val patchesError = mutableStateMapOf<Int, String?>()
    private val bundleCache = mutableMapOf<String, BundleCacheEntry>()
    private val bundleExports = mutableStateMapOf<Int, BundleExportProgress>()

    init {
        refresh()
    }

    fun refresh(packageNameQuery: String? = null) {
        val key = packageNameQuery?.trim().orEmpty()
        val cached = bundleCache[key]
        if (cached != null) {
            bundles = cached.bundles
        }
        viewModelScope.launch {
            isLoading = cached == null
            if (cached == null) {
                errorMessage = null
            }
            val snapshot = withContext(Dispatchers.IO) {
                api.getBundles(packageNameQuery).getOrNull()
            }
            if (snapshot == null) {
                if (cached == null) {
                    errorMessage = app.getString(R.string.patch_bundle_discovery_error)
                }
            } else {
                val fingerprint = fingerprint(snapshot)
                if (cached == null || cached.fingerprint != fingerprint) {
                    bundleCache[key] = BundleCacheEntry(snapshot, fingerprint)
                    bundles = snapshot
                }
            }
            isLoading = false
        }
    }

    fun importBundle(bundle: ExternalBundleSnapshot, autoUpdate: Boolean, searchUpdate: Boolean) {
        viewModelScope.launch {
            patchBundleRepository.createRemoteFromDiscovery(bundle, searchUpdate, autoUpdate)
        }
    }

    fun exportBundle(bundle: ExternalBundleSnapshot, target: Path) {
        viewModelScope.launch {
            val bundleId = bundle.bundleId
            val url = bundle.downloadUrl?.trim().takeIf { !it.isNullOrBlank() }
            if (url.isNullOrBlank()) {
                app.toast(app.getString(R.string.patch_bundle_discovery_error))
                return@launch
            }
            bundleExports[bundleId] = BundleExportProgress(0L, null)
            try {
                withContext(Dispatchers.IO) {
                    target.parent?.toFile()?.mkdirs()
                    http.downloadToFile(
                        saveLocation = target.toFile(),
                        builder = { url(url) },
                        onProgress = { bytesRead, bytesTotal ->
                            viewModelScope.launch(Dispatchers.Main) {
                                bundleExports[bundleId] = BundleExportProgress(bytesRead, bytesTotal)
                            }
                        }
                    )
                }
                app.toast(app.getString(R.string.patch_bundle_export_success, target.fileName.toString()))
            } catch (e: Exception) {
                app.toast(app.getString(R.string.patch_bundle_export_fail, e.simpleMessage()))
            } finally {
                bundleExports.remove(bundleId)
            }
        }
    }

    fun bundleEndpoints(bundle: ExternalBundleSnapshot): Set<String> {
        val endpoints = mutableSetOf<String>()
        bundle.downloadUrl?.let { endpoints.add(it) }
        graphqlBundleEndpoint(bundle, useDev = false)?.let { endpoints.add(it) }
        graphqlBundleEndpoint(bundle, useDev = true)?.let { endpoints.add(it) }
        legacyEndpoint(bundle.bundleId)?.let { endpoints.add(it) }
        return endpoints
    }

    fun remoteBundleUrl(bundle: ExternalBundleSnapshot): String? {
        val host = bundleHostFromDownload(bundle.downloadUrl)
            ?: bundleHostFromDownload(bundle.signatureDownloadUrl)
            ?: STABLE_BUNDLES_HOST
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        return if (owner.isNotBlank() && repo.isNotBlank()) {
            "https://$host/api/v1/bundle/$owner/$repo/latest?prerelease=${bundle.isPrerelease}"
        } else if (bundle.bundleId > 0) {
            "https://$host/bundles/id?id=${bundle.bundleId}"
        } else {
            null
        }
    }

    private fun legacyEndpoint(bundleId: Int): String? =
        "https://revanced-external-bundles.brosssh.com/bundles/id?id=$bundleId"

    private fun graphqlBundleEndpoint(bundle: ExternalBundleSnapshot, useDev: Boolean): String? {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        if (owner.isBlank() || repo.isBlank()) return null
        val host = if (useDev) {
            "revanced-external-bundles-dev.brosssh.com"
        } else {
            "revanced-external-bundles.brosssh.com"
        }
        return "https://$host/api/v1/bundle/$owner/$repo/latest?prerelease=${bundle.isPrerelease}"
    }

    fun loadPatches(bundleId: Int) {
        if (patchesByBundle.containsKey(bundleId) || patchesLoading[bundleId] == true) return
        viewModelScope.launch {
            patchesLoading[bundleId] = true
            patchesError[bundleId] = null
            val patches = withContext(Dispatchers.IO) {
                api.getBundlePatches(bundleId).getOrNull()
            }
            if (patches == null) {
                patchesError[bundleId] = app.getString(R.string.patch_bundle_discovery_error)
            } else {
                patchesByBundle[bundleId] = patches
            }
            patchesLoading[bundleId] = false
        }
    }

    fun getPatches(bundleId: Int): List<ExternalBundlePatch>? = patchesByBundle[bundleId]

    fun isPatchesLoading(bundleId: Int): Boolean = patchesLoading[bundleId] == true

    fun getPatchesError(bundleId: Int): String? = patchesError[bundleId]

    fun getExportProgress(bundleId: Int): BundleExportProgress? = bundleExports[bundleId]

    private fun fingerprint(bundles: List<ExternalBundleSnapshot>): String =
        bundles.joinToString(separator = "|") { bundle ->
            listOf(
                bundle.bundleId,
                bundle.version,
                bundle.downloadUrl,
                bundle.signatureDownloadUrl,
                bundle.isPrerelease,
                bundle.isBundleV3,
                bundle.bundleType,
                bundle.repoPushedAt,
                bundle.isRepoArchived
            ).joinToString(":")
        }

    private fun bundleHostFromDownload(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val host = runCatching { URI(trimmed).host?.lowercase() }.getOrNull() ?: return null
        return when {
            host == DEV_BUNDLES_HOST -> DEV_BUNDLES_HOST
            host == STABLE_BUNDLES_HOST -> STABLE_BUNDLES_HOST
            else -> null
        }
    }

    private data class BundleCacheEntry(
        val bundles: List<ExternalBundleSnapshot>,
        val fingerprint: String
    )

    data class BundleExportProgress(val bytesRead: Long, val bytesTotal: Long?)

    private companion object {
        const val STABLE_BUNDLES_HOST = "revanced-external-bundles.brosssh.com"
        const val DEV_BUNDLES_HOST = "revanced-external-bundles-dev.brosssh.com"
    }
}
