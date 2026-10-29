package app.universal.patch.manager.network.runtime

import java.io.File

// The production registry's provider controls authorization. The harness swaps that provider's result.
enum class PatcherRuntimeKind { RESEAM }
data class LoadedPatcherRuntimePlugin(val apkFile: File)
