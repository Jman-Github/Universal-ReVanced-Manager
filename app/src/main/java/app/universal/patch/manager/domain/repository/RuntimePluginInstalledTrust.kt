package app.universal.patch.manager.domain.repository

/** Local approval follows the Android installation, whose identity survives APK updates.
 * Remote archives have no such installation identity and continue to use signer trust.
 */
internal data class RuntimePluginInstalledTrust(
    val signer: String,
    val packageName: String? = null,
    val uid: Int? = null,
    val firstInstallTime: Long? = null
) {
    fun trusts(
        actualPackageName: String,
        actualUid: Int,
        actualFirstInstallTime: Long,
        signers: RuntimePluginSigners
    ): Boolean {
        if (packageName != null && packageName != actualPackageName) return false
        if (signers.current.isEmpty()) return false
        val sameInstallation = packageName != null && uid != null && uid > 0 &&
            firstInstallTime != null && firstInstallTime > 0 &&
            uid == actualUid && firstInstallTime == actualFirstInstallTime
        return sameInstallation || signers.trusts(signer)
    }

    fun encode(): String {
        if (packageName.isNullOrBlank() || uid == null || uid <= 0 ||
            firstInstallTime == null || firstInstallTime <= 0) return signer
        return "v1|$packageName|$uid|$firstInstallTime|$signer"
    }

    companion object {
        fun decode(value: String?): RuntimePluginInstalledTrust? {
            if (value.isNullOrBlank()) return null
            // Older manager versions stored just the approved certificate.
            if (!value.startsWith("v1|")) return RuntimePluginInstalledTrust(value)
            val parts = value.split('|', limit = 5)
            if (parts.size != 5 || parts[1].isBlank() || parts[4].isBlank()) return null
            val uid = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val firstInstallTime = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return RuntimePluginInstalledTrust(parts[4], parts[1], uid, firstInstallTime)
        }
    }
}
