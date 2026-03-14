package app.revanced.manager.util

import android.content.Intent

data class BundleDeepLink(val bundleUid: Int?)

object BundleDeepLinkIntent {
    const val EXTRA_BUNDLE_UID = "bundle_uid"
    const val EXTRA_OPEN_BUNDLES_TAB = "open_bundles_tab"

    fun addBundleUid(intent: Intent, bundleUid: Int?): Intent {
        intent.putExtra(EXTRA_OPEN_BUNDLES_TAB, true)
        if (bundleUid != null) {
            intent.putExtra(EXTRA_BUNDLE_UID, bundleUid)
        }
        return intent
    }

    fun fromIntent(intent: Intent?): BundleDeepLink? {
        if (intent == null) return null
        val hasUid = intent.hasExtra(EXTRA_BUNDLE_UID)
        if (!intent.getBooleanExtra(EXTRA_OPEN_BUNDLES_TAB, false) && !hasUid) return null
        val uid = if (hasUid) intent.getIntExtra(EXTRA_BUNDLE_UID, 0) else null
        return BundleDeepLink(uid)
    }
}

object ManagerUpdateDeepLinkIntent {
    private const val EXTRA_OPEN_MANAGER_UPDATE = "open_manager_update"

    fun addOpenManagerUpdate(intent: Intent): Intent {
        intent.putExtra(EXTRA_OPEN_MANAGER_UPDATE, true)
        return intent
    }

    fun shouldOpenManagerUpdate(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.getBooleanExtra(EXTRA_OPEN_MANAGER_UPDATE, false)
    }
}
