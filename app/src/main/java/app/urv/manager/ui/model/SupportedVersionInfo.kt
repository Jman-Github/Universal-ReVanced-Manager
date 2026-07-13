package app.urv.manager.ui.model

data class SupportedVersionInfo(
    val version: String,
    val experimental: Boolean = false,
    val versionCodes: Set<Long> = emptySet()
)
