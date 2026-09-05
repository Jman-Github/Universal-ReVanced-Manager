package app.urv.manager.domain.repository

import app.urv.manager.network.api.ReVancedAPI
import app.urv.manager.network.dto.ReVancedAnnouncement
import app.urv.manager.network.dto.ReVancedAnnouncementTag
import app.urv.manager.network.utils.getOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnnouncementRepository(
    private val api: ReVancedAPI
) {
    private val mutex = Mutex()
    private var cachedAnnouncements: List<ReVancedAnnouncement>? = null
    private var cachedTags: List<ReVancedAnnouncementTag>? = null

    suspend fun getAnnouncements(forceRefresh: Boolean = false): List<ReVancedAnnouncement>? {
        return mutex.withLock {
            if (cachedAnnouncements == null || forceRefresh) {
                cachedAnnouncements = api.getAnnouncements().getOrNull()
            }
            cachedAnnouncements
        }
    }

    suspend fun getTags(forceRefresh: Boolean = false): List<ReVancedAnnouncementTag>? {
        return mutex.withLock {
            if (cachedTags == null || forceRefresh) {
                cachedTags = api.getAnnouncementTags().getOrNull()
            }
            cachedTags
        }
    }
}
