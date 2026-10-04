package app.urv.manager.domain.repository

import android.util.Log
import app.urv.manager.data.room.AppDatabase
import app.urv.manager.data.room.options.Option
import app.urv.manager.data.room.options.OptionGroup
import app.urv.manager.patcher.patch.PatchInfo
import app.urv.manager.util.Options
import app.urv.manager.util.tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

class PatchOptionsRepository(
    db: AppDatabase,
    private val patchOptionInputManager: PatchOptionInputManager
) {
    private val dao = db.optionDao()
    private val resetEventsFlow = MutableSharedFlow<ResetEvent>(extraBufferCapacity = 4)
    val resetEvents: SharedFlow<ResetEvent> = resetEventsFlow.asSharedFlow()

    private suspend fun getOrCreateGroup(bundleUid: Int, packageName: String) =
        dao.getGroupId(bundleUid, packageName) ?: OptionGroup(
            uid = AppDatabase.generateUid(),
            patchBundle = bundleUid,
            packageName = packageName
        ).also { dao.createOptionGroup(it) }.uid

    suspend fun getOptions(
        packageName: String,
        bundlePatches: Map<Int, Map<String, PatchInfo>>
    ): Options {
        val options = dao.getOptions(packageName)
        // Bundle -> Patches
        return buildMap<Int, MutableMap<String, MutableMap<String, Any?>>>(options.size) {
            options.forEach { (sourceUid, bundlePatchOptionsList) ->
                // Patches -> Patch options
                this[sourceUid] =
                    bundlePatchOptionsList.fold(mutableMapOf()) { bundlePatchOptions, dbOption ->
                        val deserializedPatchOptions =
                            bundlePatchOptions.getOrPut(dbOption.patchName, ::mutableMapOf)

                        val option =
                            bundlePatches[sourceUid]?.get(dbOption.patchName)?.options?.find { it.key == dbOption.key }
                        if (option != null) {
                            try {
                                deserializedPatchOptions[option.key] =
                                    dbOption.value.deserializeFor(option)
                            } catch (e: Option.SerializationException) {
                                Log.w(
                                    tag,
                                    "Option ${dbOption.patchName}:${option.key} could not be deserialized",
                                    e
                                )
                            }
                        }

                        bundlePatchOptions
                    }
            }
        }
    }

    suspend fun saveOptions(packageName: String, options: Options) {
        patchOptionInputManager.updateReferences {
            dao.updateOptions(options.entries.associate { (sourceUid, bundlePatchOptions) ->
                val groupId = getOrCreateGroup(sourceUid, packageName)

                groupId to bundlePatchOptions.flatMap { (patchName, patchOptions) ->
                    patchOptions.mapNotNull { (key, value) ->
                        val serialized = try {
                            Option.SerializedValue.fromValue(value)
                        } catch (e: Option.SerializationException) {
                            Log.e(tag, "Option $patchName:$key could not be serialized", e)
                            return@mapNotNull null
                        }

                        Option(groupId, patchName, key, serialized)
                    }
                }
            })
        }
    }

    suspend fun export(bundleUid: Int): SerializedOptions =
        buildMap {
            dao.exportOptions(bundleUid).forEach { (packageName, packageOptions) ->
                val serialized = packageOptions.fold(mutableMapOf<String, MutableMap<String, Option.SerializedValue>>()) { patches, option ->
                    patches.getOrPut(option.patchName, ::mutableMapOf)[option.key] = option.value
                    patches
                }.mapValues { it.value.toMap() }

                if (serialized.isNotEmpty()) {
                    put(packageName, serialized)
                }
            }
        }

    suspend fun import(bundleUid: Int, options: SerializedOptions) {
        patchOptionInputManager.updateReferences {
            dao.resetOptionsForPatchBundle(bundleUid)

            if (options.isNotEmpty()) {
                dao.updateOptions(options.entries.associate { (packageName, packageOptions) ->
                    val groupId = getOrCreateGroup(bundleUid, packageName)
                    groupId to packageOptions.flatMap { (patchName, patchOptions) ->
                        patchOptions.map { (key, value) ->
                            Option(groupId, patchName, key, value)
                        }
                    }
                })
            }
        }

        resetEventsFlow.emit(ResetEvent.Bundle(bundleUid))
    }

    fun getPackagesWithSavedOptions() =
        dao.getPackagesWithOptions().map(Iterable<String>::toSet).distinctUntilChanged()

    suspend fun removeOptionsForPatches(
        packageName: String,
        patchesByBundle: Map<Int, Set<String>>
    ) {
        patchOptionInputManager.updateReferences {
            patchesByBundle.forEach { (bundleUid, patchNames) ->
                if (patchNames.isNotEmpty()) {
                    dao.removeOptionsForPatches(bundleUid, packageName, patchNames.toList())
                }
            }
        }
    }

    suspend fun resetOptionsForPackage(packageName: String) {
        patchOptionInputManager.updateReferences {
            dao.resetOptionsForPackage(packageName)
        }
        resetEventsFlow.emit(ResetEvent.Package(packageName))
    }

    suspend fun resetOptionsForPatchBundle(uid: Int) {
        patchOptionInputManager.updateReferences {
            dao.resetOptionsForPatchBundle(uid)
        }
        resetEventsFlow.emit(ResetEvent.Bundle(uid))
    }

    suspend fun reset() {
        patchOptionInputManager.updateReferences {
            dao.reset()
        }
        resetEventsFlow.emit(ResetEvent.All)
    }

    sealed interface ResetEvent {
        data object All : ResetEvent
        data class Package(val packageName: String) : ResetEvent
        data class Bundle(val bundleUid: Int) : ResetEvent
    }
}

/**
 * A [Map] of package name -> patch name -> option key -> serialized option value.
 */
typealias SerializedOptions = Map<String, Map<String, Map<String, Option.SerializedValue>>>
