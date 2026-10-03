package app.urv.manager.domain.installer

internal fun shouldApplyProfileInstallerPreference(
    chooseInstallerPerInstall: Boolean,
    installerMatchesPatchMode: Boolean,
    autoInstall: Boolean = false
): Boolean = installerMatchesPatchMode && (autoInstall || !chooseInstallerPerInstall)
