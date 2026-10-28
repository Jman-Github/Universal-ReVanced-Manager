package app.universal.patch.manager.domain.repository

import java.util.Locale

/** Signer history comes from Android's verified APK signing information.
 * https://developer.android.com/reference/android/content/pm/SigningInfo#getSigningCertificateHistory()
 */
internal data class RuntimePluginSigners(
    val current: List<String>,
    val history: List<String> = current
) {
    val identity: String
        get() = current.map(::normalize).distinct().sorted().joinToString(",")

    fun trusts(expected: String?): Boolean {
        if (expected.isNullOrBlank() || current.isEmpty()) return false
        val pinned = expected.split(",").map(::normalize).toSet()
        val signers = current.map(::normalize).toSet()
        // A multi-signer APK's identity is the complete set, never any single signer.
        if (signers.size > 1 || pinned.size > 1) return signers == pinned
        return pinned.single() in (history + current).map(::normalize)
    }

    private fun normalize(value: String) = value.trim().uppercase(Locale.ROOT)
}
