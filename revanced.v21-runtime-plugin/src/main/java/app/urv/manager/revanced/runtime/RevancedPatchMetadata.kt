package app.urv.manager.revanced.runtime

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.Option
import java.util.LinkedHashMap

internal fun patchMetadata(patch: Patch<*>): Map<String, Any?> {
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
    result["description"] = option.description.orEmpty()
    result["required"] = option.required
    result["type"] = option.type.toString()
    result["default"] = normalizeValue(option.default)
    result["presets"] = option.values
        ?.mapValues { (_, value) -> normalizeValue(value) }
        ?.ifEmpty { null }
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


internal fun setPatchOption(patch: Patch<*>, key: String, value: Any?) {
    patch.options[key] = value
}

internal fun patchResults(patcher: app.revanced.patcher.Patcher) = patcher()

internal fun acceptSelectedPatches(patcher: app.revanced.patcher.Patcher, patches: Set<Patch<*>>) {
    patcher += patches
}

internal fun shouldDeleteResource(
    resources: app.revanced.patcher.PatcherResult.PatchedResources,
    name: String
) = name in resources.deleteResources
