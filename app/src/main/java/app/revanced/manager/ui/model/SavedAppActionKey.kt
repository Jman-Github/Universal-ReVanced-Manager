package app.revanced.manager.ui.model

import androidx.annotation.StringRes
import app.universal.revanced.manager.R

enum class SavedAppActionKey(val storageId: String, @StringRes val labelRes: Int) {
    OPEN("open", R.string.open_app),
    EXPORT("export", R.string.export),
    INSTALL_UPDATE("install_update", R.string.saved_app_action_install_update),
    DELETE("delete", R.string.delete),
    REPATCH("repatch", R.string.repatch);

    companion object {
        val DefaultOrder: List<SavedAppActionKey> = values().toList() // keep in default order for new installs

        fun fromStorageId(id: String): SavedAppActionKey? =
            values().firstOrNull { it.storageId == id }

        fun ensureComplete(order: List<SavedAppActionKey>): List<SavedAppActionKey> {
            if (order.isEmpty()) return DefaultOrder
            val missing = values().filterNot(order::contains)
            return (order + missing).take(values().size)
        }
    }
}
