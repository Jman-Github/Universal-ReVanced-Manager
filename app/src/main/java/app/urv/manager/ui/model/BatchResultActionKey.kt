package app.urv.manager.ui.model

import androidx.annotation.StringRes
import app.universal.revanced.manager.R

enum class BatchResultActionKey(val storageId: String, @StringRes val labelRes: Int) {
    VIEW_PROGRESS("view_progress", R.string.batch_patch_view_progress),
    SAVE_LOGS("save_logs", R.string.save_logs),
    SAVE_APK("save_apk", R.string.save_apk),
    INSTALL_OR_OPEN("install_or_open", R.string.batch_result_action_install_or_open);

    companion object {
        val DefaultOrder: List<BatchResultActionKey> = values().toList()

        fun fromStorageId(id: String): BatchResultActionKey? =
            values().firstOrNull { it.storageId == id }

        fun ensureComplete(order: List<BatchResultActionKey>): List<BatchResultActionKey> {
            if (order.isEmpty()) return DefaultOrder
            val missing = values().filterNot(order::contains)
            return (order + missing).take(values().size)
        }
    }
}
