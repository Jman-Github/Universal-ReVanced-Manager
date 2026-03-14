package app.revanced.manager.network.downloader

sealed interface DownloaderPluginState {
    data object Untrusted : DownloaderPluginState

    data class Loaded(
        val plugins: List<LoadedDownloaderPlugin>,
        val classLoader: ClassLoader,
        val name: String
    ) : DownloaderPluginState

    data class Failed(val throwable: Throwable) : DownloaderPluginState
}
