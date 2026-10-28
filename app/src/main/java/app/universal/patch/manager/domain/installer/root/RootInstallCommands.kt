package app.universal.patch.manager.domain.installer.root

/**
 * Pass a pipe to Package Manager, rather than a descriptor for a root-private APK.
 * Binder/SELinux can reject the latter before install-write even runs.
 * Android's shell supports pipefail so a failed APK read cannot look successful.
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java
 */
internal fun rootInstallWriteCommand(
    backend: String,
    sessionId: String,
    entryName: String,
    path: String,
    size: Long
): String {
    require(backend == "pm" || backend == "cmd package") { "Unsupported package installer backend" }
    require(sessionId.matches(Regex("[0-9]+"))) { "Invalid package install session" }
    require(size > 0L) { "Empty APK" }
    return "(set -o pipefail; cat ${shellQuote(path)} | " +
        "$backend install-write -S $size $sessionId ${shellQuote(entryName)} -)"
}
