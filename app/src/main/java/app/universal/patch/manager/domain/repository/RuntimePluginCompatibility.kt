package app.universal.patch.manager.domain.repository

/** Only our shipped v21 plugin uses this version sequence; third-party plugins keep theirs. */
internal fun runtimePluginRequiresUpdate(packageName: String, versionCode: Long): Boolean =
    packageName in setOf(
        "app.universal.revanced.manager.revanced.v21.runtime",
        "app.universal.patch.manager.revanced.v21.runtime"
    ) && versionCode < 8L
