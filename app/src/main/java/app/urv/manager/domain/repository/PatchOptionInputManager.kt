package app.urv.manager.domain.repository

import android.util.Log
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.room.AppDatabase
import app.urv.manager.data.room.options.Option
import app.urv.manager.data.room.profile.PatchProfilePayload
import app.urv.manager.util.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PatchOptionInputManager(
    db: AppDatabase,
    private val filesystem: Filesystem
) {
    private val optionDao = db.optionDao()
    private val patchProfileDao = db.patchProfileDao()
    private val installedAppDao = db.installedAppDao()
    private val referenceMutationMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownershipLock = Any()
    private val activeOwnershipCounts = mutableMapOf<String, Int>()

    init {
        cleanupScope.launch {
            referenceMutationMutex.withLock {
                pruneUnreferencedInputs()
            }
            while (true) {
                val delayMillis =
                    filesystem.millisUntilNextRestoredPatchOptionInputExpiry() ?: break
                if (delayMillis > 0L) {
                    delay(delayMillis)
                }
                referenceMutationMutex.withLock {
                    filesystem.releaseExpiredRestoredPatchOptionInputs()
                    pruneUnreferencedInputs()
                }
            }
        }
    }

    /**
     * Serializes database changes that can add or remove provider-backed input references, then
     * reclaims inputs only after the change commits. Profiles and installed-app snapshots are
     * included because they can retain option values after the regular saved options are reset.
     */
    suspend fun <T> updateReferences(mutation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            referenceMutationMutex.withLock {
                val result = mutation()
                pruneUnreferencedInputs()
                result
            }
        }

    fun pendingInputsIn(value: Any?): Set<String> =
        filesystem.claimPatchOptionInputs(collectStringValues(value))

    internal fun pendingInputOwnership(
        inherited: Set<String> = emptySet(),
        initiallyReferenced: Set<String> = emptySet()
    ) = PendingPatchOptionInputOwnership(
        inherited = inherited,
        initiallyReferenced = initiallyReferenced,
        onAcquire = ::acquirePendingInputOwnership,
        onRelease = ::releasePendingInputOwnership
    )

    private fun acquirePendingInputOwnership(paths: Set<String>) {
        if (paths.isEmpty()) return
        synchronized(ownershipLock) {
            filesystem.claimPatchOptionInputs(paths).forEach { path ->
                activeOwnershipCounts[path] = activeOwnershipCounts.getOrDefault(path, 0) + 1
            }
        }
    }

    private fun releasePendingInputOwnership(paths: Set<String>) {
        if (paths.isEmpty()) return
        synchronized(ownershipLock) {
            paths.forEach { path ->
                val remaining = activeOwnershipCounts.getOrDefault(path, 0) - 1
                if (remaining > 0) {
                    activeOwnershipCounts[path] = remaining
                } else {
                    activeOwnershipCounts.remove(path)
                }
            }
        }
        cleanupScope.launch {
            referenceMutationMutex.withLock {
                synchronized(ownershipLock) {
                    val noLongerOwned = paths.filterNot(activeOwnershipCounts::containsKey)
                    filesystem.releasePendingPatchOptionInputs(noLongerOwned)
                }
                pruneUnreferencedInputs()
            }
        }
    }

    private suspend fun pruneUnreferencedInputs() {
        try {
            val retainedPaths = collectReferencedPatchOptionInputPaths(
                optionValues = optionDao.getAllValues(),
                payloads = buildList {
                    patchProfileDao.getAll().mapTo(this) { it.payload }
                    installedAppDao.getAllSnapshot().mapNotNullTo(this) { it.selectionPayload }
                }
            )
            filesystem.prunePatchOptionInputs(retainedPaths)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(tag, "Unable to prune unreferenced patch option inputs", error)
        }
    }
}

internal fun collectReferencedPatchOptionInputPaths(
    optionValues: Iterable<Option.SerializedValue>,
    payloads: Iterable<PatchProfilePayload>
): Set<String> = buildSet {
    optionValues.forEach(::addSerializedValue)
    payloads.forEach(::addPayload)
}

private fun MutableSet<String>.addPayload(payload: PatchProfilePayload) {
    payload.bundles.forEach { bundle ->
        bundle.options.values.forEach { patchOptions ->
            patchOptions.values.forEach(::addSerializedValue)
        }
    }
}

private fun MutableSet<String>.addSerializedValue(value: Option.SerializedValue) {
    addJsonStrings(value.raw)
}

private fun MutableSet<String>.addJsonStrings(value: JsonElement) {
    when (value) {
        is JsonPrimitive -> if (value.isString) add(value.content)
        is JsonArray -> value.forEach(::addJsonStrings)
        is JsonObject -> value.values.forEach(::addJsonStrings)
        else -> Unit
    }
}

internal fun collectStringValues(value: Any?): Set<String> = buildSet {
    fun collect(candidate: Any?) {
        when (candidate) {
            is String -> add(candidate)
            is Map<*, *> -> candidate.values.forEach(::collect)
            is Iterable<*> -> candidate.forEach(::collect)
            is Array<*> -> candidate.forEach(::collect)
        }
    }

    collect(value)
}

internal data class PendingInputTransfer(
    val transferred: Set<String>,
    val released: Set<String>
)

internal class PendingPatchOptionInputOwnership(
    private val inherited: Set<String> = emptySet(),
    initiallyReferenced: Set<String> = emptySet(),
    private val onAcquire: (Set<String>) -> Unit = {},
    private val onRelease: (Set<String>) -> Unit = {}
) {
    private val lock = Any()
    private val protectedPaths = initiallyReferenced.toMutableSet()

    init {
        onAcquire(protectedPaths)
    }

    fun accept(paths: Collection<String>) {
        synchronized(lock) {
            val acquired = paths.filterTo(mutableSetOf()) { it !in protectedPaths }
            protectedPaths.addAll(paths)
            onAcquire(acquired)
        }
    }

    fun reconcile(referenced: Set<String>): Set<String> =
        synchronized(lock) {
            val acquired = referenced - protectedPaths
            val released = protectedPaths - referenced
            protectedPaths.retainAll(referenced)
            protectedPaths.addAll(acquired)
            onAcquire(acquired)
            onRelease(released)
            released
        }

    fun transfer(referenced: Set<String>): PendingInputTransfer =
        synchronized(lock) {
            val transferred = referenced - inherited
            val released = protectedPaths - referenced
            val acquired = referenced - protectedPaths
            // Keep current paths protected until this owner is cleared. The receiving owner
            // accepts transferred paths before navigation removes the sending ViewModel.
            protectedPaths.retainAll(referenced)
            protectedPaths.addAll(acquired)
            onAcquire(acquired)
            onRelease(released)
            PendingInputTransfer(transferred, released)
        }

    fun releaseAll(): Set<String> =
        synchronized(lock) {
            protectedPaths.toSet().also {
                onRelease(it)
                protectedPaths.clear()
            }
        }
}
