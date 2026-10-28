package app.universal.patch.manager.patcher.runtime

import app.universal.patch.manager.network.runtime.LoadedPatcherRuntimePlugin
import app.universal.patch.manager.network.runtime.PatcherRuntimeKind

object PatcherRuntimePluginRegistry {
    @Volatile
    private var provider: (() -> Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin>)? = null

    fun install(provider: () -> Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin>) {
        this.provider = provider
    }

    fun runtimeFor(kind: PatcherRuntimeKind): LoadedPatcherRuntimePlugin? =
        provider?.invoke()?.get(kind)
}
