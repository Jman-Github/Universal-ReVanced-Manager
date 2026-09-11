package app.urv.manager.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.urv.manager.data.platform.NetworkInfo
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.AnnouncementRepository
import app.urv.manager.network.dto.ReVancedAnnouncement
import app.urv.manager.util.announcementTagKey
import app.urv.manager.util.distinctAnnouncementTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnnouncementSections(
    val activeAnnouncements: List<ReVancedAnnouncement>,
    val archivedAnnouncements: List<ReVancedAnnouncement>
) {
    val isEmpty: Boolean
        get() = activeAnnouncements.isEmpty() && archivedAnnouncements.isEmpty()
}

class AnnouncementsViewModel(
    private val announcementRepository: AnnouncementRepository,
    private val networkInfo: NetworkInfo,
    private val preferences: PreferencesManager
) : ViewModel() {
    private val allAnnouncements = MutableStateFlow<List<ReVancedAnnouncement>?>(null)
    val tags = allAnnouncements.map { announcements ->
        announcements
            ?.flatMap { it.tags }
            ?.distinctAnnouncementTags()
    }
    val selectedTags = preferences.selectedAnnouncementTags
    val readAnnouncements = preferences.readAnnouncements

    val announcements = combine(
        allAnnouncements,
        selectedTags.flow
    ) { source, selected ->
        if (source == null) return@combine null

        val availableKeys = source
            .flatMap { it.tags }
            .map(::announcementTagKey)
            .toSet()
        val selectedKeys = selected
            .map(::announcementTagKey)
            .filter { it in availableKeys }
            .toSet()

        if (selectedKeys.isEmpty()) {
            source
        } else {
            source.filter { announcement ->
                announcement.tags.any { tag -> announcementTagKey(tag) in selectedKeys }
            }
        }
    }
    val announcementSections = announcements.map { items ->
        items?.let { announcements ->
            val now = System.currentTimeMillis()
            val (activeAnnouncements, archivedAnnouncements) = announcements.partition { announcement ->
                val archivedAt = announcement.archivedAt
                    ?.toEpochMilliseconds()
                archivedAt == null || archivedAt > now
            }
            AnnouncementSections(
                activeAnnouncements = activeAnnouncements,
                archivedAnnouncements = archivedAnnouncements
            )
        }
    }

    init {
        viewModelScope.launch {
            preferences.announcementSystemEnabled.flow.collect { enabled ->
                if (!enabled) {
                    allAnnouncements.value = emptyList()
                }
            }
        }
    }

    fun refresh(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (!preferences.announcementSystemEnabled.get() || !networkInfo.isConnected()) {
                allAnnouncements.value = emptyList()
                return@launch
            }

            val announcements = withContext(Dispatchers.IO) {
                announcementRepository.getAnnouncements(forceRefresh).orEmpty()
            }

            if (!preferences.announcementSystemEnabled.get()) {
                allAnnouncements.value = emptyList()
                return@launch
            }

            allAnnouncements.value = announcements
        }
    }

    fun markAnnouncementRead(id: Long) {
        viewModelScope.launch {
            preferences.edit {
                preferences.readAnnouncements += id.toString()
            }
        }
    }

    fun changeTagSelection(tag: String) {
        viewModelScope.launch {
            preferences.edit {
                val current = preferences.selectedAnnouncementTags.value
                val tagKey = announcementTagKey(tag)
                preferences.selectedAnnouncementTags.value = if (current.any { announcementTagKey(it) == tagKey }) {
                    current.filterNot { announcementTagKey(it) == tagKey }.toSet()
                } else {
                    current + tag
                }
            }
        }
    }

    fun resetTagSelection() {
        viewModelScope.launch {
            preferences.selectedAnnouncementTags.update(PreferencesManager.DEFAULT_ANNOUNCEMENT_TAGS)
        }
    }
}
