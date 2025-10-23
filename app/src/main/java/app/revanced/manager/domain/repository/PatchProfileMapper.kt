package app.revanced.manager.domain.repository

import android.util.Log
import app.revanced.manager.data.room.options.Option
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.patcher.patch.PatchBundleInfo
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.tag
data class PatchProfileConfiguration(
    val selection: PatchSelection,
    val options: Options,
    val missingBundles: Set<Int>,
    val changedBundles: Set<Int>
)

fun PatchProfile.toConfiguration(
    bundles: Map<Int, PatchBundleInfo.Scoped>,
    sources: Map<Int, PatchBundleSource>
): PatchProfileConfiguration {
    val selection = mutableMapOf<Int, MutableSet<String>>()
    val options = mutableMapOf<Int, MutableMap<String, MutableMap<String, Any?>>>()
    val missingBundles = mutableSetOf<Int>()
    val changedBundles = mutableSetOf<Int>()
    val endpointToSource = sources.values.mapNotNull { source ->
        (source as? RemotePatchBundle)?.endpoint?.let { endpoint -> endpoint to source }
    }.toMap()

    payload.bundles.forEach { bundle ->
        var resolvedUid = bundle.bundleUid
        var info = bundles[resolvedUid]
        if (info == null) {
            val endpoint = bundle.sourceEndpoint
            if (endpoint != null) {
                val matchingSource = endpointToSource[endpoint]
                if (matchingSource != null) {
                    resolvedUid = matchingSource.uid
                    info = bundles[resolvedUid]
                }
            }
        }
        if (info == null) {
            missingBundles += bundle.bundleUid
            return@forEach
        }
        val patchMetadata = info.patches.associateBy { it.name }

        val selectedPatches = bundle.patches
            .filter { patchMetadata.containsKey(it) }
            .toMutableSet()

        selection[resolvedUid] = selectedPatches

        val bundleOptions = mutableMapOf<String, MutableMap<String, Any?>>()
        var bundleChanged = selectedPatches.size != bundle.patches.size

        bundle.options.forEach { (patchName, optionValues) ->
            val patchInfo = patchMetadata[patchName] ?: run {
                bundleChanged = true
                return@forEach
            }
            val optionMetadata = patchInfo.options?.associateBy { it.key } ?: emptyMap()

            optionValues.forEach { (key, serialized) ->
                val option = optionMetadata[key] ?: run {
                    bundleChanged = true
                    return@forEach
                }
                val value = try {
                    serialized.deserializeFor(option)
                } catch (e: Option.SerializationException) {
                    Log.w(
                        tag,
                        "Failed to deserialize option $name:$patchName:$key for bundle ${bundle.bundleUid}",
                        e
                    )
                    bundleChanged = true
                    return@forEach
                }

                bundleOptions
                    .getOrPut(patchName, ::mutableMapOf)[key] = value
            }
        }

        if (bundleOptions.isNotEmpty()) {
            options[resolvedUid] = bundleOptions
        }

        if (bundleChanged) {
            changedBundles += resolvedUid
        }
    }

    return PatchProfileConfiguration(
        selection = selection.mapValues { it.value.toSet() },
        options = options.mapValues { bundleEntry ->
            bundleEntry.value.mapValues { it.value.toMap() }
        },
        missingBundles = missingBundles,
        changedBundles = changedBundles
    )
}
