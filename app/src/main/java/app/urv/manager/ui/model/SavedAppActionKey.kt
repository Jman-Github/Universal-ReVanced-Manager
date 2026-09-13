package app.urv.manager.ui.model

import androidx.annotation.StringRes
import app.universal.revanced.manager.R

enum class SavedAppActionKey(val storageId: String, @StringRes val labelRes: Int) {
    OPEN("open", R.string.open_app),
    EXPORT("export", R.string.export),
    INSTALL_UPDATE("install_update", R.string.saved_app_action_install_update),
    REPAIR_ROOT_MOUNT("repair_root_mount", R.string.root_mount_repair),
    EXPORT_ROOT_MOUNT_DIAGNOSTICS(
        "export_root_mount_diagnostics",
        R.string.root_mount_export_diagnostics
    ),
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
