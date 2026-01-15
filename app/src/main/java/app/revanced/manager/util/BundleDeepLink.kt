package app.revanced.manager.util

import android.content.Intent

data class BundleDeepLink(val bundleUid: Int)

object BundleDeepLinkIntent {
    const val EXTRA_BUNDLE_UID = "bundle_uid"

    fun addBundleUid(intent: Intent, bundleUid: Int?): Intent {
        if (bundleUid != null) {
            intent.putExtra(EXTRA_BUNDLE_UID, bundleUid)
        }
        return intent
    }

    fun fromIntent(intent: Intent?): BundleDeepLink? {
        if (intent == null) return null
        val uid = intent.getIntExtra(EXTRA_BUNDLE_UID, -1)
        if (uid < 0) return null
        return BundleDeepLink(uid)
    }
}
