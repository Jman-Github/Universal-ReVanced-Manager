package app.urv.manager.util

import android.content.Intent
import android.net.Uri
import app.urv.manager.ui.model.navigation.Announcement

data class BundleDeepLink(
    val bundleUid: Int?,
    val importUrl: String? = null
)

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
        importUrlFromIntent(intent)?.let { url ->
            return BundleDeepLink(bundleUid = null, importUrl = url)
        }
        val hasUid = intent.hasExtra(EXTRA_BUNDLE_UID)
        if (!intent.getBooleanExtra(EXTRA_OPEN_BUNDLES_TAB, false) && !hasUid) return null
        val uid = if (hasUid) intent.getIntExtra(EXTRA_BUNDLE_UID, 0) else null
        return BundleDeepLink(uid)
    }

    private fun importUrlFromIntent(intent: Intent): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        return uri.toString().takeIf { uri.isSupportedMorpheSourceUrl() }
    }

    private fun Uri.isSupportedMorpheSourceUrl(): Boolean {
        val scheme = scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (!host.equals("morphe.software", ignoreCase = true)) return false
        if (path != "/add-source") return false
        return !getQueryParameter("github").isNullOrBlank() ||
            !getQueryParameter("gitlab").isNullOrBlank()
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

object AnnouncementDeepLinkIntent {
    private const val EXTRA_OPEN_ANNOUNCEMENT = "open_announcement"
    private const val EXTRA_ANNOUNCEMENT_ID = "announcement_id"
    private const val EXTRA_ANNOUNCEMENT_AUTHOR = "announcement_author"
    private const val EXTRA_ANNOUNCEMENT_TITLE = "announcement_title"
    private const val EXTRA_ANNOUNCEMENT_CONTENT = "announcement_content"
    private const val EXTRA_ANNOUNCEMENT_TAGS = "announcement_tags"
    private const val EXTRA_ANNOUNCEMENT_CREATED_AT = "announcement_created_at"
    private const val EXTRA_ANNOUNCEMENT_ARCHIVED_AT = "announcement_archived_at"
    private const val EXTRA_ANNOUNCEMENT_LEVEL = "announcement_level"

    fun addOpenAnnouncement(intent: Intent, announcement: Announcement.Payload): Intent {
        intent.putExtra(EXTRA_OPEN_ANNOUNCEMENT, true)
        intent.putExtra(EXTRA_ANNOUNCEMENT_ID, announcement.id)
        intent.putExtra(EXTRA_ANNOUNCEMENT_AUTHOR, announcement.author)
        intent.putExtra(EXTRA_ANNOUNCEMENT_TITLE, announcement.title)
        intent.putExtra(EXTRA_ANNOUNCEMENT_CONTENT, announcement.content)
        intent.putStringArrayListExtra(
            EXTRA_ANNOUNCEMENT_TAGS,
            ArrayList(announcement.tags)
        )
        intent.putExtra(EXTRA_ANNOUNCEMENT_CREATED_AT, announcement.createdAt)
        intent.putExtra(EXTRA_ANNOUNCEMENT_ARCHIVED_AT, announcement.archivedAt)
        intent.putExtra(EXTRA_ANNOUNCEMENT_LEVEL, announcement.level)
        return intent
    }

    fun fromIntent(intent: Intent?): Announcement.Payload? {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_ANNOUNCEMENT, false)) return null
        return Announcement.Payload(
            id = intent.getLongExtra(EXTRA_ANNOUNCEMENT_ID, 0L),
            author = intent.getStringExtra(EXTRA_ANNOUNCEMENT_AUTHOR).orEmpty(),
            title = intent.getStringExtra(EXTRA_ANNOUNCEMENT_TITLE).orEmpty(),
            content = intent.getStringExtra(EXTRA_ANNOUNCEMENT_CONTENT).orEmpty(),
            tags = intent.getStringArrayListExtra(EXTRA_ANNOUNCEMENT_TAGS).orEmpty(),
            createdAt = intent.getStringExtra(EXTRA_ANNOUNCEMENT_CREATED_AT).orEmpty(),
            archivedAt = intent.getStringExtra(EXTRA_ANNOUNCEMENT_ARCHIVED_AT),
            level = intent.getIntExtra(EXTRA_ANNOUNCEMENT_LEVEL, 0)
        )
    }
}
