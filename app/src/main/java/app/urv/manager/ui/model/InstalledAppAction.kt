package app.urv.manager.ui.model

import kotlinx.serialization.Serializable

@Serializable
enum class InstalledAppAction {
    OPEN,
    EXPORT,
    INSTALL_OR_UPDATE,
    REPAIR_ROOT_MOUNT,
    EXPORT_ROOT_MOUNT_DIAGNOSTICS,
    UNINSTALL,
    DELETE,
    REPATCH
}
