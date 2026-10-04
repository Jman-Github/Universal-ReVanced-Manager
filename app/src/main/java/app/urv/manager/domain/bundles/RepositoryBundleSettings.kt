package app.urv.manager.domain.bundles

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RepositoryBundleSettings(
    val usePrereleases: Boolean = false,
    val useLatest: Boolean = false,
) {
    val releaseChannel: RepositoryBundleReleaseChannel
        get() = when {
            useLatest -> RepositoryBundleReleaseChannel.LATEST
            usePrereleases -> RepositoryBundleReleaseChannel.PRERELEASE
            else -> RepositoryBundleReleaseChannel.RELEASE
        }

    companion object {
        fun forReleaseChannel(channel: RepositoryBundleReleaseChannel) = RepositoryBundleSettings(
            usePrereleases = channel == RepositoryBundleReleaseChannel.PRERELEASE,
            useLatest = channel == RepositoryBundleReleaseChannel.LATEST,
        )
    }
}

enum class RepositoryBundleReleaseChannel {
    RELEASE,
    PRERELEASE,
    LATEST,
}

object RepositoryBundleSettingsStore {
    private const val FILE_NAME = "repository_bundle_settings.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun read(directory: File): RepositoryBundleSettings = runCatching {
        json.decodeFromString<RepositoryBundleSettings>(directory.resolve(FILE_NAME).readText())
    }.getOrDefault(RepositoryBundleSettings())

    fun write(directory: File, settings: RepositoryBundleSettings) {
        directory.resolve(FILE_NAME).writeText(json.encodeToString(settings))
    }

    fun clear(directory: File) {
        runCatching { directory.resolve(FILE_NAME).delete() }
    }
}
