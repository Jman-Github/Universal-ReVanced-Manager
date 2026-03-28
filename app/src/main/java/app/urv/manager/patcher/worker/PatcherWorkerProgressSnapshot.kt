package app.urv.manager.patcher.worker

import android.os.Build
import android.os.Parcel
import androidx.work.Data
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.ProgressEventParcel
import app.urv.manager.patcher.toEvent
import app.urv.manager.patcher.toParcel

data class PatcherWorkerProgressSnapshot(
    val sequence: Long,
    val event: ProgressEvent
)

object PatcherWorkerProgressState {
    private const val PROGRESS_SEQUENCE_KEY = "patching_progress_sequence"
    private const val PROGRESS_EVENT_KEY = "patching_progress_event"

    fun toWorkData(
        active: Boolean,
        snapshot: PatcherWorkerProgressSnapshot? = null
    ): Data {
        val builder = Data.Builder()
            .putBoolean(PatcherWorker.PATCHING_ACTIVE_KEY, active)

        if (snapshot != null) {
            builder.putLong(PROGRESS_SEQUENCE_KEY, snapshot.sequence)
            marshal(snapshot.event)?.let { payload ->
                builder.putByteArray(PROGRESS_EVENT_KEY, payload)
            }
        }

        return builder.build()
    }

    fun fromWorkData(data: Data): PatcherWorkerProgressSnapshot? {
        val payload = data.getByteArray(PROGRESS_EVENT_KEY) ?: return null
        val event = unmarshal(payload) ?: return null
        val sequence = data.getLong(PROGRESS_SEQUENCE_KEY, Long.MIN_VALUE)
        return PatcherWorkerProgressSnapshot(sequence = sequence, event = event)
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
        } finally {
            parcel.recycle()
        }
    }
}
