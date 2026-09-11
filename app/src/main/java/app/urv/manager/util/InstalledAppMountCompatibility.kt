package app.urv.manager.util

import app.urv.manager.data.room.apps.installed.InstalledApp

// Code adapted from Morphe, see third-party/NOTICE for more information
// https://github.com/MorpheApp/morphe-manager/pull/779
/**
 * Root mount replaces the original package in place, so patched APKs that changed their package
 * name must use a regular installer. [patchedPackageName] handles legacy or synthetic saved entries
 * whose database key does not directly expose the APK package name.
 */
fun InstalledApp.supportsRootMount(patchedPackageName: String? = null): Boolean {
    val resolvedPackageName = patchedPackageName ?: savedAppBasePackage(currentPackageName)
    return resolvedPackageName == originalPackageName
}
