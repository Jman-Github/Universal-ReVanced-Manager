package app.urv.manager.ui.model

import androidx.annotation.StringRes
import app.universal.revanced.manager.R

enum class PatchProfileActionKey(val storageId: String, @StringRes val labelRes: Int) {
    RENAME("rename", R.string.patch_profile_rename),
    VIEW_PATCHES("view_patches", R.string.patch_profile_show_more),
    SETTINGS("settings", R.string.settings);

    companion object {
        val DefaultOrder: List<PatchProfileActionKey> = values().toList()

        fun fromStorageId(id: String): PatchProfileActionKey? =
            values().firstOrNull { it.storageId == id }

        fun ensureComplete(order: List<PatchProfileActionKey>): List<PatchProfileActionKey> {
            if (order.isEmpty()) return DefaultOrder
            val missing = values().filterNot(order::contains)
            return (order + missing).take(values().size)
        }
    }
}
