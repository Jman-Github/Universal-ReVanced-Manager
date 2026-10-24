package app.urv.manager.patcher.runtime

enum class RuntimeEngine(val host: String?, val entry: String) {
    MORPHE("urv-patcher-core", "app.urv.manager.morphe.runtime.MorpheRuntimeEntry"),
    REVANCED21("revanced21-patcher-core", "app.urv.manager.revanced.runtime.RevancedRuntimeEntry"),
    REVANCED_LEGACY("revanced-legacy-patcher-core", "app.urv.manager.revanced.runtime.RevancedRuntimeEntry"),
    REVANCED22("revanced22-patcher-core", "app.urv.manager.revanced.runtime.RevancedRuntimeEntry"),
    TOOLS("revanced22-patcher-core", "app.urv.manager.patcher.host.ToolRuntimeEntry"),
    // ReSeam's Android native SDK must be loaded by its Android class loader.
    RESEAM(null, "app.urv.manager.reseam.runtime.ReseamRuntimeEntry")
}
