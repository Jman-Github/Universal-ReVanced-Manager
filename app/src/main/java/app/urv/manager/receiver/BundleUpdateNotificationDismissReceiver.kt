package app.urv.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BundleUpdateNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BUNDLE_UPDATE_NOTIFICATION_DISMISSED) return
        val markers = intent.getStringArrayExtra(EXTRA_DISMISSAL_MARKERS)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        if (markers.isEmpty()) return

        val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { addAll(markers) }
        prefs.edit()
            .putStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, updated)
            .apply()
    }

    companion object {
        const val ACTION_BUNDLE_UPDATE_NOTIFICATION_DISMISSED =
            "app.urv.manager.action.BUNDLE_UPDATE_NOTIFICATION_DISMISSED"
        const val EXTRA_DISMISSAL_MARKERS = "dismissal_markers"
        private const val PREFS_FILE = "bundle_update_notifications"
        private const val KEY_DISMISSED_MANUAL_UPDATE_MARKERS = "dismissed_manual_update_markers"

        fun dismissedMarkers(context: Context): Set<String> =
            context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, emptySet())
                .orEmpty()
                .toSet()

        fun clearDismissedMarkers(context: Context, markers: Set<String>) {
            if (markers.isEmpty()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val updated = prefs.getStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { removeAll(markers) }
            prefs.edit()
                .putStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, updated)
                .apply()
        }
    }
}
