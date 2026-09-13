package app.urv.manager.util

import app.universal.revanced.manager.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PatchBundleExportData(
    val name: String?,
    val version: String?
)

data class PatchedAppExportData(
    val appName: String?,
    val packageName: String,
    val appVersion: String?,
    val patchBundles: List<PatchBundleExportData> = emptyList(),
    val generatedAt: Instant = Instant.now(),
    val managerVersion: String = BuildConfig.VERSION_NAME
)

object ExportNameFormatter {
    const val DEFAULT_TEMPLATE = "{app name}-{app version}-{patches version}.apk"
    const val DEFAULT_MERGED_APK_TEMPLATE = "{app name}-{app version}-merged.apk"

    data class Variable(
        val token: String,
        val label: Int,
        val description: Int
    )

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        .withZone(ZoneId.systemDefault())
    private val pairedPatchBundleVariables =
        Regex("""\{patch bundle names\}([\s._+\-]*)\{patches version\}""")

    fun availableVariables(): List<Variable> = listOf(
        Variable("{app name}", app.universal.revanced.manager.R.string.export_name_variable_app_name, app.universal.revanced.manager.R.string.export_name_variable_app_name_description),
        Variable("{package name}", app.universal.revanced.manager.R.string.export_name_variable_package_name, app.universal.revanced.manager.R.string.export_name_variable_package_name_description),
        Variable("{app version}", app.universal.revanced.manager.R.string.export_name_variable_app_version, app.universal.revanced.manager.R.string.export_name_variable_app_version_description),
        Variable("{patches version}", app.universal.revanced.manager.R.string.export_name_variable_patches_version, app.universal.revanced.manager.R.string.export_name_variable_patches_version_description),
        Variable("{patch bundle names}", app.universal.revanced.manager.R.string.export_name_variable_bundle_names, app.universal.revanced.manager.R.string.export_name_variable_bundle_names_description),
        Variable("{manager version}", app.universal.revanced.manager.R.string.export_name_variable_manager_version, app.universal.revanced.manager.R.string.export_name_variable_manager_version_description),
        Variable("{timestamp}", app.universal.revanced.manager.R.string.export_name_variable_timestamp, app.universal.revanced.manager.R.string.export_name_variable_timestamp_description),
        Variable("{date}", app.universal.revanced.manager.R.string.export_name_variable_date, app.universal.revanced.manager.R.string.export_name_variable_date_description)
    )

    fun availableMergedApkVariables(): List<Variable> = availableVariables().filterNot { variable ->
        variable.token == "{patches version}" || variable.token == "{patch bundle names}"
    }

    fun format(
        template: String?,
        data: PatchedAppExportData,
        defaultTemplate: String = DEFAULT_TEMPLATE
    ): String {
        val resolvedTemplate = template?.takeIf { it.isNotBlank() } ?: defaultTemplate
        val sanitizedTemplate = replaceVariables(resolvedTemplate, data)
        val ensuredExtension = ensureExtension(sanitizedTemplate)
        val clean = ensuredExtension.trim().ifEmpty { defaultTemplate }
        return FilenameUtils.sanitize(clean)
    }

    fun preview(
        template: String,
        data: PatchedAppExportData = patchedAppPreviewData(),
        defaultTemplate: String = DEFAULT_TEMPLATE
    ): String = format(template, data, defaultTemplate)

    fun patchedAppPreviewData() = PatchedAppExportData(
        appName = "ExampleApp",
        packageName = "com.example.app",
        appVersion = "1.2.3",
        patchBundles = listOf(
            PatchBundleExportData(name = "anddea", version = "4.2.0"),
            PatchBundleExportData(name = "piko", version = "3.9.0-dev.1")
        ),
        generatedAt = Instant.now()
    )

    fun mergedApkPreviewData() = PatchedAppExportData(
        appName = "ExampleApp",
        packageName = "com.example.app",
        appVersion = "1.2.3",
        patchBundles = listOf(PatchBundleExportData(name = "Merged", version = null)),
        generatedAt = Instant.now()
    )

    private fun replaceVariables(template: String, data: PatchedAppExportData): String {
        val pairedTemplate = pairedPatchBundleVariables.replace(template) { match ->
            formatPatchBundles(data.patchBundles, match.groupValues[1])
        }
        val replacements: Map<String, String> = buildMap {
            put("{app name}", data.appName?.takeUnless { it.isBlank() } ?: data.packageName)
            put("{package name}", data.packageName)
            put("{app version}", formatVersion(data.appVersion) ?: "unknown")
            put(
                "{patches version}",
                joinValues(
                    data.patchBundles.mapNotNull { formatVersion(it.version) },
                    fallback = "unknown",
                    limit = 0
                )
            )
            put(
                "{patch bundle names}",
                joinValues(
                    data.patchBundles.mapNotNull { it.name },
                    fallback = "bundles",
                    limit = 2,
                    separator = "_"
                )
            )
            put("{manager version}", data.managerVersion.takeUnless { it.isBlank() } ?: "unknown")
            put("{timestamp}", timestampFormatter.format(data.generatedAt))
            put("{date}", dateFormatter.format(data.generatedAt))
        }

        return replacements.entries.fold(pairedTemplate) { acc, (token, value) ->
            acc.replace(token, value)
        }
    }

    private fun formatPatchBundles(
        bundles: List<PatchBundleExportData>,
        nameVersionSeparator: String
    ): String {
        val values = bundles.mapNotNull { bundle ->
            val name = bundle.name?.trim()?.takeIf(String::isNotEmpty)
            val version = formatVersion(bundle.version)
            when {
                name != null && version != null -> "$name$nameVersionSeparator$version"
                name != null -> name
                version != null -> version
                else -> null
            }
        }
        return values.takeIf { it.isNotEmpty() }?.joinToString("_")
            ?: "bundles${nameVersionSeparator}unknown"
    }

    private fun formatVersion(raw: String?): String? {
        val trimmed = raw?.trim()?.takeUnless { it.isEmpty() } ?: return null
        val normalized = trimmed.removePrefix("v").removePrefix("V")
        return "v$normalized"
    }

    private fun joinValues(
        values: List<String>,
        fallback: String,
        limit: Int,
        separator: String = "+"
    ): String {
        val filtered = values.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        if (filtered.isEmpty()) return fallback
        val effective = if (limit > 0) filtered.take(limit) else filtered
        return effective.joinToString(separator)
    }

    private fun ensureExtension(value: String): String =
        if (value.endsWith(".apk", ignoreCase = true)) value else "$value.apk"
}
