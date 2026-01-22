package app.revanced.manager.morphe.runtime

import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch
import app.revanced.manager.patcher.morphe.MorphePatchBundleLoader
import java.util.LinkedHashMap

object MorpheRuntimeEntry {
    @JvmStatic
    fun loadMetadata(bundlePaths: List<String>): Map<String, List<Map<String, Any?>>> {
        val result = LinkedHashMap<String, List<Map<String, Any?>>>()
        bundlePaths.forEach { path ->
            result[path] = loadMetadataForBundle(path)
        }
        return result
    }

    @JvmStatic
    fun loadMetadataForBundle(bundlePath: String): List<Map<String, Any?>> =
        MorphePatchBundleLoader.loadBundle(bundlePath).map(::patchToMap)

    private fun patchToMap(patch: Patch<*>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["name"] = patch.name.orEmpty()
        result["description"] = patch.description
        result["use"] = patch.use
        result["compatiblePackages"] = patch.compatiblePackages?.map { (pkg, versions) ->
            linkedMapOf(
                "packageName" to pkg,
                "versions" to versions?.toList()
            )
        }
        val options = patch.options.values.map(::optionToMap)
        result["options"] = options.ifEmpty { null }
        return result
    }

    private fun optionToMap(option: Option<*>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["key"] = option.key
        result["title"] = option.title ?: option.key
        result["description"] = option.description
        result["required"] = option.required
        result["type"] = option.type.toString()
        result["default"] = normalizeValue(option.default)
        result["presets"] = option.values?.mapValues { (_, value) -> normalizeValue(value) }
        return result
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> value
        is Boolean -> value
        is Int -> value
        is Long -> value
        is Float -> value
        is Double -> value.toFloat()
        is Iterable<*> -> value.map(::normalizeValue)
        else -> value.toString()
    }
}
