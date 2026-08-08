package app.urv.manager.ui.model

import androidx.annotation.StringRes
import app.universal.revanced.manager.R

enum class LsposedModuleActionKey(val storageId: String, @StringRes val labelRes: Int) {
    MANAGER("manager", R.string.lsposed_manager),
    SETTINGS("settings", R.string.lsposed_settings),
    UPDATE("update", R.string.lsposed_check_update_action),
    REINSTALL("reinstall", R.string.lsposed_reinstall),
    UNINSTALL("uninstall", R.string.lsposed_uninstall),
    FORGET("forget", R.string.lsposed_forget);

    companion object {
        val DefaultOrder: List<LsposedModuleActionKey> = values().toList()

        fun fromStorageId(id: String): LsposedModuleActionKey? =
            values().firstOrNull { it.storageId == id }

        fun ensureComplete(order: List<LsposedModuleActionKey>): List<LsposedModuleActionKey> {
            if (order.isEmpty()) return DefaultOrder
            val missing = values().filterNot(order::contains)
            return (order + missing).take(values().size)
        }
    }
}
