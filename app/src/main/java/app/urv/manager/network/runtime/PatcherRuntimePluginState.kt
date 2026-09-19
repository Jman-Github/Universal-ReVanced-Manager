package app.urv.manager.network.runtime

import kotlinx.serialization.Serializable

sealed interface PatcherRuntimePluginState {
    data object Untrusted : PatcherRuntimePluginState

    data class Loaded(
        val plugin: LoadedPatcherRuntimePlugin
    ) : PatcherRuntimePluginState

    data class Failed(val throwable: Throwable) : PatcherRuntimePluginState
}

@Serializable
data class PatcherRuntimePluginSourceEntry(
    val id: String,
    val repoUrl: String,
    val assetSelector: String,
    val autoUpdate: Boolean = true,
    val latest: Boolean = false,
    val prerelease: Boolean = false,
    val versionKey: String? = null,
    val trustedSignatureHex: String? = null
)

data class PatcherRuntimePluginSourceState(
    val entry: PatcherRuntimePluginSourceEntry,
    val name: String,
    val version: String?,
    val repoUrl: String,
    val state: State
) {
    sealed interface State {
        data class Loaded(
            val plugin: LoadedPatcherRuntimePlugin
        ) : State

        data class Untrusted(
            val packageName: String,
            val signature: String
        ) : State

        data object Missing : State

        data class Failed(
            val throwable: Throwable
        ) : State
    }
}
