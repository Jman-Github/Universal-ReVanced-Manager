package app.urv.manager.domain.bundles

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RepositoryBundleSettings(
    val usePrereleases: Boolean = false
)

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
