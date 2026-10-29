package app.universal.patch.manager.patcher.runtime

enum class RuntimeEngine(val host: String?, val entry: String) {
    MORPHE("upm-patcher-core", "app.universal.patch.manager.morphe.runtime.MorpheRuntimeEntry"),
    REVANCED21("revanced21-patcher-core", "app.universal.patch.manager.revanced.runtime.RevancedRuntimeEntry"),
    REVANCED_LEGACY("revanced-legacy-patcher-core", "app.universal.patch.manager.revanced.runtime.RevancedRuntimeEntry"),
    REVANCED22("revanced22-patcher-core", "app.universal.patch.manager.revanced.runtime.RevancedRuntimeEntry"),
    TOOLS("tools-patcher-core", "app.universal.patch.manager.patcher.host.ToolRuntimeEntry"),
    // Reseam's Android native SDK must be loaded by its Android class loader.
    RESEAM(null, "app.universal.patch.manager.reseam.runtime.ReseamRuntimeEntry")
}
