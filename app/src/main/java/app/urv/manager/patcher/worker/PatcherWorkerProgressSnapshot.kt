package app.urv.manager.patcher.worker

import android.os.Build
import android.os.Parcel
import android.util.Log
import androidx.work.Data
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.ProgressEventParcel
import app.urv.manager.patcher.toEvent
import app.urv.manager.patcher.toParcel

data class PatcherWorkerProgressSnapshot(
    val generation: Long,
    val sequence: Long,
    val event: ProgressEvent,
    val notificationProgressCurrent: Int? = null,
    val notificationProgressMax: Int? = null,
    val failedPatchIndexes: Set<Int> = emptySet()
)

object PatcherWorkerProgressState {
    private const val PROGRESS_GENERATION_KEY = "patching_progress_generation"
    private const val PROGRESS_SEQUENCE_KEY = "patching_progress_sequence"
    private const val PROGRESS_EVENT_KEY = "patching_progress_event"
    private const val PROGRESS_NOTIFICATION_CURRENT_KEY = "patching_progress_notification_current"
    private const val PROGRESS_NOTIFICATION_MAX_KEY = "patching_progress_notification_max"
    private const val PROGRESS_FAILED_PATCH_INDEXES_KEY = "patching_progress_failed_patch_indexes"

    fun toWorkData(
        active: Boolean,
        snapshot: PatcherWorkerProgressSnapshot? = null
    ): Data {
        val builder = Data.Builder()
            .putBoolean(PatcherWorker.PATCHING_ACTIVE_KEY, active)

        if (snapshot != null) {
            builder.putLong(PROGRESS_GENERATION_KEY, snapshot.generation)
            builder.putLong(PROGRESS_SEQUENCE_KEY, snapshot.sequence)
            marshal(snapshot.event)?.let { payload ->
                builder.putByteArray(PROGRESS_EVENT_KEY, payload)
            }
            snapshot.notificationProgressCurrent?.let { current ->
                builder.putInt(PROGRESS_NOTIFICATION_CURRENT_KEY, current)
            }
            snapshot.notificationProgressMax?.let { max ->
                builder.putInt(PROGRESS_NOTIFICATION_MAX_KEY, max)
            }
            if (snapshot.failedPatchIndexes.isNotEmpty()) {
                builder.putIntArray(
                    PROGRESS_FAILED_PATCH_INDEXES_KEY,
                    snapshot.failedPatchIndexes.sorted().toIntArray()
                )
            }
        }

        return builder.build()
    }

    fun fromWorkData(data: Data): PatcherWorkerProgressSnapshot? {
        val payload = data.getByteArray(PROGRESS_EVENT_KEY) ?: return null
        val event = unmarshal(payload) ?: return null
        val generation = data.getLong(PROGRESS_GENERATION_KEY, Long.MIN_VALUE)
        val sequence = data.getLong(PROGRESS_SEQUENCE_KEY, Long.MIN_VALUE)
        val notificationCurrent = if (data.keyValueMap.containsKey(PROGRESS_NOTIFICATION_CURRENT_KEY)) {
            data.getInt(PROGRESS_NOTIFICATION_CURRENT_KEY, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
        } else {
            null
        }
        val notificationMax = if (data.keyValueMap.containsKey(PROGRESS_NOTIFICATION_MAX_KEY)) {
            data.getInt(PROGRESS_NOTIFICATION_MAX_KEY, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
        } else {
            null
        }
        return PatcherWorkerProgressSnapshot(
            generation = generation,
            sequence = sequence,
            event = event,
            notificationProgressCurrent = notificationCurrent,
            notificationProgressMax = notificationMax,
            failedPatchIndexes = data.getIntArray(PROGRESS_FAILED_PATCH_INDEXES_KEY)?.toSet().orEmpty()
        )
    }

    private fun marshal(event: ProgressEvent): ByteArray? {
        val parcel = Parcel.obtain()
        return try {
            event.toParcel().writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun unmarshal(payload: ByteArray): ProgressEvent? {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(payload, 0, payload.size)
            parcel.setDataPosition(0)
            val restored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                parcel.readParcelable(
                    ProgressEventParcel::class.java.classLoader,
                    ProgressEventParcel::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                parcel.readParcelable(ProgressEventParcel::class.java.classLoader)
            }
            restored?.toEvent()
        } catch (e: Exception) {
            // Older app builds persisted progress snapshots with a different parcelable class name.
            // Treat unreadable snapshots as stale and skip replay instead of crashing on resume.
            Log.w(TAG, "Skipping stale patch worker progress snapshot", e)
            null
        } finally {
            parcel.recycle()
        }
    }

    private const val TAG = "PatcherWorkerProgress"
}
