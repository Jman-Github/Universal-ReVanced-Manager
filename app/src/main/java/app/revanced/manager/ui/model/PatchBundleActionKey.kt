package app.revanced.manager.ui.model

import androidx.annotation.StringRes
import app.universal.revanced.manager.R

enum class PatchBundleActionKey(val storageId: String, @StringRes val labelRes: Int) {
    EDIT("edit", R.string.edit),
    REFRESH("refresh", R.string.refresh),
    LINKS("links", R.string.bundle_links),
    CHANGELOG_LATEST("changelog_latest", R.string.bundle_latest_changelog),
    CHANGELOG_HISTORY("changelog_history", R.string.bundle_previous_changelogs),
    TOGGLE("toggle", R.string.patch_bundle_action_toggle_label),
    DELETE("delete", R.string.delete);

    companion object {
        val DefaultOrder: List<PatchBundleActionKey> = values().toList()

        fun fromStorageId(id: String): PatchBundleActionKey? =
            values().firstOrNull { it.storageId == id }

        fun ensureComplete(order: List<PatchBundleActionKey>): List<PatchBundleActionKey> {
            if (order.isEmpty()) return DefaultOrder
            val missing = values().filterNot(order::contains)
            return (order + missing).take(values().size)
        }
    }
}
