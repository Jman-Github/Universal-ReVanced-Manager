package app.universal.patch.manager.domain.manager

import app.universal.patch.manager.domain.repository.RuntimePluginInstalledTrust
import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val CURRENT_MANAGER_REPO = "Universal-Patch-Manager"
private const val LEGACY_MANAGER_REPO = "Universal-ReVanced-Manager"

/** Only manager repository addresses are migrated; arbitrary URLs and backup values stay intact. */
internal fun migrateManagerRepositoryUrl(value: String): String {
    val uri = runCatching { URI(value) }.getOrNull() ?: return value
    if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return value
    if (uri.rawAuthority != uri.host) return value
    val parts = uri.rawPath.orEmpty().removePrefix("/").split('/')
    val repoIndex = when {
        uri.host.equals("github.com", ignoreCase = true) -> 1
        uri.host.equals("api.github.com", ignoreCase = true) && parts.firstOrNull() == "repos" -> 2
        else -> return value
    }
    if (!parts.getOrNull(repoIndex - 1).equals("Jman-Github", ignoreCase = true)) return value
    val repo = parts.getOrNull(repoIndex) ?: return value
    if (!repo.removeSuffix(".git").equals(LEGACY_MANAGER_REPO, ignoreCase = true)) return value
    val pathParts = uri.rawPath.split('/').toMutableList()
    val pathRepoIndex = if (repoIndex == 1) 2 else 3
    pathParts[pathRepoIndex] = CURRENT_MANAGER_REPO + if (repo.endsWith(".git")) ".git" else ""
    return buildString {
        append(uri.scheme).append("://").append(uri.rawAuthority).append(pathParts.joinToString("/"))
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

/** Historical builds stored the manager GitHub repository in the patch API setting. */
internal fun normalizeLegacyManagerApi(value: String): String {
    val migrated = migrateManagerRepositoryUrl(value).trimEnd('/')
    val uri = runCatching { URI(migrated) }.getOrNull() ?: return value
    val path = uri.rawPath.orEmpty().removeSuffix(".git")
    val managerApi = uri.rawAuthority == uri.host &&
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
        ((uri.host.equals("github.com", true) &&
            path.equals("/Jman-Github/$CURRENT_MANAGER_REPO", true)) ||
            (uri.host.equals("api.github.com", true) &&
                path.equals("/repos/Jman-Github/$CURRENT_MANAGER_REPO", true)))
    return if (managerApi) "https://api.revanced.app" else value
}

internal fun migrateManagerPluginSources(raw: String): String = runCatching {
    val entries = Json.parseToJsonElement(raw) as? JsonArray ?: return raw
    var changed = false
    val migrated = entries.map { element ->
        val entry = element as? JsonObject ?: return@map element
        val repo = entry["repoUrl"] as? JsonPrimitive ?: return@map element
        if (!repo.isString) return@map element
        val url = migrateManagerRepositoryUrl(repo.content)
        if (url == repo.content) return@map element
        changed = true
        JsonObject(entry + ("repoUrl" to JsonPrimitive(url)))
    }
    if (changed) JsonArray(migrated).toString() else raw
}.getOrDefault(raw)

/** Old and new package trust is still checked against the exact saved signing certificate. */
internal fun migrateManagerRuntimePackage(value: String): String {
    val legacy = "app.universal.revanced.manager"
    val current = "app.universal.patch.manager"
    if (!value.startsWith(legacy)) return value
    val suffix = value.removePrefix(legacy)
    return if (suffix in setOf(".morphe.runtime", ".revanced.v21.runtime", ".revanced.v22.runtime", ".reseam.runtime")) {
        current + suffix
    } else value
}

internal fun migrateManagerRuntimeTrust(raw: String): String = runCatching {
    val entries = Json.parseToJsonElement(raw) as? JsonObject ?: return raw
    // Explicit UPM entries take precedence if a backup contains both package generations.
    val migrated = entries.entries.sortedBy { migrateManagerRuntimePackage(it.key) == it.key }
        .associate { entry ->
            val packageName = migrateManagerRuntimePackage(entry.key)
            val value = entry.value
            val approval = (value as? JsonPrimitive)?.takeIf { it.isString }
                ?.content?.let(RuntimePluginInstalledTrust::decode)
            val approvedPackage = approval?.packageName
            val renamedPackage = approvedPackage?.let(::migrateManagerRuntimePackage)
            // A renamed package is a new installation. Preserve its exact signer pin,
            // but never transfer the old installation's UID/time-based approval.
            val migratedValue = if (approvedPackage != renamedPackage && renamedPackage == packageName) {
                JsonPrimitive(requireNotNull(approval).signer)
            } else value
            packageName to migratedValue
        }
    if (migrated == entries) raw else JsonObject(migrated).toString()
}.getOrDefault(raw)

internal fun normalizeImportedManagerSettings(
    snapshot: PreferencesManager.SettingsSnapshot
): PreferencesManager.SettingsSnapshot = snapshot.copy(
    api = snapshot.api?.let(::normalizeLegacyManagerApi),
    downloaderPluginSourcesJson = snapshot.downloaderPluginSourcesJson?.let(::migrateManagerPluginSources),
    patcherRuntimePluginSourcesJson = snapshot.patcherRuntimePluginSourcesJson?.let(::migrateManagerPluginSources),
    acknowledgedPatcherRuntimePlugins = snapshot.acknowledgedPatcherRuntimePlugins
        ?.map(::migrateManagerRuntimePackage)?.toSet(),
    trustedPatcherRuntimePluginsJson = snapshot.trustedPatcherRuntimePluginsJson?.let(::migrateManagerRuntimeTrust)
)

/** Recovery only examines this installation's storage; importing another app's key stays explicit. */
internal fun legacyManagerKeystoreFiles(
    signingDirectory: File,
    filesDirectory: File,
    backupDirectory: File?
): List<File> = listOf("urv_keystore.keystore", "manager.keystore").flatMap { name ->
    listOfNotNull(signingDirectory, backupDirectory, filesDirectory).map { it.resolve(name) }
}.distinct()
