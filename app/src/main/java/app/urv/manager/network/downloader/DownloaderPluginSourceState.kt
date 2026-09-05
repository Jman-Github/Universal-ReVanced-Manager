package app.urv.manager.network.downloader

import kotlinx.serialization.Serializable

@Serializable
data class DownloaderPluginSourceEntry(
    val id: String,
    val repoUrl: String,
    val assetSelector: String,
    val autoUpdate: Boolean = true,
    val latest: Boolean = false,
    val prerelease: Boolean = false,
    val versionKey: String? = null,
    val trustedSignatureHex: String? = null,
    val trustedAsHelperApp: Boolean = false
)

data class DownloaderPluginSourceState(
    val entry: DownloaderPluginSourceEntry,
    val name: String,
    val version: String?,
    val repoUrl: String,
    val state: State
) {
    sealed interface State {
        data class Loaded(
            val plugins: List<LoadedDownloaderPlugin>
        ) : State

        data class Untrusted(
            val packageName: String,
            val signature: String,
            val helperApp: Boolean = false
        ) : State

        data class HelperApp(
            val packageName: String,
            val installed: Boolean,
            val installedVersion: String?,
            val installedSignerTrusted: Boolean
        ) : State

        data object Missing : State

        data class Failed(
            val throwable: Throwable
        ) : State
    }
}
