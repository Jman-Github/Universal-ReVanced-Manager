package app.revanced.manager.util

import android.annotation.SuppressLint
import android.content.pm.PackageInstaller
import ru.solrudev.ackpine.installer.InstallFailure
import ru.solrudev.ackpine.uninstaller.UninstallFailure

fun InstallFailure.asCode() = when (this) {
    is InstallFailure.Aborted -> PackageInstaller.STATUS_FAILURE_ABORTED
    is InstallFailure.Blocked -> PackageInstaller.STATUS_FAILURE_BLOCKED
    is InstallFailure.Conflict -> PackageInstaller.STATUS_FAILURE_CONFLICT
    is InstallFailure.Incompatible -> PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
    is InstallFailure.Invalid -> PackageInstaller.STATUS_FAILURE_INVALID
    is InstallFailure.Storage -> PackageInstaller.STATUS_FAILURE_STORAGE
    is InstallFailure.Timeout -> @SuppressLint("InlinedApi") PackageInstaller.STATUS_FAILURE_TIMEOUT
    else -> PackageInstaller.STATUS_FAILURE
}

fun UninstallFailure.asCode() = when (this) {
    is UninstallFailure.Aborted -> PackageInstaller.STATUS_FAILURE_ABORTED
    is UninstallFailure.Blocked -> PackageInstaller.STATUS_FAILURE_BLOCKED
    is UninstallFailure.Conflict -> PackageInstaller.STATUS_FAILURE_CONFLICT
    else -> PackageInstaller.STATUS_FAILURE
}
