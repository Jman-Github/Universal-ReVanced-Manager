package app.revanced.manager.ui.model

import kotlinx.serialization.Serializable

@Serializable
enum class InstalledAppAction {
    OPEN,
    EXPORT,
    INSTALL_OR_UPDATE,
    UNINSTALL,
    DELETE,
    REPATCH
}
